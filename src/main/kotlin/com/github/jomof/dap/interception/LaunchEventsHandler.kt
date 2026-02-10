package com.github.jomof.dap.interception

import com.github.jomof.dap.DapSession.RequestAction
import com.github.jomof.dap.messages.*

/**
 * Injects CodeLLDB-compatible launch status output events.
 *
 * CodeLLDB sends `"Launching: {program}"` and `"Launched process {pid}"`
 * output events during launch. `lldb-dap` does not. This handler observes
 * the `launch` request to capture the program path, then injects the
 * equivalent output events before the `process` event from the backend.
 *
 * ## Injection point
 *
 * Both launch status messages are injected before the `process` event
 * because `lldb-dap`'s message ordering differs from CodeLLDB's:
 * `lldb-dap` may send the `process` event before the `configurationDone`
 * response. The `process` event is the reliable signal that the launch
 * has occurred.
 *
 * ## Thread safety
 *
 * [onRequest] is called from the client-reader coroutine; [onBackendMessage]
 * is called from the backend-reader coroutine. [launchProgram] uses
 * `@Volatile` for safe publication between coroutines.
 */
class LaunchEventsHandler : InterceptionHandler {

    /** Program path extracted from the most recent launch request. */
    @Volatile
    private var launchProgram: String? = null

    @Volatile
    private var launchedInjected = false

    override fun onRequest(request: DapRequest): RequestAction {
        if (request is LaunchRequest) {
            launchProgram = request.program
        }
        // Always forward — this handler only observes requests.
        return RequestAction.Forward
    }

    override fun onBackendMessage(message: DapMessage): List<DapMessage> {
        val program = launchProgram
        if (!launchedInjected && program != null && message is ProcessEvent) {
            launchedInjected = true
            return listOf(
                OutputEvent.console("Launching: $program\n"),
                OutputEvent.console("Launched process ${message.systemProcessId ?: "?"} from '$program'\n"),
                message,
            )
        }
        return listOf(message)
    }
}

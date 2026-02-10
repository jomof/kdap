package com.github.jomof.dap.interception

import com.github.jomof.dap.messages.ContinuedEvent
import com.github.jomof.dap.messages.DapMessage
import com.github.jomof.dap.messages.ProcessEvent

/**
 * Replaces lldb-dap's `process` event with a CodeLLDB-compatible `continued`
 * event.
 *
 * lldb-dap sends a `process` event when the debuggee launches; CodeLLDB does
 * not. Instead, CodeLLDB sends a `continued` event indicating the process has
 * started running. This handler makes KDAP's event stream match CodeLLDB's by
 * performing this substitution.
 *
 * **Must be registered after [LaunchEventsHandler]** in the handler chain so
 * that [LaunchEventsHandler] can still observe the original [ProcessEvent] to
 * extract the PID for its "Launched process" output event.
 */
class ProcessEventHandler : InterceptionHandler {

    override fun onBackendMessage(message: DapMessage): List<DapMessage> {
        if (message is ProcessEvent) {
            return listOf(ContinuedEvent(seq = 0, allThreadsContinued = true))
        }
        return listOf(message)
    }
}

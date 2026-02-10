package com.github.jomof.dap.interception

import com.github.jomof.dap.messages.ContinuedEvent
import com.github.jomof.dap.messages.DapMessage
import com.github.jomof.dap.messages.DapResponse

/**
 * Reorders the launch and configurationDone responses to match CodeLLDB's
 * sequence.
 *
 * lldb-dap sends the launch response immediately (before any events) and
 * the configurationDone response after the continued event. CodeLLDB sends
 * both responses together — after launch output events but before continued.
 *
 * This handler buffers the launch response and continued event, then
 * releases them in the correct order when the configurationDone response
 * arrives: `[launch_resp, configDone_resp, continued]`.
 *
 * **Must be registered last** in the handler chain (after [ProcessEventHandler]
 * which creates the [ContinuedEvent]).
 */
class LaunchResponseOrderHandler : InterceptionHandler {

    @Volatile private var pendingLaunchResponse: DapResponse? = null
    @Volatile private var pendingContinued: ContinuedEvent? = null

    override fun onBackendMessage(message: DapMessage): List<DapMessage> {
        // Buffer the launch response
        if (message is DapResponse && message.command == "launch") {
            pendingLaunchResponse = message
            return emptyList()
        }
        // Buffer the continued event
        if (message is ContinuedEvent) {
            pendingContinued = message
            return emptyList()
        }
        // When configurationDone response arrives, release everything in order
        if (message is DapResponse && message.command == "configurationDone") {
            val result = mutableListOf<DapMessage>()
            pendingLaunchResponse?.let { result.add(it) }
            pendingLaunchResponse = null
            result.add(message) // configurationDone response
            pendingContinued?.let { result.add(it) }
            pendingContinued = null
            return result
        }
        return listOf(message)
    }
}

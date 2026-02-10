package com.github.jomof.dap.interception

import com.github.jomof.dap.messages.CapabilitiesEvent
import com.github.jomof.dap.messages.DapMessage

/**
 * Suppresses lldb-dap's `capabilities` event to align with CodeLLDB.
 *
 * lldb-dap sends a `capabilities` event after the `initialized` event,
 * advertising additional capabilities (e.g., `supportsRestartRequest`,
 * `supportsStepInTargetsRequest`). The exact set varies by lldb-dap build
 * and platform. CodeLLDB does not send this event.
 *
 * This handler drops the event entirely so KDAP's event stream matches
 * CodeLLDB's. The capabilities are still available in the `initialize`
 * response, which is the standard DAP mechanism for capability discovery.
 */
class CapabilitiesEventHandler : InterceptionHandler {

    override fun onBackendMessage(message: DapMessage): List<DapMessage> {
        if (message is CapabilitiesEvent) {
            return emptyList()
        }
        return listOf(message)
    }
}

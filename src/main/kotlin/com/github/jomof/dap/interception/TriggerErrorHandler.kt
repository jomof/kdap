package com.github.jomof.dap.interception

import com.github.jomof.dap.DapSession.RequestAction
import com.github.jomof.dap.messages.DapRequest
import com.github.jomof.dap.messages.DapResponse
import com.github.jomof.dap.messages.UnknownRequest

/**
 * Test hook that handles the `_triggerError` command locally, returning an
 * error response without forwarding to the backend. This exercises KDAP's
 * own error-response path in integration tests.
 */
class TriggerErrorHandler : InterceptionHandler {
    override fun onRequest(request: DapRequest): RequestAction = when (request) {
        is UnknownRequest -> {
            if (request.command == METHOD_TRIGGER_ERROR) {
                RequestAction.Respond(
                    DapResponse.error(request.seq, request.command, INTERNAL_ERROR_MESSAGE)
                )
            } else {
                RequestAction.Forward
            }
        }
        else -> RequestAction.Forward
    }

    companion object {
        /** Test-only command intercepted locally to exercise KDAP's error-response path. */
        const val METHOD_TRIGGER_ERROR = "_triggerError"

        /** Error message returned by [METHOD_TRIGGER_ERROR]. */
        const val INTERNAL_ERROR_MESSAGE = "triggered internal error"
    }
}

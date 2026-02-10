package com.github.jomof.dap.interception

import com.github.jomof.dap.DapSession.RequestAction
import com.github.jomof.dap.debugsession.DebugSession
import com.github.jomof.dap.debugsession.handleTerminate
import com.github.jomof.dap.messages.DapRequest
import com.github.jomof.dap.messages.TerminateRequest

/**
 * Intercepts the `terminate` request and handles it asynchronously by
 * delegating to [DebugSession.handleTerminate][handleTerminate].
 */
class TerminateHandler(private val session: DebugSession) : InterceptionHandler {
    override fun onRequest(request: DapRequest): RequestAction = when (request) {
        is TerminateRequest -> RequestAction.HandleAsync { rawJson, ctx ->
            session.handleTerminate(rawJson, ctx)
        }
        else -> RequestAction.Forward
    }
}

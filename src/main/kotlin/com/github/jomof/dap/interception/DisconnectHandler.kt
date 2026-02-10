package com.github.jomof.dap.interception

import com.github.jomof.dap.DapSession.RequestAction
import com.github.jomof.dap.debugsession.DebugSession
import com.github.jomof.dap.debugsession.handleDisconnect
import com.github.jomof.dap.messages.DapRequest
import com.github.jomof.dap.messages.DisconnectRequest

/**
 * Intercepts the `disconnect` request and handles it asynchronously by
 * delegating to [DebugSession.handleDisconnect][handleDisconnect].
 */
class DisconnectHandler(private val session: DebugSession) : InterceptionHandler {
    override fun onRequest(request: DapRequest): RequestAction = when (request) {
        is DisconnectRequest -> RequestAction.HandleAsync { rawJson, ctx ->
            session.handleDisconnect(rawJson, ctx)
        }
        else -> RequestAction.Forward
    }
}

package com.github.jomof.dap.interception

import com.github.jomof.dap.DapSession.RequestAction
import com.github.jomof.dap.debugsession.DebugSession
import com.github.jomof.dap.debugsession.handleAttach
import com.github.jomof.dap.messages.AttachRequest
import com.github.jomof.dap.messages.DapRequest

/**
 * Intercepts the `attach` request and handles it asynchronously by
 * delegating to [DebugSession.handleAttach][handleAttach].
 */
class AttachHandler(private val session: DebugSession) : InterceptionHandler {
    override fun onRequest(request: DapRequest): RequestAction = when (request) {
        is AttachRequest -> RequestAction.HandleAsync { rawJson, ctx ->
            session.handleAttach(rawJson, ctx)
        }
        else -> RequestAction.Forward
    }
}

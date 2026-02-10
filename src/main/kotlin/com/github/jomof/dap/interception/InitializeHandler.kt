package com.github.jomof.dap.interception

import com.github.jomof.dap.DapSession.RequestAction
import com.github.jomof.dap.debugsession.DebugSession
import com.github.jomof.dap.debugsession.onInitialize
import com.github.jomof.dap.messages.DapRequest
import com.github.jomof.dap.messages.InitializeRequest

/**
 * Captures client capabilities from the `initialize` request and
 * forwards it to the backend unchanged.
 *
 * Delegates to [DebugSession.onInitialize][onInitialize] for the
 * actual state capture.
 */
class InitializeHandler(private val session: DebugSession) : InterceptionHandler {
    override fun onRequest(request: DapRequest): RequestAction = when (request) {
        is InitializeRequest -> {
            session.onInitialize(request)
            RequestAction.Forward
        }
        else -> RequestAction.Forward
    }
}

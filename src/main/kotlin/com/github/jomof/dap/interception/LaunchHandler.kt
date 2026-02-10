package com.github.jomof.dap.interception

import com.github.jomof.dap.DapSession.RequestAction
import com.github.jomof.dap.debugsession.DebugSession
import com.github.jomof.dap.debugsession.handleLaunch
import com.github.jomof.dap.messages.DapRequest
import com.github.jomof.dap.messages.LaunchRequest

/**
 * Intercepts the `launch` request and handles it asynchronously by
 * delegating to [DebugSession.handleLaunch][handleLaunch].
 */
class LaunchHandler(private val session: DebugSession) : InterceptionHandler {
    override fun onRequest(request: DapRequest): RequestAction = when (request) {
        is LaunchRequest -> RequestAction.HandleAsync { rawJson, ctx ->
            session.handleLaunch(rawJson, ctx)
        }
        else -> RequestAction.Forward
    }
}

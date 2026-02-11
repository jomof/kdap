package com.github.jomof.dap

import com.github.jomof.dap.DapSession.RequestAction
import com.github.jomof.dap.debugsession.DebugSession
import com.github.jomof.dap.interception.*
import com.github.jomof.dap.messages.DapMessage
import com.github.jomof.dap.messages.DapRequest

/**
 * Production interceptor for KDAP. Composes an ordered list of
 * [InterceptionHandler]s, each encapsulating a single concern with its
 * own state.
 *
 * ## Composition semantics
 *
 * **Requests**: every handler's [InterceptionHandler.onRequest] is called
 * so all can observe (e.g., to capture state). The first non-[RequestAction.Forward]
 * result is used as the action.
 *
 * **Backend messages**: handlers chain via `flatMap` — each handler
 * processes every message in the list produced by the previous handler,
 * allowing multiple handlers to inject messages at different points
 * without conflicting.
 *
 * ## Default handlers
 *
 * - [InitializeHandler] — captures client capabilities from initialize
 * - [LaunchHandler] — handles launch request via [DebugSession]
 * - [AttachHandler] — handles attach request via [DebugSession]
 * - [DisconnectHandler] — handles disconnect request via [DebugSession]
 * - [TerminateHandler] — handles terminate request via [DebugSession]
 * - [EvaluateContextRewriter] — rewrites CodeLLDB `_command` to `repl`
 * - [OutputCategoryNormalizer] — reclassifies debuggee console output as stdout
 * - [ExitStatusHandler] — reformats process exit output to match CodeLLDB
 * - [OutputCoalescingHandler] — combines consecutive stdout/stderr output events
 */
class KdapInterceptor(
    private val handlers: List<InterceptionHandler> = defaultHandlers(),
    private val sbWatcher: SBWatcher? = null,
) : DapSession.Interceptor {

    override fun onRequest(request: DapRequest): RequestAction {
        sbWatcher?.onMessage("→lldb-dap", request.toJson())
        var result: RequestAction = RequestAction.Forward
        for (handler in handlers) {
            val action = handler.onRequest(request)
            if (result is RequestAction.Forward && action !is RequestAction.Forward) {
                result = action
            }
        }
        return result
    }

    override fun onBackendMessage(message: DapMessage): List<DapMessage> {
        sbWatcher?.onMessage("←lldb-dap", message.toJson())
        var messages = listOf(message)
        for (handler in handlers) {
            messages = messages.flatMap { handler.onBackendMessage(it) }
        }
        return messages
    }

    companion object {
        fun defaultHandlers(sbWatcher: SBWatcher? = null): List<InterceptionHandler> {
            val session = DebugSession().apply {
                this.sbWatcher = sbWatcher
            }
            return listOf(
                InitializeHandler(session),        // captures client capabilities
                LaunchHandler(session),            // handles launch request
                AttachHandler(session),            // handles attach request
                DisconnectHandler(session),        // handles disconnect request
                TerminateHandler(session),         // handles terminate request
                EvaluateContextRewriter(),         // rewrites CodeLLDB _command → repl
                OutputCategoryNormalizer(session),  // reclassifies debuggee console → stdout
                ExitStatusHandler(),               // reformats exit output to match CodeLLDB
                OutputCoalescingHandler(),         // combines consecutive stdout/stderr events
            )
        }
    }
}

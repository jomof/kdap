package com.github.jomof.dap

import com.github.jomof.dap.DapSession.RequestAction
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
 * - [EvaluateContextRewriter] — rewrites CodeLLDB `_command` to `repl`
 * - [TriggerErrorHandler] — test hook for error-response path
 * - [ConsoleModeHandler] — injects console mode announcement
 * - [LaunchEventsHandler] — injects launch status output events
 * - [ProcessEventHandler] — replaces `process` event with `continued`
 * - [ExitStatusHandler] — reformats process exit output to match CodeLLDB
 * - [OutputCoalescingHandler] — combines consecutive stdout/stderr output events
 * - [LaunchResponseOrderHandler] — reorders launch/configDone responses to match CodeLLDB
 */
class KdapInterceptor(
    private val handlers: List<InterceptionHandler> = defaultHandlers(),
) : DapSession.Interceptor {

    override fun onRequest(request: DapRequest): RequestAction {
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
        var messages = listOf(message)
        for (handler in handlers) {
            messages = messages.flatMap { handler.onBackendMessage(it) }
        }
        return messages
    }

    companion object {
        fun defaultHandlers(): List<InterceptionHandler> = listOf(
            EvaluateContextRewriter(),
            TriggerErrorHandler(),
            ConsoleModeHandler(),
            LaunchEventsHandler(),
            ProcessEventHandler(),              // must follow LaunchEventsHandler
            ExitStatusHandler(),               // reformats exit output before coalescing
            OutputCoalescingHandler(),         // combines consecutive stdout/stderr events
            LaunchResponseOrderHandler(),       // must be last (sees ContinuedEvent from ProcessEventHandler)
        )
    }
}

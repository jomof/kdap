package com.github.jomof.dap.interception

import com.github.jomof.dap.DapSession.RequestAction
import com.github.jomof.dap.messages.DapMessage
import com.github.jomof.dap.messages.DapRequest

/**
 * A single, focused interception concern. Each handler encapsulates one
 * behavior (e.g., context rewriting, reverse event injection) with its
 * own state, independent from other handlers.
 *
 * Handlers are composed by [com.github.jomof.dap.KdapInterceptor]:
 *
 * - **Requests**: every handler's [onRequest] is called so all can observe.
 *   The first non-[RequestAction.Forward] result is used as the action.
 * - **Backend messages**: handlers chain via `flatMap` — each handler
 *   processes every message in the list produced by the previous handler,
 *   allowing multiple handlers to inject messages at different points
 *   without conflicting.
 *
 * ## Thread safety
 *
 * [onRequest] is called from the client-reader coroutine; [onBackendMessage]
 * is called from the backend-reader coroutine. Implementations that share
 * state between the two methods must use `@Volatile` or other
 * synchronization for safe publication.
 */
interface InterceptionHandler {
    /**
     * Called for each DAP request from the client. Return
     * [RequestAction.Forward] to pass through (the default), or another
     * action to intercept.
     *
     * Handlers that only need to *observe* a request (e.g., to capture
     * state for later use in [onBackendMessage]) should return
     * [RequestAction.Forward].
     */
    fun onRequest(request: DapRequest): RequestAction = RequestAction.Forward

    /**
     * Called for each DAP message from the backend. Returns the list of
     * messages that should replace the input message in the stream.
     *
     * - `listOf(message)` — forward unchanged (default)
     * - `listOf(extra, message)` — inject a message before
     * - `listOf(message, extra)` — inject a message after
     * - `emptyList()` — suppress the message entirely
     */
    fun onBackendMessage(message: DapMessage): List<DapMessage> = listOf(message)
}

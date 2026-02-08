package com.github.jomof.dap

import com.github.jomof.dap.DapSession.RequestAction

/**
 * Production interceptor for KDAP. Sits between the client and `lldb-dap`,
 * translating CodeLLDB-compatible requests into the form that `lldb-dap`
 * understands.
 *
 * ## CodeLLDB `_command` context
 *
 * CodeLLDB uses `evaluate` with `context: "_command"` to run LLDB commands
 * and return their output in the response body. `lldb-dap` uses the standard
 * `"repl"` context for the same purpose. This interceptor rewrites `_command`
 * to `repl` so clients accustomed to CodeLLDB work transparently.
 *
 * ## `_triggerError` (test hook)
 *
 * The `_triggerError` command is handled locally to exercise KDAP's own
 * error-response path without involving the backend.
 */
object KdapInterceptor : DapSession.Interceptor {

    /** Test-only command intercepted locally to exercise KDAP's error-response path. */
    const val METHOD_TRIGGER_ERROR = "_triggerError"

    /** Error message returned by [METHOD_TRIGGER_ERROR]. */
    const val INTERNAL_ERROR_MESSAGE = "triggered internal error"

    private val COMMAND_REGEX = """"command"\s*:\s*"([^"]+)"""".toRegex()
    private val SEQ_REGEX = """"seq"\s*:\s*(\d+)""".toRegex()
    private val COMMAND_CONTEXT_REGEX = """"context"\s*:\s*"_command"""".toRegex()

    override fun onRequest(request: String): RequestAction {
        val command = COMMAND_REGEX.find(request)?.groupValues?.get(1)

        // Test hook: handle _triggerError locally so the error-handling test
        // works without forwarding to the backend.
        if (command == METHOD_TRIGGER_ERROR) {
            val requestSeq = SEQ_REGEX.find(request)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            return RequestAction.Respond(
                buildErrorResponse(requestSeq, command, INTERNAL_ERROR_MESSAGE)
            )
        }

        // Rewrite CodeLLDB-style _command context to lldb-dap's repl context.
        if (command == "evaluate" && COMMAND_CONTEXT_REGEX.containsMatchIn(request)) {
            val modified = COMMAND_CONTEXT_REGEX.replace(request, """"context":"repl"""")
            return RequestAction.ForwardModified(modified)
        }

        return RequestAction.Forward
    }

    private fun buildErrorResponse(requestSeq: Int, command: String, message: String): String {
        val escaped = message.replace("\\", "\\\\").replace("\"", "\\\"")
        return """{"type":"response","request_seq":$requestSeq,"seq":0,"command":"$command","success":false,"message":"$escaped"}"""
    }
}

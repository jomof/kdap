package com.github.jomof.dap.interception

import com.github.jomof.dap.DapSession.RequestAction
import com.github.jomof.dap.messages.DapRequest
import com.github.jomof.dap.messages.EvaluateRequest

/**
 * Rewrites CodeLLDB-style `_command` evaluate context to `lldb-dap`'s `repl`
 * context.
 *
 * CodeLLDB uses `evaluate` with `context: "_command"` to run LLDB commands
 * and return their output in the response body. `lldb-dap` uses the standard
 * `"repl"` context for the same purpose. This handler rewrites `_command`
 * to `repl` so clients accustomed to CodeLLDB work transparently.
 */
class EvaluateContextRewriter : InterceptionHandler {
    override fun onRequest(request: DapRequest): RequestAction = when (request) {
        is EvaluateRequest -> {
            if (request.context == "_command") {
                RequestAction.ForwardModified(request.copy(context = "repl"))
            } else {
                RequestAction.Forward
            }
        }
        else -> RequestAction.Forward
    }
}

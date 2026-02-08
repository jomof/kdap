package com.github.jomof.dap

/**
 * Handles DAP JSON-RPC request bodies and returns response bodies.
 * Stateless; safe to reuse across requests.
 */
object DapRequestHandler {
    private val METHOD_REGEX = """"method"\s*:\s*"([^"]+)"""".toRegex()
    private val ID_REGEX = """"id"\s*:\s*(\d+)""".toRegex()

    /**
     * Test-only: method that throws so the server's catch block can be exercised.
     * Do not use in production configs.
     */
    const val METHOD_TRIGGER_ERROR = "_triggerError"

    /**
     * Parses [requestBody] (JSON-RPC request), dispatches by method, and returns the JSON-RPC response body.
     */
    fun handle(requestBody: String): String {
        val method = METHOD_REGEX.find(requestBody)?.groupValues?.get(1)
        val id = ID_REGEX.find(requestBody)?.groupValues?.get(1)?.toIntOrNull()
        return when (method) {
            "initialize" -> buildInitializeResponse(id)
            METHOD_TRIGGER_ERROR -> throw RuntimeException(INTERNAL_ERROR_MESSAGE)
            else -> buildErrorResponse(id, -32601, "Method not found: $method")
        }
    }

    internal const val INTERNAL_ERROR_MESSAGE = "triggered internal error"

    private fun buildInitializeResponse(id: Int?): String {
        val resultBody = """{"capabilities":{}}"""
        return """{"jsonrpc":"2.0","id":${id ?: "null"},"result":$resultBody}"""
    }

    internal fun buildErrorResponse(id: Int?, code: Int, message: String): String {
        val escaped = message.replace("\\", "\\\\").replace("\"", "\\\"")
        return """{"jsonrpc":"2.0","id":${id ?: "null"},"error":{"code":$code,"message":"$escaped"}}"""
    }

    /** Builds a JSON-RPC error response for internal failures; extracts request id from [requestBody] if possible. */
    fun buildInternalErrorResponse(requestBody: String, message: String): String {
        val id = ID_REGEX.find(requestBody)?.groupValues?.get(1)?.toIntOrNull()
        return buildErrorResponse(id, -32603, message)
    }
}

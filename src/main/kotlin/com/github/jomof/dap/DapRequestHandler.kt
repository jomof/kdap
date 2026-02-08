package com.github.jomof.dap

/**
 * Handles DAP request bodies (envelope: type, seq, command, arguments) and returns
 * DAP response bodies (type, request_seq, command, success, body/message). Stateless.
 */
object DapRequestHandler {
    private val COMMAND_REGEX = """"command"\s*:\s*"([^"]+)"""".toRegex()
    private val SEQ_REGEX = """"seq"\s*:\s*(\d+)""".toRegex()

    /**
     * Test-only: command that throws so the server's catch block can be exercised.
     */
    const val METHOD_TRIGGER_ERROR = "_triggerError"

    internal const val INTERNAL_ERROR_MESSAGE = "triggered internal error"

    /**
     * Parses [requestBody] (DAP request), dispatches by command, returns DAP response body.
     */
    fun handle(requestBody: String): String {
        val command = COMMAND_REGEX.find(requestBody)?.groupValues?.get(1)
        val requestSeq = SEQ_REGEX.find(requestBody)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        return when (command) {
            "initialize" -> buildDapResponse(requestSeq, "initialize", body = """{"capabilities":{}}""")
            METHOD_TRIGGER_ERROR -> throw RuntimeException(INTERNAL_ERROR_MESSAGE)
            else -> buildDapErrorResponse(requestSeq, command ?: "unknown", "Method not found: $command")
        }
    }

    private fun buildDapResponse(requestSeq: Int, command: String, body: String): String {
        return """{"type":"response","request_seq":$requestSeq,"seq":0,"command":"$command","success":true,"body":$body}"""
    }

    private fun buildDapErrorResponse(requestSeq: Int, command: String, message: String): String {
        val escaped = message.replace("\\", "\\\\").replace("\"", "\\\"")
        return """{"type":"response","request_seq":$requestSeq,"seq":0,"command":"$command","success":false,"message":"$escaped"}"""
    }

    /** DAP-format error response for internal failures; extracts request_seq from [requestBody]. */
    fun buildInternalErrorResponse(requestBody: String, message: String): String {
        val requestSeq = SEQ_REGEX.find(requestBody)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        val command = COMMAND_REGEX.find(requestBody)?.groupValues?.get(1) ?: "unknown"
        return buildDapErrorResponse(requestSeq, command, message)
    }
}

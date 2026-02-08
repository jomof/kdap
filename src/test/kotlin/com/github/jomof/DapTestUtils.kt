package com.github.jomof

import com.github.jomof.dap.DapRequestHandler
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets

/** Shared DAP message helpers for tests. */
object DapTestUtils {
    private const val CONTENT_LENGTH_PREFIX = "Content-Length: "

    /** DAP initialize request (same format for our server and lldb-dap). */
    fun sendInitializeRequest(output: OutputStream) {
        sendRequest(output, """{"type":"request","seq":1,"command":"initialize","arguments":{"adapterID":"lldb","pathFormat":"path"}}""")
    }

    /** Sends a request that causes our server to hit its catch block (internal error response). */
    fun sendTriggerErrorRequest(output: OutputStream) {
        sendRequest(output, """{"type":"request","seq":42,"command":"${DapRequestHandler.METHOD_TRIGGER_ERROR}","arguments":{}}""")
    }

    /** Unknown command (lldb-dap returns success: false; our server returns method not found). */
    fun sendUnknownCommandRequest(output: OutputStream) {
        sendRequest(output, """{"type":"request","seq":42,"command":"UnknownCommand","arguments":{}}""")
    }

    private fun sendRequest(output: OutputStream, requestBody: String) {
        val bytes = requestBody.toByteArray(StandardCharsets.UTF_8)
        val header = "$CONTENT_LENGTH_PREFIX${bytes.size}\r\n\r\n"
        output.write(header.toByteArray(StandardCharsets.UTF_8))
        output.write(bytes)
        output.flush()
    }

    fun readResponseBody(input: InputStream): String {
        val reader = input.bufferedReader(StandardCharsets.UTF_8)
        val firstLine = reader.readLine() ?: error("No response header")
        require(firstLine.startsWith(CONTENT_LENGTH_PREFIX)) { "Expected Content-Length header: $firstLine" }
        val contentLength = firstLine.substring(CONTENT_LENGTH_PREFIX.length).trim().toInt()
        reader.readLine() // blank line
        val buf = CharArray(contentLength)
        var read = 0
        while (read < contentLength) {
            val n = reader.read(buf, read, contentLength - read)
            require(n > 0) { "EOF before full response" }
            read += n
        }
        return String(buf)
    }

    fun sendInitializeAndReadResponse(input: InputStream, output: OutputStream): String {
        sendInitializeRequest(output)
        return readResponseBody(input)
    }

    /** DAP success response: success true, command initialize, body with capabilities. */
    fun assertValidInitializeResponse(responseBody: String) {
        org.junit.jupiter.api.Assertions.assertTrue(
            responseBody.contains("\"success\":true"),
            "Response should be success: $responseBody"
        )
        org.junit.jupiter.api.Assertions.assertTrue(
            responseBody.contains("\"command\":\"initialize\"") && responseBody.contains("\"body\""),
            "Response should be initialize response with body: $responseBody"
        )
    }

    /** DAP error response: success false, message (internal error or method not found). */
    fun assertInternalErrorResponse(responseBody: String, expectedMessage: String = DapRequestHandler.INTERNAL_ERROR_MESSAGE) {
        org.junit.jupiter.api.Assertions.assertTrue(
            responseBody.contains("\"success\":false"),
            "Response should be error: $responseBody"
        )
        org.junit.jupiter.api.Assertions.assertTrue(
            responseBody.contains(expectedMessage),
            "Response should contain message '$expectedMessage': $responseBody"
        )
    }
}

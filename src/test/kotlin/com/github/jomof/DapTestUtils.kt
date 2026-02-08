package com.github.jomof

import com.github.jomof.dap.DapRequestHandler
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets

/** Shared DAP message helpers for tests. */
object DapTestUtils {
    private const val CONTENT_LENGTH_PREFIX = "Content-Length: "

    fun sendInitializeRequest(output: OutputStream) {
        sendRequest(output, """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{}}""")
    }

    /** Sends a request that causes the server to hit its catch block (internal error response). */
    fun sendTriggerErrorRequest(output: OutputStream) {
        sendRequest(output, """{"jsonrpc":"2.0","id":42,"method":"${DapRequestHandler.METHOD_TRIGGER_ERROR}","params":{}}""")
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

    fun assertValidInitializeResponse(responseBody: String) {
        org.junit.jupiter.api.Assertions.assertTrue(
            responseBody.contains("\"result\""),
            "Response should contain result: $responseBody"
        )
        org.junit.jupiter.api.Assertions.assertFalse(
            responseBody.contains("\"error\""),
            "Response should not be error: $responseBody"
        )
        org.junit.jupiter.api.Assertions.assertTrue(
            responseBody.contains("capabilities"),
            "Response should contain capabilities: $responseBody"
        )
    }

    /** Asserts the response is a JSON-RPC internal error (-32603) with the expected message. */
    fun assertInternalErrorResponse(responseBody: String, expectedMessage: String = DapRequestHandler.INTERNAL_ERROR_MESSAGE) {
        org.junit.jupiter.api.Assertions.assertTrue(
            responseBody.contains("\"error\""),
            "Response should contain error: $responseBody"
        )
        org.junit.jupiter.api.Assertions.assertTrue(
            responseBody.contains("-32603"),
            "Response should contain internal error code -32603: $responseBody"
        )
        org.junit.jupiter.api.Assertions.assertTrue(
            responseBody.contains(expectedMessage),
            "Response should contain message '$expectedMessage': $responseBody"
        )
    }
}

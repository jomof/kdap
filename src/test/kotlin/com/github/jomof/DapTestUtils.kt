package com.github.jomof

import com.github.jomof.dap.DapRequestHandler
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
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

    /**
     * Parses DAP initialize response and returns the capabilities object.
     * Standard DAP: body has a nested "capabilities" object. Some servers send body as the capabilities directly.
     */
    fun parseInitializeCapabilities(responseBody: String): JSONObject {
        val response = JSONObject(responseBody)
        val body = response.optJSONObject("body") ?: throw org.json.JSONException("Response has no 'body'")
        return if (body.has("capabilities")) body.getJSONObject("capabilities") else body
    }

    /**
     * Compares actual capabilities (from server response) to the expected baseline (JSON string).
     * Returns null if they match; otherwise returns a human-readable diff (missing keys, wrong values).
     * Baseline is the minimum we converge to: every key in expected must exist in actual with the same value.
     * Actual may have extra keys (e.g. CodeLLDB's exceptionBreakpointFilters); those are not reported as diffs.
     */
    fun compareCapabilitiesToBaseline(actual: JSONObject, expectedBaselineJson: String): String? {
        val expected = JSONObject(expectedBaselineJson)
        val diffs = mutableListOf<String>()
        for (key in expected.keySet()) {
            if (!actual.has(key)) {
                diffs.add("missing: $key (expected: ${expected.get(key)})")
            } else {
                val a = actual.get(key)
                val e = expected.get(key)
                if (!jsonValueEquals(a, e)) {
                    diffs.add("$key: expected $e, actual $a")
                }
            }
        }
        return if (diffs.isEmpty()) null else diffs.joinToString("\n")
    }

    private fun jsonValueEquals(a: Any?, e: Any?): Boolean {
        if (a == null && e == null) return true
        if (a == null || e == null) return false
        if (a is Boolean && e is Boolean) return a == e
        if (a is Number && e is Number) return a.toString() == e.toString()
        if (a is String && e is String) return a == e
        if (a is JSONObject && e is JSONObject) {
            if (a.keySet() != e.keySet()) return false
            return a.keySet().all { jsonValueEquals(a.opt(it), e.opt(it)) }
        }
        if (a is JSONArray && e is JSONArray) {
            if (a.length() != e.length()) return false
            return (0 until a.length()).all { jsonValueEquals(a.opt(it), e.opt(it)) }
        }
        return a == e
    }

    /**
     * Strict bidirectional comparison: every key in [expectedBaselineJson] must exist
     * in [actual] with the same value, AND every key in [actual] must appear in the
     * baseline (unexpected keys are reported). Use this when the baseline should fully
     * document the response shape — unlike [compareCapabilitiesToBaseline] which allows
     * extra keys in the actual.
     *
     * Keys in [excludeKeys] are skipped during comparison but may still appear in the
     * baseline for documentation purposes (e.g. a dynamic `result` field whose value
     * changes per release but whose presence is part of the documented shape).
     *
     * Returns null if they match; otherwise returns a human-readable diff.
     */
    fun compareJsonStrict(
        actual: JSONObject,
        expectedBaselineJson: String,
        excludeKeys: Set<String> = emptySet()
    ): String? {
        val expected = JSONObject(expectedBaselineJson)
        val diffs = mutableListOf<String>()
        for (key in expected.keySet()) {
            if (key in excludeKeys) continue
            if (!actual.has(key)) {
                diffs.add("missing: $key (expected: ${expected.get(key)})")
            } else {
                val a = actual.get(key)
                val e = expected.get(key)
                if (!jsonValueEquals(a, e)) {
                    diffs.add("$key: expected $e, actual $a")
                }
            }
        }
        for (key in actual.keySet()) {
            if (key in excludeKeys) continue
            if (!expected.has(key)) {
                diffs.add("unexpected: $key = ${actual.get(key)}")
            }
        }
        return if (diffs.isEmpty()) null else diffs.joinToString("\n")
    }

    /**
     * Sends a DAP evaluate request with the given [context].
     *
     * Common contexts:
     * - `"repl"` — standard DAP; lldb-dap runs LLDB commands in this context.
     * - `"_command"` — CodeLLDB extension; runs an LLDB command and returns output
     *   in the response body (unlike `"repl"` which returns empty for CodeLLDB).
     */
    fun sendEvaluateRequest(output: OutputStream, seq: Int, expression: String, context: String) {
        val json = JSONObject().apply {
            put("type", "request")
            put("seq", seq)
            put("command", "evaluate")
            put("arguments", JSONObject().apply {
                put("expression", expression)
                put("context", context)
            })
        }
        sendRequest(output, json.toString())
    }

    /**
     * Convenience: sends evaluate with `_command` context (CodeLLDB-specific).
     * @see sendEvaluateRequest
     */
    fun sendEvaluateCommandRequest(output: OutputStream, seq: Int, expression: String) {
        sendEvaluateRequest(output, seq, expression, "_command")
    }

    /**
     * Reads a single DAP-framed message directly from the stream using byte-level
     * reads (no BufferedReader), so multiple sequential calls on the same InputStream
     * are safe. Use this instead of [readResponseBody] when you need to read more
     * than one message from a connection.
     */
    fun readDapMessage(input: InputStream): String {
        val headerBuf = ByteArrayOutputStream()
        var consecutiveCrLf = 0
        var expectLf = false
        while (true) {
            val b = input.read()
            if (b == -1) error("EOF while reading DAP message header")
            headerBuf.write(b)
            if (expectLf) {
                expectLf = false
                if (b == '\n'.code) {
                    consecutiveCrLf++
                    if (consecutiveCrLf == 2) break
                } else {
                    consecutiveCrLf = 0
                }
            } else if (b == '\r'.code) {
                expectLf = true
            } else {
                consecutiveCrLf = 0
            }
        }
        val header = headerBuf.toString(StandardCharsets.UTF_8.name())
        val match = Regex("""Content-Length:\s*(\d+)""").find(header)
            ?: error("No Content-Length in DAP header: $header")
        val contentLength = match.groupValues[1].toInt()
        val body = ByteArray(contentLength)
        var read = 0
        while (read < contentLength) {
            val n = input.read(body, read, contentLength - read)
            if (n == -1) error("EOF reading DAP body (expected $contentLength, got $read)")
            read += n
        }
        return String(body, StandardCharsets.UTF_8)
    }

    /**
     * Reads DAP messages via [readDapMessage] until a response with the matching
     * [requestSeq] is found. Events and non-matching messages are skipped.
     */
    fun readResponseForRequestSeq(input: InputStream, requestSeq: Int, maxMessages: Int = 50): String {
        repeat(maxMessages) {
            val message = readDapMessage(input)
            val json = JSONObject(message)
            if (json.optString("type") == "response" && json.optInt("request_seq", -1) == requestSeq) {
                return message
            }
        }
        error("No response for request_seq=$requestSeq within $maxMessages messages")
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

package com.github.jomof

import com.github.jomof.dap.interception.TriggerErrorHandler
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets

/**
 * Which debuggee binary to launch. Both share the same testcase interface
 * (`stdio`, no-args, etc.) so expected DAP message sequences are reusable.
 */
enum class Debuggee {
    /** C++ debuggee (always available — built by cmake). */
    CPP,
    /** Rust debuggee (available when cargo was present at cmake time). */
    RUST;

    /**
     * Resolves the debuggee binary, or throws if not found.
     * For [RUST], throws with a hint about needing cargo installed.
     */
    fun resolve(): File = when (this) {
        CPP  -> DapTestUtils.resolveDebuggeeBinary()
        RUST -> DapTestUtils.resolveRustDebuggeeBinary()
    }

    /** True if the debuggee binary exists and is executable. */
    fun isAvailable(): Boolean = try { resolve(); true } catch (_: Exception) { false }
}

/** Shared DAP message helpers for tests. */
object DapTestUtils {

    /**
     * Resolves the C++ debuggee binary built by cmake.
     * Throws if not found — run: `cmake -B debuggee/build debuggee && cmake --build debuggee/build`
     */
    fun resolveDebuggeeBinary(): File {
        val cwd = File(System.getProperty("user.dir"))
        return listOf(
            File(cwd, "debuggee/build/debuggee"),
            File(cwd, "debuggee/build/debuggee.exe"),       // Windows
            File(cwd, "debuggee/build/Debug/debuggee.exe"), // MSVC multi-config
        ).firstOrNull { it.isFile && it.canExecute() }
            ?: error("debuggee binary not found — run: cmake -B debuggee/build debuggee && cmake --build debuggee/build")
    }

    /**
     * Resolves the Rust debuggee binary built by cmake (via cargo).
     * Throws if not found — requires cargo at cmake time.
     */
    fun resolveRustDebuggeeBinary(): File {
        val cwd = File(System.getProperty("user.dir"))
        return listOf(
            File(cwd, "debuggee/build/rust-debuggee"),
            File(cwd, "debuggee/build/rust-debuggee.exe"),       // Windows
            File(cwd, "debuggee/build/Debug/rust-debuggee.exe"), // MSVC multi-config
        ).firstOrNull { it.isFile && it.canExecute() }
            ?: error("rust-debuggee binary not found — install cargo (rustup) and rebuild: cmake -B debuggee/build debuggee && cmake --build debuggee/build")
    }

    private const val CONTENT_LENGTH_PREFIX = "Content-Length: "

    /**
     * DAP initialize request (same format for our server and lldb-dap).
     *
     * @param supportsRunInTerminal when true, advertises the
     *   `supportsRunInTerminalRequest` client capability so the adapter
     *   may send `runInTerminal` reverse requests for terminal modes.
     */
    fun sendInitializeRequest(output: OutputStream, supportsRunInTerminal: Boolean = false) {
        val args = JSONObject().apply {
            put("adapterID", "lldb")
            put("pathFormat", "path")
            if (supportsRunInTerminal) put("supportsRunInTerminalRequest", true)
        }
        val json = JSONObject().apply {
            put("type", "request")
            put("seq", 1)
            put("command", "initialize")
            put("arguments", args)
        }
        sendRequest(output, json.toString())
    }

    /** Sends a request that causes our server to hit its catch block (internal error response). */
    fun sendTriggerErrorRequest(output: OutputStream) {
        sendRequest(output, """{"type":"request","seq":42,"command":"${TriggerErrorHandler.METHOD_TRIGGER_ERROR}","arguments":{}}""")
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
        try {
            while (true) {
                val b = input.read()
                if (b == -1) {
                    val partial = headerBuf.toString(StandardCharsets.UTF_8.name()).take(200)
                    error("EOF while reading DAP message header (${headerBuf.size()} bytes read so far: \"$partial\")")
                }
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
        } catch (e: java.net.SocketException) {
            val partial = headerBuf.toString(StandardCharsets.UTF_8.name()).take(200)
            throw java.net.SocketException(
                "Connection lost while reading DAP header (${headerBuf.size()} header bytes read so far: \"$partial\"): ${e.message}"
            ).initCause(e)
        }
        val header = headerBuf.toString(StandardCharsets.UTF_8.name())
        val match = Regex("""Content-Length:\s*(\d+)""").find(header)
            ?: error("No Content-Length in DAP header: $header")
        val contentLength = match.groupValues[1].toInt()
        val body = ByteArray(contentLength)
        var read = 0
        try {
            while (read < contentLength) {
                val n = input.read(body, read, contentLength - read)
                if (n == -1) error("EOF reading DAP body (expected $contentLength bytes, got $read)")
                read += n
            }
        } catch (e: java.net.SocketException) {
            val partial = String(body, 0, read, StandardCharsets.UTF_8).take(200)
            throw java.net.SocketException(
                "Connection lost while reading DAP body ($read/$contentLength bytes read: \"$partial\"): ${e.message}"
            ).initCause(e)
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

    // ── Launch / configurationDone helpers ─────────────────────────────────

    /**
     * Sends a DAP `launch` request.
     *
     * [extraArgs] is merged into the `arguments` object so callers can add
     * server-specific fields (e.g. CodeLLDB needs `"terminal":"console"`).
     */
    fun sendLaunchRequest(output: OutputStream, seq: Int, program: String, extraArgs: Map<String, Any> = emptyMap()) {
        val args = JSONObject().apply {
            put("program", program)
            for ((k, v) in extraArgs) put(k, v)
        }
        val json = JSONObject().apply {
            put("type", "request")
            put("seq", seq)
            put("command", "launch")
            put("arguments", args)
        }
        sendRequest(output, json.toString())
    }

    /**
     * Sends a success response for a `runInTerminal` reverse request.
     */
    fun sendRunInTerminalResponse(output: OutputStream, requestSeq: Int) {
        val json = JSONObject().apply {
            put("type", "response")
            put("seq", 0)
            put("request_seq", requestSeq)
            put("command", "runInTerminal")
            put("success", true)
            put("body", JSONObject())
        }
        sendRequest(output, json.toString())
    }

    /**
     * Sends a DAP `attach` request.
     *
     * @param pid the OS process ID of the running debuggee to attach to.
     * @param program path to the debuggee binary (used by the adapter to
     *   create the debug target / locate symbols).
     * @param extraArgs additional arguments merged into the `arguments` object
     *   (e.g. `"stopOnEntry"` to `true`).
     */
    fun sendAttachRequest(
        output: OutputStream,
        seq: Int,
        pid: Long,
        program: String,
        extraArgs: Map<String, Any> = mapOf("stopOnEntry" to true),
    ) {
        val args = JSONObject().apply {
            put("pid", pid)
            put("program", program)
            for ((k, v) in extraArgs) put(k, v)
        }
        val json = JSONObject().apply {
            put("type", "request")
            put("seq", seq)
            put("command", "attach")
            put("arguments", args)
        }
        sendRequest(output, json.toString())
    }

    /**
     * Sends a DAP `disconnect` request.
     *
     * @param terminateDebuggee when false (default for attach), the debuggee
     *   continues running after detach. When true, the adapter kills the process.
     */
    fun sendDisconnectRequest(output: OutputStream, seq: Int, terminateDebuggee: Boolean = false) {
        val args = JSONObject().apply {
            put("terminateDebuggee", terminateDebuggee)
        }
        val json = JSONObject().apply {
            put("type", "request")
            put("seq", seq)
            put("command", "disconnect")
            put("arguments", args)
        }
        sendRequest(output, json.toString())
    }

    /** Sends a DAP `configurationDone` request. */
    fun sendConfigurationDoneRequest(output: OutputStream, seq: Int) {
        val json = JSONObject().apply {
            put("type", "request")
            put("seq", seq)
            put("command", "configurationDone")
            put("arguments", JSONObject())
        }
        sendRequest(output, json.toString())
    }

    /**
     * Reads DAP messages via [readDapMessage] until an **event** with the given
     * [eventType] is found. Non-matching messages (responses, other events) are
     * skipped. Throws if [maxMessages] messages are read without finding it.
     */
    fun readEventOfType(input: InputStream, eventType: String, maxMessages: Int = 50): String {
        val skipped = mutableListOf<String>()
        try {
            repeat(maxMessages) {
                val message = readDapMessage(input)
                val json = JSONObject(message)
                if (json.optString("type") == "event" && json.optString("event") == eventType) {
                    return message
                }
                // Summarize for diagnostics — include error details from failed responses
                val type = json.optString("type", "?")
                val detail = when (type) {
                    "event" -> "event:${json.optString("event")}"
                    "response" -> {
                        val cmd = json.optString("command")
                        val success = json.optBoolean("success")
                        val msg = json.optString("message", "")
                        val bodyError = json.optJSONObject("body")?.optString("error", "") ?: ""
                        buildString {
                            append("response:$cmd success=$success")
                            if (!success && msg.isNotEmpty()) append(" message=\"$msg\"")
                            if (!success && bodyError.isNotEmpty()) append(" body.error=\"$bodyError\"")
                        }
                    }
                    else -> type
                }
                skipped.add(detail)
            }
        } catch (e: Exception) {
            throw IllegalStateException(
                "Stream failed while waiting for '$eventType' event after ${skipped.size} messages. " +
                "Messages seen so far: $skipped", e
            )
        }
        error("No '$eventType' event within $maxMessages messages. Saw: $skipped")
    }

    /** DAP error response: success false, message (internal error or method not found). */
    fun assertInternalErrorResponse(responseBody: String, expectedMessage: String = TriggerErrorHandler.INTERNAL_ERROR_MESSAGE) {
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

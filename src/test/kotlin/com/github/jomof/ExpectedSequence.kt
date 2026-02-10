package com.github.jomof

import com.github.jomof.dap.messages.*
import org.json.JSONObject
import org.junit.jupiter.api.Assertions.fail

// ── Server tags ──────────────────────────────────────────────────────

/** Identifies which DAP server implementation produced a message. */
enum class Server { KDAP, CODELLDB }

/** Produced by both KDAP and CodeLLDB (the convergence target). */
val both: Set<Server> = setOf(Server.KDAP, Server.CODELLDB)

/** Produced only by KDAP (a difference to eliminate). */
val kdapOnly: Set<Server> = setOf(Server.KDAP)

/** Produced only by CodeLLDB (a difference to eliminate). */
val codelldbOnly: Set<Server> = setOf(Server.CODELLDB)

// ── Expected-message type ────────────────────────────────────────────

/**
 * An expected message in a DAP message sequence. Holds a concrete [DapMessage]
 * instance (event or response) whose type and fields are matched against
 * the actual stream.
 *
 * Comparison is done by serializing both expected and actual to JSON,
 * then checking structural equality (via [JSONObject.similar]) after
 * removing transport artifacts (`seq`, `request_seq`) and applying overrides:
 *
 * - [regex]: maps field names to patterns. The field is checked via regex
 *   instead of literal equality. The message's field value serves as
 *   documentation only. Works on fields inside `body` (events/responses)
 *   and `arguments` (requests).
 * - [skip]: field names to exclude from comparison entirely (e.g.,
 *   version-dependent or dynamic values). Applied at the top level AND
 *   inside `body`/`arguments`. Use sparingly.
 */
class Expected(
    val description: String,
    val servers: Set<Server>,
    val message: DapMessage,
    private val regex: Map<String, Regex> = emptyMap(),
    private val skip: Set<String> = emptySet(),
) {
    fun appliesTo(server: Server) = server in servers

    fun matchesOne(actual: DapMessage): Boolean {
        if (message::class != actual::class) return false
        val expectedJson = JSONObject(message.toJson())
        val actualJson = JSONObject(actual.toJson())
        // Remove transport artifacts — not semantically meaningful
        expectedJson.remove("seq")
        actualJson.remove("seq")
        expectedJson.remove("request_seq")
        actualJson.remove("request_seq")
        // Remove top-level skipped fields first
        for (field in skip) {
            expectedJson.remove(field)
            actualJson.remove(field)
        }
        // Apply regex and skip to the nested payload object.
        // Events/responses use "body"; requests use "arguments".
        val expectedPayload = expectedJson.optJSONObject("body")
            ?: expectedJson.optJSONObject("arguments")
        val actualPayload = actualJson.optJSONObject("body")
            ?: actualJson.optJSONObject("arguments")
        // Check regex-overridden fields separately, then remove from both.
        // For non-string values (e.g. arrays), the value is stringified
        // before matching so callers can regex-match structured fields.
        for ((field, pattern) in regex) {
            val raw = actualPayload?.opt(field) ?: return false
            val actualValue = if (raw is String) raw else raw.toString()
            if (!pattern.matches(actualValue)) return false
            expectedPayload?.remove(field)
            actualPayload.remove(field)
        }
        // Remove payload-level skipped fields
        for (field in skip) {
            expectedPayload?.remove(field)
            actualPayload?.remove(field)
        }
        // Structural comparison of everything that remains
        return expectedJson.similar(actualJson)
    }
}

// ── Factory functions ────────────────────────────────────────────────

/** An expected event in the message sequence. */
fun ExpectedEvent(
    description: String,
    servers: Set<Server>,
    event: DapEvent,
    regex: Map<String, Regex> = emptyMap(),
    skip: Set<String> = emptySet(),
) = Expected(description, servers, message = event, regex = regex, skip = skip)

/** An expected response in the message sequence. */
fun ExpectedResponse(
    description: String,
    servers: Set<Server>,
    command: String,
    success: Boolean = true,
    skip: Set<String> = emptySet(),
) = Expected(
    description, servers,
    message = DapResponse(seq = 0, requestSeq = 0, command = command, success = success),
    skip = skip,
)

/**
 * An expected reverse request (adapter -> client) in the message sequence.
 *
 * Like [ExpectedEvent], supports [regex] for fields with dynamic values
 * (matched against fields inside `arguments`) and [skip] for fields to
 * exclude from comparison entirely.
 */
fun ExpectedRequest(
    description: String,
    servers: Set<Server>,
    request: DapRequest,
    regex: Map<String, Regex> = emptyMap(),
    skip: Set<String> = emptySet(),
) = Expected(description, servers, message = request, regex = regex, skip = skip)

// ── Filtering ────────────────────────────────────────────────────────

/**
 * Filters the raw message stream to interesting messages:
 * - [DapResponse]s (anchored to their arrival position)
 * - [DapEvent]s, excluding [ModuleEvent]s (non-deterministic count)
 *   and blank [OutputEvent]s (trailing newline fragments from split writes)
 * - [RunInTerminalRequest]s (reverse requests from the adapter)
 *
 * Other [DapRequest]s are excluded (they originate from the client, not
 * the adapter). Every message type NOT excluded here must appear in the
 * expected sequence.
 *
 * Blank output events (containing only whitespace like `\r\n`) are excluded
 * because output coalescing is non-deterministic: a debuggee's `"hello\n"`
 * may arrive as one event or be split into `"hello"` + `"\r\n"` depending
 * on platform, PTY settings, and timing. Filtering these artifacts makes
 * sequence matching robust across environments.
 */
fun filterToInterestingMessages(messages: List<DapMessage>): List<DapMessage> =
    messages.filter {
        when {
            it is OutputEvent && it.output.isBlank() -> false
            it is DapResponse -> true
            it is DapEvent && it !is ModuleEvent -> true
            it is RunInTerminalRequest -> true
            else -> false
        }
    }

// ── Full-match assertion ─────────────────────────────────────────────

/**
 * Asserts that [actual] matches [expected] exactly — same count, same
 * order, each message matching its corresponding expected entry.
 *
 * On failure, prints expected and actual sequences side by side with
 * the mismatch position highlighted and both JSON payloads shown.
 */
fun assertFullMatch(
    actual: List<DapMessage>,
    expected: List<Expected>,
    label: String,
) {
    val mismatchIndex = actual.indices.firstOrNull { i ->
        i >= expected.size || !expected[i].matchesOne(actual[i])
    } ?: if (actual.size < expected.size) actual.size else null

    if (mismatchIndex != null || actual.size != expected.size) {
        fail<Unit>(buildString {
            appendLine("[$label] Message sequence mismatch at position ${(mismatchIndex ?: actual.size) + 1}")
            appendLine()
            val maxLen = maxOf(actual.size, expected.size)
            appendLine("Expected (${expected.size} messages) vs Actual (${actual.size} messages):")
            for (i in 0 until maxLen) {
                val expDesc = expected.getOrNull(i)?.description ?: "(none)"
                val actDesc = actual.getOrNull(i)?.let { summarize(it) } ?: "(none)"
                val marker = if (i == mismatchIndex) "  ← MISMATCH" else ""
                appendLine("  ${i + 1}. expected: $expDesc")
                appendLine("     actual:   $actDesc$marker")
            }
            if (mismatchIndex != null && mismatchIndex < actual.size) {
                appendLine()
                appendLine("Actual message JSON at mismatch:")
                appendLine("  ${actual[mismatchIndex].toJson()}")
                if (mismatchIndex < expected.size) {
                    appendLine("Expected message JSON:")
                    appendLine("  ${expected[mismatchIndex].message.toJson()}")
                }
            }
        })
    }
}

// ── Diagnostics ─────────────────────────────────────────────────────

/** One-line summary of a [DapMessage] for diagnostic output. */
fun summarize(message: DapMessage): String = when (message) {
    is OutputEvent -> {
        val cat = message.category ?: "?"
        val text = message.output.take(80).replace("\n", "\\n")
        "event:output($cat) \"$text\""
    }
    is DapEvent -> "event:${message.event}"
    is DapResponse -> {
        val ok = message.success
        val msg = message.message
        if (!ok && !msg.isNullOrEmpty()) "response:${message.command} FAILED \"$msg\""
        else "response:${message.command} ok"
    }
    is RunInTerminalRequest -> "request:runInTerminal(kind=${message.kind})"
    is DapRequest -> "request:${message.command}"
}

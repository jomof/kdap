package com.github.jomof.dap.interception

import com.github.jomof.dap.messages.DapMessage
import com.github.jomof.dap.messages.OutputEvent

/**
 * Coalesces consecutive debuggee output events into a single event.
 *
 * lldb-dap sends each chunk of debuggee output (stdout/stderr) as a
 * separate `output` event. CodeLLDB combines consecutive chunks of the
 * same category into one event. This handler makes KDAP match CodeLLDB's
 * behavior by buffering `stdout` and `stderr` output events and flushing
 * them as a single concatenated event when a different message arrives.
 *
 * Only debuggee output categories (`stdout`, `stderr`) are coalesced.
 * Adapter-originated output (`console`) passes through unchanged — those
 * events are injected by other handlers and should remain distinct.
 *
 * ## Flush triggers
 *
 * The buffer is flushed when:
 * - A non-output message arrives (event, response, etc.)
 * - An output event with a non-coalesced category arrives (`console`)
 * - An output event with a **different** coalesced category arrives
 *   (e.g., buffered `stdout` then a `stderr` event)
 *
 * In practice, the DAP session always ends with `exited`/`terminated`
 * events, so the buffer is guaranteed to flush before the session closes.
 *
 * ## Thread safety
 *
 * [onBackendMessage] is called exclusively from the backend-reader
 * coroutine, so the mutable buffer requires no synchronization.
 */
class OutputCoalescingHandler : InterceptionHandler {

    private var pendingCategory: String? = null
    private val pendingOutput = StringBuilder()

    override fun onBackendMessage(message: DapMessage): List<DapMessage> {
        if (message is OutputEvent && message.category in COALESCE_CATEGORIES) {
            if (message.category == pendingCategory) {
                // Same category — append to buffer
                pendingOutput.append(message.output)
                return emptyList()
            }
            // Different coalesced category — flush previous, start new buffer
            val flushed = flush()
            pendingCategory = message.category
            pendingOutput.append(message.output)
            return flushed
        }
        // Non-coalesced message — flush buffer, then forward
        val flushed = flush()
        return if (flushed.isEmpty()) listOf(message) else flushed + message
    }

    private fun flush(): List<DapMessage> {
        val cat = pendingCategory ?: return emptyList()
        val output = pendingOutput.toString()
        pendingCategory = null
        pendingOutput.clear()
        return listOf(OutputEvent(seq = 0, category = cat, output = output))
    }

    companion object {
        /** Output categories that are coalesced (debuggee output). */
        private val COALESCE_CATEGORIES = setOf("stdout", "stderr")
    }
}

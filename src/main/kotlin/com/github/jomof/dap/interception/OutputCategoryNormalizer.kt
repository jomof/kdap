package com.github.jomof.dap.interception

import com.github.jomof.dap.debugsession.DebugSession
import com.github.jomof.dap.messages.DapMessage
import com.github.jomof.dap.messages.OutputEvent

/**
 * Normalizes debuggee output category from `console` to `stdout`.
 *
 * On macOS/Linux, lldb-dap sends debuggee output as `category: "stdout"`
 * and reserves `category: "console"` for adapter-originated messages.
 * On Windows, lldb-dap sends *all* output — including debuggee
 * stdout/stderr — as `category: "console"`.
 *
 * This handler reclassifies `console` output events as `stdout` once
 * [DebugSession.processRunning] is `true`, **except** for exit-status
 * messages (which [ExitStatusHandler] needs to stay as `console`).
 *
 * On macOS/Linux this is effectively a no-op — debuggee output already
 * arrives as `stdout`, so the reclassification never triggers.
 *
 * **Must be registered before [ExitStatusHandler]** so that exit messages
 * retain their `console` category for reformatting.
 */
class OutputCategoryNormalizer(
    private val session: DebugSession,
) : InterceptionHandler {

    override fun onBackendMessage(message: DapMessage): List<DapMessage> {
        if (session.processRunning
            && message is OutputEvent
            && message.category == "console"
            && !ExitStatusHandler.EXIT_PATTERN.containsMatchIn(message.output)
        ) {
            return listOf(OutputEvent(seq = message.seq, category = "stdout", output = message.output))
        }
        return listOf(message)
    }
}

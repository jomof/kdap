package com.github.jomof.dap.interception

import com.github.jomof.dap.messages.ContinuedEvent
import com.github.jomof.dap.messages.DapMessage
import com.github.jomof.dap.messages.OutputEvent

/**
 * Normalizes debuggee output category from `console` to `stdout`.
 *
 * On macOS/Linux, lldb-dap sends debuggee output as `category: "stdout"`
 * and only uses `category: "console"` for adapter-originated messages
 * (like the process exit message). On Windows, lldb-dap sends *all*
 * output — including debuggee stdout/stderr — as `category: "console"`.
 *
 * This handler uses a state machine to distinguish debuggee output from
 * adapter output:
 *
 * - **Before [ContinuedEvent]**: all messages pass through unchanged.
 *   This preserves KDAP-injected console output ("Console is in 'commands'
 *   mode", "Launching:", "Launched process") which is produced by earlier
 *   handlers in the chain and arrives before the process starts running.
 *
 * - **After [ContinuedEvent]**: `console` [OutputEvent]s that do **not**
 *   match [ExitStatusHandler.EXIT_PATTERN] are reclassified as `stdout`.
 *   Exit messages stay as `console` so [ExitStatusHandler] can reformat them.
 *
 * On macOS/Linux this handler is effectively a no-op — debuggee output
 * already arrives as `stdout`, so the reclassification never triggers.
 *
 * **Must be registered before [ExitStatusHandler]** so exit messages
 * retain their `console` category for reformatting.
 *
 * ## Thread safety
 *
 * [onBackendMessage] is called exclusively from the backend-reader
 * coroutine, so the mutable [running] flag requires no synchronization.
 */
class OutputCategoryHandler : InterceptionHandler {

    private var running = false

    override fun onBackendMessage(message: DapMessage): List<DapMessage> {
        if (message is ContinuedEvent) {
            running = true
            return listOf(message)
        }
        if (running && message is OutputEvent && message.category == "console") {
            // Leave exit messages as console for ExitStatusHandler to reformat
            if (ExitStatusHandler.EXIT_PATTERN.containsMatchIn(message.output)) {
                return listOf(message)
            }
            // Reclassify debuggee console output as stdout
            return listOf(OutputEvent(seq = message.seq, category = "stdout", output = message.output))
        }
        return listOf(message)
    }
}

package com.github.jomof.dap.interception

import com.github.jomof.dap.messages.DapMessage
import com.github.jomof.dap.messages.OutputEvent

/**
 * Reformats lldb-dap's process exit output to match CodeLLDB's format.
 *
 * lldb-dap sends:
 * ```
 * Process 12345 exited with status = 255 (0x000000ff)
 * ```
 *
 * CodeLLDB sends:
 * ```
 * Process exited with code 255.
 * ```
 *
 * This handler matches the lldb-dap pattern, extracts the exit code,
 * and replaces the output text with the CodeLLDB format.
 */
class ExitStatusHandler : InterceptionHandler {

    override fun onBackendMessage(message: DapMessage): List<DapMessage> {
        if (message is OutputEvent && message.category == "console") {
            val match = EXIT_PATTERN.find(message.output)
            if (match != null) {
                val exitCode = match.groupValues[1]
                return listOf(OutputEvent.console("Process exited with code $exitCode.\n"))
            }
        }
        return listOf(message)
    }

    companion object {
        /**
         * Matches lldb-dap's exit status output:
         * `Process <pid> exited with status = <code> (0x...)`
         *
         * Group 1 captures the decimal exit code.
         */
        val EXIT_PATTERN = Regex("""^Process \d+ exited with status = (\d+)""")
    }
}

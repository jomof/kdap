package com.github.jomof.dap.interception

import com.github.jomof.dap.messages.DapMessage
import com.github.jomof.dap.messages.InitializedEvent
import com.github.jomof.dap.messages.OutputEvent

/**
 * Injects a console mode announcement before the `initialized` event.
 *
 * CodeLLDB sends an output event announcing the console mode (e.g.,
 * `"Console is in 'commands' mode, prefix expressions with '?'."`)
 * before the `initialized` event. `lldb-dap` does not. This handler
 * injects the same event so clients accustomed to CodeLLDB see the
 * expected output.
 *
 * CodeLLDB source: `debug_session/launch.rs` -> `print_console_mode`
 */
class ConsoleModeHandler : InterceptionHandler {

    @Volatile
    private var consoleModeInjected = false

    override fun onBackendMessage(message: DapMessage): List<DapMessage> {
        if (!consoleModeInjected && message is InitializedEvent) {
            consoleModeInjected = true
            return listOf(OutputEvent.console(CONSOLE_MODE_MESSAGE), message)
        }
        return listOf(message)
    }

    companion object {
        /**
         * Console mode announcement injected before the `initialized` event.
         * Matches CodeLLDB's `print_console_mode` output for the default
         * "commands" console mode.
         */
        const val CONSOLE_MODE_MESSAGE =
            "Console is in 'commands' mode, prefix expressions with '?'.\n"
    }
}

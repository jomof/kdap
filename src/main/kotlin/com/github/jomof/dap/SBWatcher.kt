package com.github.jomof.dap

/**
 * Observer for SB API calls made by KDAP's debug session.
 *
 * When registered, every call to an [com.github.jomof.dap.sb] interface
 * method is reported to the watcher with the interface name, method name,
 * formatted arguments, and the result (or error).
 *
 * ## Thread safety
 *
 * Implementations must be thread-safe. Calls may arrive concurrently
 * from different coroutines.
 *
 * ## Usage
 *
 * Register via `--sb-log <file>` CLI flag, which installs a
 * [FileSBWatcher] that writes each call as a single line to the file.
 *
 * @see com.github.jomof.dap.sb.watched
 * @see FileSBWatcher
 */
interface SBWatcher {
    /**
     * Called after each SB API method completes (or fails).
     *
     * @param interfaceName the SB interface (e.g., `"SBDebugger"`, `"SBTarget"`)
     * @param methodName    the method called (e.g., `"createTarget"`, `"launch"`)
     * @param args          formatted argument string (e.g., `"program=/path/to/hello"`)
     * @param result        the return value's string representation, or
     *                      `"ERROR: <message>"` if the call threw an exception
     */
    fun onCall(interfaceName: String, methodName: String, args: String, result: String)

    /**
     * Called when a DAP-level message is observed.
     *
     * @param direction `"→backend"` or `"←backend"` indicating message direction
     * @param message   the raw DAP message JSON or summary
     */
    fun onMessage(direction: String, message: String)
}

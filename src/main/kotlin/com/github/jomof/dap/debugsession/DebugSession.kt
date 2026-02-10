package com.github.jomof.dap.debugsession

import com.github.jomof.dap.types.Either

/**
 * Mutable session state for a single debug session, mirroring the fields
 * that CodeLLDB's `DebugSession` struct accumulates across
 * initialize / launch / attach / disconnect / terminate.
 *
 * The launch-related extension functions in [Launch.kt] read and write
 * these fields. Each [com.github.jomof.dap.interception] handler
 * shares the same [DebugSession] instance so state flows naturally
 * across request boundaries.
 *
 * ## Thread safety
 *
 * Fields are `@Volatile` for safe publication between the client-reader
 * coroutine (which calls [onInitialize]) and async handler coroutines.
 */
class DebugSession {

    /** Whether the client advertised `supportsRunInTerminalRequest`. */
    @Volatile
    var clientSupportsRunInTerminal: Boolean = false

    /**
     * Set to `true` once the debuggee process is running (after
     * `target.launch()` or `target.attach()`).
     */
    @Volatile
    var processRunning: Boolean = false

    /** Whether to kill (true) or detach (false) on disconnect. */
    @Volatile
    var terminateOnDisconnect: Boolean = false

    /** Commands to run before terminating the debuggee. */
    @Volatile
    var preTerminateCommands: List<String>? = null

    /** Commands to run after disconnecting. */
    @Volatile
    var exitCommands: List<String>? = null

    /** Graceful shutdown config: signal name (First) or commands (Second). */
    @Volatile
    var gracefulShutdown: Either<String, List<String>>? = null
}

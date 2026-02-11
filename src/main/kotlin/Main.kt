package com.github.jomof

import com.github.jomof.dap.*
import java.io.File

/**
 * Entry point for the KDAP DAP server. Always runs as a decorator in front
 * of `lldb-dap`, proxying DAP messages bidirectionally.
 *
 * Supports stdio (default), TCP listen (--port N), and TCP connect (--connect N),
 * matching CodeLLDB's command line. `--lldb-dap <path>` is required to specify
 * the `lldb-dap` executable.
 *
 * When `--port 0` is used, the OS assigns an available port. The actual port
 * is printed to stderr as `Listening on port <N>` so callers can discover it
 * without a TOCTOU race.
 */
fun main(args: Array<String>) = mainImpl(args)

/**
 * Implementation extracted so tests (e.g. IN_PROCESS) can call it by name
 * without ambiguity when the test file also defines a top-level `main`.
 */
fun mainImpl(args: Array<String>) {
    val config = Cli.parse(args)
    if (config == null) {
        System.err.println("Usage: [--port N] | [--connect N] [--lldb-dap PATH] [--sb-log PATH]")
        System.err.println("  --port N         Listen on port N (use 0 for OS-assigned)")
        System.err.println("  --connect N      Connect to 127.0.0.1:N")
        System.err.println("  --lldb-dap PATH  Path to lldb-dap executable (required)")
        System.err.println("  --sb-log PATH    Write SB API call trace to file")
        System.err.println("  (no args)        Use stdio")
        System.exit(1)
        return
    }

    // When using TCP listen, report the actual bound port to stderr.
    // This is essential for --port 0 (OS-assigned) but also useful for
    // confirming the port with an explicit value.
    val transport = when (val t = config.transport) {
        is Transport.TcpListen -> t.copy(onBound = { port ->
            System.err.println("Listening on port $port")
        })
        else -> t
    }

    val lldbDapPath = config.lldbDapPath?.let { File(it) }
        ?: run {
            System.err.println("--lldb-dap <path> is required")
            System.exit(1)
            return
        }

    val sbWatcher = config.sbLogPath?.let { FileSBWatcher(File(it)) }

    val lldbDap = LldbDapProcess.start(lldbDapPath)
    try {
        DapServer.runDecorator(
            transport,
            lldbDap.inputStream,
            lldbDap.outputStream,
            KdapInterceptor(KdapInterceptor.defaultHandlers(sbWatcher), sbWatcher),
        )
    } finally {
        lldbDap.close()
    }
}

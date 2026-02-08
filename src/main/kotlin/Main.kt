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
 */
fun main(args: Array<String>) {
    val config = Cli.parse(args)
    if (config == null) {
        System.err.println("Usage: [--port N] | [--connect N] [--lldb-dap PATH]")
        System.err.println("  --port N         Listen on port N, accept one connection")
        System.err.println("  --connect N      Connect to 127.0.0.1:N")
        System.err.println("  --lldb-dap PATH  Path to lldb-dap executable (required)")
        System.err.println("  (no args)        Use stdio")
        System.exit(1)
        return
    }

    val lldbDapPath = config.lldbDapPath?.let { File(it) }
        ?: run {
            System.err.println("--lldb-dap <path> is required")
            System.exit(1)
            return
        }

    val lldbDap = LldbDapProcess.start(lldbDapPath)
    try {
        DapServer.runDecorator(
            config.transport,
            lldbDap.inputStream,
            lldbDap.outputStream,
            KdapInterceptor,
        )
    } finally {
        lldbDap.close()
    }
}

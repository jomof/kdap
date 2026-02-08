package com.github.jomof

import com.github.jomof.dap.Cli
import com.github.jomof.dap.DapServer

/**
 * Entry point for the DAP server. Supports stdio (default), TCP listen (--port N),
 * and TCP connect (--connect N), matching CodeLLDB's command line.
 */
fun main(args: Array<String>) {
    val config = Cli.parse(args)
    if (config == null) {
        System.err.println("Usage: [--port N] | [--connect N]")
        System.err.println("  --port N    Listen on port N, accept one connection")
        System.err.println("  --connect N Connect to 127.0.0.1:N")
        System.err.println("  (no args)   Use stdio")
        System.exit(1)
    } else {
        DapServer.run(config.transport)
    }
}

package com.github.jomof.dap

/**
 * Command-line parsing for the DAP server. Matches CodeLLDB-style options
 * plus KDAP-specific flags:
 * - no args: stdio
 * - --port N: listen on port N, accept one connection
 * - --connect N: connect to localhost:N
 * - --lldb-dap PATH: explicit path to the lldb-dap executable
 * - --sb-log PATH: write SB API call trace to file
 */
object Cli {
    private const val PORT = "--port"
    private const val CONNECT = "--connect"
    private const val LLDB_DAP = "--lldb-dap"
    private const val SB_LOG = "--sb-log"
    private const val DEFAULT_HOST = "127.0.0.1"

    data class Config(
        val transport: Transport,
        /** Explicit path to the `lldb-dap` executable, or null for auto-discovery. */
        val lldbDapPath: String? = null,
        /** Path to write SB API call trace, or null to disable. */
        val sbLogPath: String? = null,
    )

    /**
     * Parses [args] and returns [Config], or null if args are invalid (e.g. both --port and --connect).
     */
    fun parse(args: Array<String>): Config? {
        var port: Int? = null
        var connect: Int? = null
        var lldbDapPath: String? = null
        var sbLogPath: String? = null
        var i = 0
        while (i < args.size) {
            when (args[i]) {
                PORT -> {
                    if (i + 1 >= args.size) return null
                    port = args[i + 1].toIntOrNull()
                    if (port == null || port !in 0..65535) return null
                    i += 2
                }
                CONNECT -> {
                    if (i + 1 >= args.size) return null
                    connect = args[i + 1].toIntOrNull()
                    if (connect == null || connect !in 1..65535) return null
                    i += 2
                }
                LLDB_DAP -> {
                    if (i + 1 >= args.size) return null
                    lldbDapPath = args[i + 1]
                    i += 2
                }
                SB_LOG -> {
                    if (i + 1 >= args.size) return null
                    sbLogPath = args[i + 1]
                    i += 2
                }
                else -> i++
            }
        }
        return when {
            port != null && connect != null -> null
            connect != null -> Config(Transport.TcpConnect(DEFAULT_HOST, connect), lldbDapPath, sbLogPath)
            port != null -> Config(Transport.TcpListen(port), lldbDapPath, sbLogPath)
            else -> Config(Transport.Stdio, lldbDapPath, sbLogPath)
        }
    }
}

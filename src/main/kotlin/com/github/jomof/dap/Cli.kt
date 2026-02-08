package com.github.jomof.dap

/**
 * Command-line parsing for the DAP server. Matches CodeLLDB-style options:
 * - no args: stdio
 * - --port N: listen on port N, accept one connection
 * - --connect N: connect to localhost:N
 */
object Cli {
    private const val PORT = "--port"
    private const val CONNECT = "--connect"
    private const val DEFAULT_HOST = "127.0.0.1"

    data class Config(
        val transport: Transport,
    )

    /**
     * Parses [args] and returns [Config], or null if args are invalid (e.g. both --port and --connect).
     */
    fun parse(args: Array<String>): Config? {
        var port: Int? = null
        var connect: Int? = null
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
                else -> i++
            }
        }
        return when {
            port != null && connect != null -> null
            connect != null -> Config(Transport.TcpConnect(DEFAULT_HOST, connect))
            port != null -> Config(Transport.TcpListen(port))
            else -> Config(Transport.Stdio)
        }
    }
}

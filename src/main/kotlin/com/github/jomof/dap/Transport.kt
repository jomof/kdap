package com.github.jomof.dap

import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket

/**
 * Transport for DAP: provides an [InputStream] and [OutputStream] over which
 * the server runs. Aligns with CodeLLDB's modes: stdio, listen (--port), connect (--connect).
 */
sealed class Transport {

    /**
     * Runs [block] with the transport's input and output streams. Resources are closed after [block].
     */
    abstract fun run(block: (InputStream, OutputStream) -> Unit)

    /** DAP over standard input/output (default when no --port or --connect). */
    data object Stdio : Transport() {
        override fun run(block: (InputStream, OutputStream) -> Unit) {
            block(System.`in`, System.out)
        }
    }

    /**
     * DAP over TCP: listen on [port], accept one connection, then use that socket.
     *
     * When [port] is `0`, the OS assigns an available port. The actual bound
     * port is reported via [onBound] before accepting connections, so callers
     * (or test harnesses) can discover it without a TOCTOU race.
     *
     * `SO_REUSEADDR` is set to handle TIME_WAIT overlap from previous runs.
     */
    data class TcpListen(
        val port: Int,
        /** Called with the actual bound port after the server socket is listening. */
        val onBound: (Int) -> Unit = {},
    ) : Transport() {
        override fun run(block: (InputStream, OutputStream) -> Unit) {
            val server = ServerSocket()
            server.reuseAddress = true
            server.bind(InetSocketAddress(InetAddress.getLoopbackAddress(), port), 1)
            onBound(server.localPort)
            server.use {
                val socket = server.accept()
                socket.use {
                    block(socket.getInputStream(), socket.getOutputStream())
                }
            }
        }
    }

    /** DAP over TCP: connect to [host]:[port], then use that socket. */
    data class TcpConnect(val host: String, val port: Int) : Transport() {
        override fun run(block: (InputStream, OutputStream) -> Unit) {
            Socket(host, port).use { socket ->
                block(socket.getInputStream(), socket.getOutputStream())
            }
        }
    }

}

package com.github.jomof.dap

import java.io.InputStream
import java.io.OutputStream
import java.net.BindException
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
     * Retries the bind up to [MAX_BIND_RETRIES] times with back-off to handle
     * transient port conflicts (e.g., a previous test's server still releasing
     * the port). `SO_REUSEADDR` is set to handle TIME_WAIT overlap.
     */
    data class TcpListen(val port: Int) : Transport() {
        override fun run(block: (InputStream, OutputStream) -> Unit) {
            val server = ServerSocket()
            server.reuseAddress = true
            var lastException: BindException? = null
            for (attempt in 1..MAX_BIND_RETRIES) {
                try {
                    server.bind(InetSocketAddress(InetAddress.getLoopbackAddress(), port), 1)
                    lastException = null
                    break
                } catch (e: BindException) {
                    lastException = e
                    if (attempt < MAX_BIND_RETRIES) {
                        Thread.sleep(BIND_RETRY_DELAY_MS * attempt)
                    }
                }
            }
            if (lastException != null) throw lastException
            server.use {
                val socket = server.accept()
                socket.use {
                    block(socket.getInputStream(), socket.getOutputStream())
                }
            }
        }

        companion object {
            private const val MAX_BIND_RETRIES = 5
            private const val BIND_RETRY_DELAY_MS = 200L
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

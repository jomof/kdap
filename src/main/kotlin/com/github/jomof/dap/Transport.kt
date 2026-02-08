package com.github.jomof.dap

import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
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

    /** DAP over TCP: listen on [port], accept one connection, then use that socket. */
    data class TcpListen(val port: Int) : Transport() {
        override fun run(block: (InputStream, OutputStream) -> Unit) {
            ServerSocket(port, 1, InetAddress.getLoopbackAddress()).use { server ->
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

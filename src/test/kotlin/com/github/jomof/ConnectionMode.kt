package com.github.jomof

import com.github.jomof.dap.DapServer
import java.io.InputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread

/**
 * Ways to connect to the DAP server process for tests. Use [connect] to get a
 * [ConnectionContext] (streams + cleanup); use [ConnectionContext.close] when done.
 */
enum class ConnectionMode {
    STDIO {
        override fun connect(): ConnectionContext {
            val process = DapProcessHarness.startProcess()
            return object : ConnectionContext {
                override val inputStream: InputStream = process.inputStream
                override val outputStream: OutputStream = process.outputStream
                override fun close() = DapProcessHarness.stopProcess(process)
            }
        }
    },
    TCP_LISTEN {
        override fun connect(): ConnectionContext {
            val freePort = ServerSocket(0).use { it.localPort }
            val process = DapProcessHarness.startProcess("--port", freePort.toString())
            val socket = DapProcessHarness.connectToPort(freePort).apply { soTimeout = 10_000 }
            return object : ConnectionContext {
                override val inputStream: InputStream = socket.getInputStream()
                override val outputStream: OutputStream = socket.getOutputStream()
                override fun close() {
                    socket.close()
                    DapProcessHarness.stopProcess(process)
                }
            }
        }
    },
    TCP_CONNECT {
        override fun connect(): ConnectionContext {
            val server = ServerSocket(0)
            val port = server.localPort
            val process = DapProcessHarness.startProcess("--connect", port.toString())
            val socket = server.accept().apply { soTimeout = 10_000 }
            return object : ConnectionContext {
                override val inputStream: InputStream = socket.getInputStream()
                override val outputStream: OutputStream = socket.getOutputStream()
                override fun close() {
                    socket.close()
                    DapProcessHarness.stopProcess(process)
                    server.close()
                }
            }
        }
    },
    IN_PROCESS {
        override fun connect(): ConnectionContext {
            val serverInput = java.io.PipedInputStream()
            val testToServer = java.io.PipedOutputStream(serverInput)
            val serverToClient = java.io.PipedOutputStream()
            val testFromServer = java.io.PipedInputStream(serverToClient)
            val serverReady = CountDownLatch(1)
            val serverThread = thread {
                serverReady.countDown()
                DapServer.run(serverInput, serverToClient)
            }
            serverReady.await()
            return object : ConnectionContext {
                override val inputStream: InputStream = testFromServer
                override val outputStream: OutputStream = testToServer
                override fun close() {
                    testToServer.close()
                    serverThread.join(2000)
                }
            }
        }
    };

    abstract fun connect(): ConnectionContext
}

/**
 * A connection to the DAP server: input/output streams and [close] for cleanup.
 */
interface ConnectionContext : AutoCloseable {
    val inputStream: InputStream
    val outputStream: OutputStream
}

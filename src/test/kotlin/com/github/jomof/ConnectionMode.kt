package com.github.jomof

import com.github.jomof.dap.DapServer
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread

/**
 * Which server the connection talks to. Use in tests to expect different results.
 */
enum class ServerKind {
    /** Our KDAP server (MainKt). */
    OUR_SERVER,
    /** lldb-dap from LLVM prebuilts. */
    LLDB_DAP
}

/**
 * Ways to connect to the DAP server process for tests. Use [connect] to get a
 * [ConnectionContext] (streams + cleanup); use [ConnectionContext.close] when done.
 * [serverKind] indicates whether this mode talks to our server or lldb-dap (for test branching).
 */
enum class ConnectionMode(val serverKind: ServerKind) {
    STDIO(ServerKind.OUR_SERVER) {
        override fun connect(): ConnectionContext {
            val process = DapProcessHarness.startProcess()
            return object : ConnectionContext {
                override val inputStream: InputStream = process.inputStream
                override val outputStream: OutputStream = process.outputStream
                override fun close() = DapProcessHarness.stopProcess(process)
            }
        }
    },
    TCP_LISTEN(ServerKind.OUR_SERVER) {
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
    TCP_CONNECT(ServerKind.OUR_SERVER) {
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
    IN_PROCESS(ServerKind.OUR_SERVER) {
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
    },
    /** Connects to lldb-dap via TCP (-p port). Stdout and stderr captured on failure (keep for CI diagnostics). */
    TCP_LLDB(ServerKind.LLDB_DAP) {
        override fun connect(): ConnectionContext {
            if (!LldbDapHarness.isAvailable())
                throw IllegalStateException("lldb-dap not available (run scripts/download-lldb.sh or set KDAP_LLDB_ROOT)")
            val (process, port) = LldbDapHarness.startLldbDapTcp()
            val stdoutBuf = StringBuilder()
            val stderrBuf = StringBuilder()
            thread(isDaemon = true) {
                process.inputStream.bufferedReader(Charsets.UTF_8).use { r ->
                    r.lineSequence().forEach { stdoutBuf.appendLine(it) }
                }
            }
            thread(isDaemon = true) {
                process.errorStream.bufferedReader(Charsets.UTF_8).use { r ->
                    r.lineSequence().forEach { stderrBuf.appendLine(it) }
                }
            }
            return try {
                val socket = DapProcessHarness.connectToPort(port, 30_000).apply { soTimeout = 15_000 }
                object : ConnectionContext {
                    override val inputStream: InputStream = socket.getInputStream()
                    override val outputStream: OutputStream = socket.getOutputStream()
                    override fun close() {
                        socket.close()
                        LldbDapHarness.stopProcess(process)
                    }
                }
            } catch (e: IllegalStateException) {
                LldbDapHarness.stopProcess(process)
                val exitInfo = if (!process.isAlive) " (lldb-dap exited with ${process.exitValue()})" else ""
                val out = stdoutBuf.toString().trim().takeLast(2000).ifEmpty { "(no stdout)" }
                val err = stderrBuf.toString().trim().takeLast(2000).ifEmpty { "(no stderr)" }
                throw IllegalStateException("${e.message}$exitInfo\nlldb-dap stdout:\n$out\nlldb-dap stderr:\n$err", e)
            }
        }
    };

    abstract fun connect(): ConnectionContext

    companion object {
        /** Modes that run our KDAP server (excludes lldb-dap). For parameterized tests that only target our server. */
        @JvmStatic
        fun ourServerModes(): List<ConnectionMode> = entries.filter { it.serverKind == ServerKind.OUR_SERVER }
    }
}

/**
 * A connection to the DAP server: input/output streams and [close] for cleanup.
 * [stderr] is non-empty only for STDIO_LLDB (lldb-dap's stderr capture for diagnostics).
 */
interface ConnectionContext : AutoCloseable {
    val inputStream: InputStream
    val outputStream: OutputStream
    val stderr: String get() = ""
}

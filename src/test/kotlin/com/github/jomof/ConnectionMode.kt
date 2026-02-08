package com.github.jomof

import com.github.jomof.dap.DapServer
import com.github.jomof.dap.LldbDapProcess
import java.io.InputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** Runs shell commands to diagnose why a port never became reachable; returns combined output. */
private fun runPortDiagnostics(port: Int, lldbDap: LldbDapProcess): String {
    val out = StringBuilder()
    fun run(vararg cmd: String) {
        try {
            val p = ProcessBuilder(*cmd).redirectErrorStream(true).start()
            if (!p.waitFor(5, TimeUnit.SECONDS)) p.destroyForcibly()
            out.append("$ ").append(cmd.joinToString(" ")).append("\n")
            out.append(p.inputStream.bufferedReader(Charsets.UTF_8).readText().take(500))
            if (out.lastOrNull() != '\n') out.append('\n')
        } catch (e: Exception) {
            out.append("(failed: ${e.message})\n")
        }
    }
    val pid = if (lldbDap.isAlive) lldbDap.pid.toString() else "exited"
    out.append("--- port=$port pid=$pid ---\n")
    val os = System.getProperty("os.name").lowercase()
    when {
        os.contains("linux") -> {
            run("ss", "-tlnp")
            run("lsof", "-i", ":$port")
            if (lldbDap.isAlive) run("ps", "-p", lldbDap.pid.toString(), "-o", "pid,state,etime,args")
        }
        os.contains("mac") || os.contains("darwin") -> {
            run("lsof", "-i", ":$port")
            if (lldbDap.isAlive) run("ps", "-p", lldbDap.pid.toString(), "-o", "pid,state,etime,command")
        }
        else -> run("netstat", "-an")
    }
    return out.toString()
}

/**
 * Test-level abstraction over behavioral differences between DAP server
 * implementations. Each [ServerKind] implements this interface so tests can
 * call server-specific operations polymorphically instead of switching on
 * server kind.
 */
interface DapTestServer {
    /**
     * The `evaluate` context used for running LLDB commands on this server,
     * or `null` if the server does not support evaluate.
     *
     * - CodeLLDB: `"_command"` (custom extension that returns output in the response body).
     * - lldb-dap: `"repl"` (standard DAP; lldb-dap runs LLDB commands in repl context).
     * - KDAP: `null` (not yet implemented).
     */
    val evaluateCommandContext: String?

    /** Whether this server supports the `evaluate` DAP request. */
    val supportsEvaluate: Boolean get() = evaluateCommandContext != null

    /**
     * Sends a DAP `evaluate` request to run [expression] as an LLDB command,
     * using the appropriate context for this server.
     *
     * @throws IllegalStateException if this server does not support evaluate.
     */
    fun sendEvaluateRequest(output: OutputStream, seq: Int, expression: String) {
        val context = evaluateCommandContext
            ?: error("$this does not support the evaluate command")
        DapTestUtils.sendEvaluateRequest(output, seq, expression, context)
    }

}

/**
 * Which server the connection talks to. Implements [DapTestServer] so tests
 * get per-server behavior without `when` blocks.
 */
enum class ServerKind : DapTestServer {
    /** Our KDAP server (MainKt). */
    OUR_SERVER {
        override val evaluateCommandContext: String? = null
    },

    /** lldb-dap from LLVM prebuilts. */
    LLDB_DAP {
        override val evaluateCommandContext = "repl"
    },

    /** CodeLLDB adapter (from codelldb-vsix or KDAP_CODELDB_EXTENSION). */
    CODELDB {
        override val evaluateCommandContext = "_command"
    }
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
            val (lldbDap, port) = LldbDapHarness.startTcp()
            val stdoutBuf = StringBuilder()
            val stderrBuf = StringBuilder()
            // In TCP mode lldb-dap's stdout/stderr are not DAP traffic — capture
            // them for diagnostics in case the connection fails to come up.
            thread(isDaemon = true) {
                lldbDap.inputStream.bufferedReader(Charsets.UTF_8).use { r ->
                    r.lineSequence().forEach { stdoutBuf.appendLine(it) }
                }
            }
            thread(isDaemon = true) {
                lldbDap.errorStream.bufferedReader(Charsets.UTF_8).use { r ->
                    r.lineSequence().forEach { stderrBuf.appendLine(it) }
                }
            }
            return try {
                val socket = DapProcessHarness.connectToPort(port, 120_000).apply { soTimeout = 15_000 }
                object : ConnectionContext {
                    override val inputStream: InputStream = socket.getInputStream()
                    override val outputStream: OutputStream = socket.getOutputStream()
                    override fun close() {
                        socket.close()
                        lldbDap.close()
                    }
                }
            } catch (e: IllegalStateException) {
                val diagnostics = runPortDiagnostics(port, lldbDap)
                lldbDap.close()
                val exitInfo = if (!lldbDap.isAlive) " (lldb-dap exited with ${lldbDap.exitValue})" else ""
                val out = stdoutBuf.toString().trim().takeLast(2000).ifEmpty { "(no stdout)" }
                val err = stderrBuf.toString().trim().takeLast(2000).ifEmpty { "(no stderr)" }
                val emptyNote = if (out == "(no stdout)" && err == "(no stderr)")
                    "\n(Empty stdout/stderr is common: lldb-dap redirects them after startup.)"
                else ""
                val portNote = "\n(If Diagnostics show process running but port not in ss -tlnp, lldb-dap is stuck in init before bind.)"
                throw IllegalStateException(
                    "${e.message}$exitInfo$emptyNote$portNote\nlldb-dap stdout:\n$out\nlldb-dap stderr:\n$err\nDiagnostics:\n$diagnostics",
                    e
                )
            }
        }
    },
    /** Connects to CodeLLDB adapter via stdio (adapter with no args). */
    STDIO_CODELDB(ServerKind.CODELDB) {
        override fun connect(): ConnectionContext {
            if (!CodeLldbHarness.isAvailable())
                throw IllegalStateException("CodeLLDB adapter not available (run scripts/download-codelldb-vsix.sh or set KDAP_CODELDB_EXTENSION)")
            val process = CodeLldbHarness.startAdapter()
            return object : ConnectionContext {
                override val inputStream: InputStream = process.inputStream
                override val outputStream: OutputStream = process.outputStream
                override fun close() = CodeLldbHarness.stopProcess(process)
            }
        }
    },
    /** Connects to CodeLLDB adapter via TCP (adapter --port N). */
    TCP_CODELDB(ServerKind.CODELDB) {
        override fun connect(): ConnectionContext {
            if (!CodeLldbHarness.isAvailable())
                throw IllegalStateException("CodeLLDB adapter not available (run scripts/download-codelldb-vsix.sh or set KDAP_CODELDB_EXTENSION)")
            val (process, port) = CodeLldbHarness.startAdapterTcp()
            val socket = DapProcessHarness.connectToPort(port, 30_000).apply { soTimeout = 15_000 }
            return object : ConnectionContext {
                override val inputStream: InputStream = socket.getInputStream()
                override val outputStream: OutputStream = socket.getOutputStream()
                override fun close() {
                    socket.close()
                    CodeLldbHarness.stopProcess(process)
                }
            }
        }
    };

    abstract fun connect(): ConnectionContext

    companion object {
        /** Modes that run our KDAP server (excludes lldb-dap, codelldb). For parameterized tests that only target our server. */
        @JvmStatic
        fun ourServerModes(): List<ConnectionMode> = entries.filter { it.serverKind == ServerKind.OUR_SERVER }

        /** Modes that connect to the CodeLLDB adapter (stdio or TCP). For parameterized tests that target CodeLLDB. */
        @JvmStatic
        fun codelldbModes(): List<ConnectionMode> = entries.filter { it.serverKind == ServerKind.CODELDB }
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

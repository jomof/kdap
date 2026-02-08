package com.github.jomof

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
     * Sends a DAP `evaluate` request to run [expression] as an LLDB command.
     * Each server kind sends the appropriate context internally — callers
     * never need to think about it.
     */
    fun sendEvaluateRequest(output: OutputStream, seq: Int, expression: String)
}

/**
 * Shared [DapTestServer] for CodeLLDB-compatible servers (KDAP and CodeLLDB).
 * Both [ServerKind.OUR_SERVER] and [ServerKind.CODELDB] reference this same
 * instance, so they cannot drift apart. When a new method is added to
 * [DapTestServer], the compiler forces it to be implemented here — once —
 * and both server kinds get it automatically.
 */
private val codelldbCompatibleTestServer = object : DapTestServer {
    override fun sendEvaluateRequest(output: OutputStream, seq: Int, expression: String) =
        DapTestUtils.sendEvaluateRequest(output, seq, expression, "_command")
}

/** [DapTestServer] for raw lldb-dap (no KDAP or CodeLLDB in front). */
private val lldbDapTestServer = object : DapTestServer {
    override fun sendEvaluateRequest(output: OutputStream, seq: Int, expression: String) =
        DapTestUtils.sendEvaluateRequest(output, seq, expression, "repl")
}

/**
 * Which server the connection talks to. Each kind carries a [testServer] that
 * provides the correct request-sending behavior for that server type.
 *
 * [OUR_SERVER] and [CODELDB] share the **same** [DapTestServer] instance
 * ([codelldbCompatibleTestServer]) so they are identical by construction and
 * can never independently evolve.
 */
enum class ServerKind(val testServer: DapTestServer) {
    /** Our KDAP server — always a decorator in front of lldb-dap. CodeLLDB-compatible. */
    OUR_SERVER(codelldbCompatibleTestServer),

    /** lldb-dap from LLVM prebuilts (direct, no KDAP in front). Standard DAP. */
    LLDB_DAP(lldbDapTestServer),

    /** CodeLLDB adapter (from codelldb-vsix or KDAP_CODELDB_EXTENSION). */
    CODELDB(codelldbCompatibleTestServer),
}

/**
 * Ways to connect to the DAP server process for tests. Use [connect] to get a
 * [ConnectionContext] (streams + cleanup); use [ConnectionContext.close] when done.
 * [serverKind] indicates whether this mode talks to our server or lldb-dap (for test branching).
 */
enum class ConnectionMode(val serverKind: ServerKind) {
    // ── KDAP modes (mirrors CodeLLDB modes below) ────────────────────────
    /** Connects to KDAP adapter via stdio (no transport args). */
    STDIO(ServerKind.OUR_SERVER) {
        override fun connect(): ConnectionContext {
            val process = KdapHarness.startAdapter()
            return object : ConnectionContext {
                override val inputStream: InputStream = process.inputStream
                override val outputStream: OutputStream = process.outputStream
                override fun close() = KdapHarness.stopProcess(process)
            }
        }
    },
    /** Connects to KDAP adapter via TCP (--port N). */
    TCP_LISTEN(ServerKind.OUR_SERVER) {
        override fun connect(): ConnectionContext {
            val (process, port) = KdapHarness.startAdapterTcp()
            val socket = TcpTestUtils.connectToPort(port).apply { soTimeout = 10_000 }
            return object : ConnectionContext {
                override val inputStream: InputStream = socket.getInputStream()
                override val outputStream: OutputStream = socket.getOutputStream()
                override fun close() {
                    socket.close()
                    KdapHarness.stopProcess(process)
                }
            }
        }
    },
    /** Connects to KDAP adapter via reverse TCP (--connect N). */
    TCP_CONNECT(ServerKind.OUR_SERVER) {
        override fun connect(): ConnectionContext {
            val server = ServerSocket(0)
            val port = server.localPort
            val process = KdapHarness.startAdapter("--connect", port.toString())
            val socket = server.accept().apply { soTimeout = 10_000 }
            return object : ConnectionContext {
                override val inputStream: InputStream = socket.getInputStream()
                override val outputStream: OutputStream = socket.getOutputStream()
                override fun close() {
                    socket.close()
                    KdapHarness.stopProcess(process)
                    server.close()
                }
            }
        }
    },
    /** Runs KDAP in-process by calling [main] directly with redirected System.in/out. */
    IN_PROCESS(ServerKind.OUR_SERVER) {
        override fun connect(): ConnectionContext {
            val lldbDapPath = LldbDapHarness.resolveLldbDapPath()
                ?: error("lldb-dap not found (run scripts/download-lldb.sh or set KDAP_LLDB_ROOT)")
            val originalIn = System.`in`
            val originalOut = System.out
            val serverInput = java.io.PipedInputStream()
            val testToServer = java.io.PipedOutputStream(serverInput)
            val serverToClient = java.io.PipedOutputStream()
            val testFromServer = java.io.PipedInputStream(serverToClient)
            System.setIn(serverInput)
            System.setOut(java.io.PrintStream(serverToClient, /* autoFlush = */ true))
            val serverReady = CountDownLatch(1)
            val serverThread = thread {
                serverReady.countDown()
                main(arrayOf("--lldb-dap", lldbDapPath.absolutePath))
            }
            serverReady.await()
            return object : ConnectionContext {
                override val inputStream: InputStream = testFromServer
                override val outputStream: OutputStream = testToServer
                override fun close() {
                    testToServer.close()
                    serverThread.join(5000)
                    System.setIn(originalIn)
                    System.setOut(originalOut)
                }
            }
        }
    },

    // ── lldb-dap direct (reference implementation, no KDAP in front) ─────
    /** Connects to lldb-dap via TCP (-p port). Stdout and stderr captured on failure (keep for CI diagnostics). */
    TCP_LLDB(ServerKind.LLDB_DAP) {
        override fun connect(): ConnectionContext {
            if (!LldbDapHarness.isAvailable())
                throw IllegalStateException("lldb-dap not available (run scripts/download-lldb.sh or set KDAP_LLDB_ROOT)")
            val (lldbDap, port) = LldbDapHarness.startTcp()
            val stdoutBuf = StringBuilder()
            val stderrBuf = StringBuilder()
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
                val socket = TcpTestUtils.connectToPort(port, 120_000).apply { soTimeout = 15_000 }
                // lldb-dap's TCP mode has a race: the OS accept-backlog accepts our
                // connection before the application is ready for DAP traffic. A brief
                // pause lets lldb-dap finish initializing after accept().
                Thread.sleep(200)
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

    // ── CodeLLDB modes (KDAP modes above mirror this structure) ──────────
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
            val socket = TcpTestUtils.connectToPort(port, 30_000).apply { soTimeout = 15_000 }
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

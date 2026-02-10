package com.github.jomof

import java.io.BufferedInputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.ServerSocket
import kotlin.concurrent.thread

/**
 * Builds a diagnostic string for a server subprocess: alive/exited state,
 * exit code, and captured stderr/stdout (last 2000 chars each).
 */
private fun processDiagnostics(
    name: String,
    process: Process,
    stdout: StringBuilder? = null,
    stderr: StringBuilder? = null,
): String = buildString {
    appendLine("$name diagnostics:")
    if (process.isAlive) {
        appendLine("  process: alive (pid ${process.pid()})")
    } else {
        appendLine("  process: exited with code ${process.exitValue()}")
    }
    val err = stderr?.toString()?.trim()?.takeLast(2000)
    val out = stdout?.toString()?.trim()?.takeLast(2000)
    if (!out.isNullOrEmpty()) appendLine("  stdout:\n    ${out.replace("\n", "\n    ")}")
    if (!err.isNullOrEmpty()) appendLine("  stderr:\n    ${err.replace("\n", "\n    ")}")
    if (out.isNullOrEmpty() && err.isNullOrEmpty()) appendLine("  (no stdout/stderr captured)")
}

/** Formats captured stderr for appending to diagnostics (last 2000 chars). */
private fun processDiagnosticStderr(stderr: StringBuilder): String {
    val text = stderr.toString().trim().takeLast(2000)
    return if (text.isNotEmpty()) "  stderr:\n    ${text.replace("\n", "\n    ")}\n" else ""
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

    /**
     * Sends a DAP `launch` request for [program]. Each server kind adds
     * the appropriate extra arguments internally (e.g. CodeLLDB needs
     * `terminal` and `name`; lldb-dap does not).
     */
    fun sendLaunchRequest(output: OutputStream, seq: Int, program: String)
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

    override fun sendLaunchRequest(output: OutputStream, seq: Int, program: String) =
        DapTestUtils.sendLaunchRequest(output, seq, program, mapOf("name" to "test", "terminal" to "console"))
}

/** [DapTestServer] for raw lldb-dap (no KDAP or CodeLLDB in front). */
private val lldbDapTestServer = object : DapTestServer {
    override fun sendEvaluateRequest(output: OutputStream, seq: Int, expression: String) =
        DapTestUtils.sendEvaluateRequest(output, seq, expression, "repl")

    override fun sendLaunchRequest(output: OutputStream, seq: Int, program: String) =
        DapTestUtils.sendLaunchRequest(output, seq, program)
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
            val stderrBuf = StringBuilder()
            kotlin.concurrent.thread(isDaemon = true) {
                process.errorStream.bufferedReader(Charsets.UTF_8).use { r ->
                    r.lineSequence().forEach { stderrBuf.appendLine(it) }
                }
            }
            return object : ConnectionContext {
                override val inputStream: InputStream = TimeoutInputStream(BufferedInputStream(process.inputStream, DAP_READ_BUFFER_SIZE))
                override val outputStream: OutputStream = process.outputStream
                override fun diagnostics(): String = processDiagnostics("kdap", process, stderr = stderrBuf)
                override fun close() {
                    KdapHarness.stopProcess(process)
                }
            }
        }
    },
    /** Connects to KDAP adapter via TCP (--port N). */
    TCP_LISTEN(ServerKind.OUR_SERVER) {
        override fun connect(): ConnectionContext {
            val (process, port) = KdapHarness.startAdapterTcp()
            val stderrBuf = StringBuilder()
            kotlin.concurrent.thread(isDaemon = true) {
                process.errorStream.bufferedReader(Charsets.UTF_8).use { r ->
                    r.lineSequence().forEach { stderrBuf.appendLine(it) }
                }
            }
            val socket = TcpTestUtils.connectToPort(port, expectedProcess = process).apply { soTimeout = 10_000 }
            return object : ConnectionContext {
                override val inputStream: InputStream = BufferedInputStream(socket.getInputStream(), DAP_READ_BUFFER_SIZE)
                override val outputStream: OutputStream = socket.getOutputStream()
                override fun diagnostics(): String = processDiagnostics("kdap", process, stderr = stderrBuf)
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
            val stderrBuf = StringBuilder()
            kotlin.concurrent.thread(isDaemon = true) {
                process.errorStream.bufferedReader(Charsets.UTF_8).use { r ->
                    r.lineSequence().forEach { stderrBuf.appendLine(it) }
                }
            }
            val socket = server.accept().apply { soTimeout = 10_000 }
            return object : ConnectionContext {
                override val inputStream: InputStream = BufferedInputStream(socket.getInputStream(), DAP_READ_BUFFER_SIZE)
                override val outputStream: OutputStream = socket.getOutputStream()
                override fun diagnostics(): String = processDiagnostics("kdap", process, stderr = stderrBuf)
                override fun close() {
                    socket.close()
                    KdapHarness.stopProcess(process)
                    server.close()
                }
            }
        }
    },
    /**
     * Runs KDAP in-process by calling [main] directly in a thread, using TCP
     * (--port) instead of stdio. This avoids System.in/out redirection entirely.
     */
    IN_PROCESS(ServerKind.OUR_SERVER) {
        override fun connect(): ConnectionContext {
            val lldbDapPath = LldbDapHarness.resolveLldbDapPath()
                ?: error("lldb-dap not found (run scripts/download-lldb.sh or set KDAP_LLDB_ROOT)")
            val port = java.net.ServerSocket(0).use { it.localPort }
            val serverError = java.util.concurrent.atomic.AtomicReference<Throwable?>(null)
            val serverThread = thread(name = "kdap-in-process") {
                try {
                    mainImpl(arrayOf("--port", port.toString(), "--lldb-dap", lldbDapPath.absolutePath))
                } catch (e: Throwable) {
                    serverError.set(e)
                    System.err.println("[KDAP IN_PROCESS] main() threw: $e")
                    e.printStackTrace(System.err)
                }
            }
            // Poll for connectivity, checking the server thread is still alive.
            // If the thread died (e.g. BindException), fail immediately instead
            // of waiting for timeout (which could connect to a stale server on
            // the same port from a previous test).
            val socket = run {
                val deadline = System.currentTimeMillis() + 10_000
                while (System.currentTimeMillis() < deadline) {
                    serverError.get()?.let { throw it }
                    if (!serverThread.isAlive) {
                        error("KDAP in-process server thread died before accepting connections on port $port")
                    }
                    try {
                        return@run java.net.Socket("127.0.0.1", port)
                    } catch (_: Exception) {
                        Thread.sleep(20)
                    }
                }
                error("Port $port did not become reachable within 10000ms")
            }.apply { soTimeout = 10_000 }
            return object : ConnectionContext {
                override val inputStream: InputStream = BufferedInputStream(socket.getInputStream(), DAP_READ_BUFFER_SIZE)
                override val outputStream: OutputStream = socket.getOutputStream()
                override fun diagnostics(): String = buildString {
                    appendLine("kdap (in-process) diagnostics:")
                    appendLine("  server thread alive: ${serverThread.isAlive}")
                    serverError.get()?.let { appendLine("  server error: $it") }
                }
                override fun close() {
                    socket.close()
                    serverThread.join(5000)
                }
            }
        }
    },

    // ── lldb-dap direct (reference implementation, no KDAP in front) ─────
    /** Connects to lldb-dap via stdio — the same transport KDAP uses in production. */
    STDIO_LLDB(ServerKind.LLDB_DAP) {
        override fun connect(): ConnectionContext {
            if (!LldbDapHarness.isAvailable())
                throw IllegalStateException("lldb-dap not available (run scripts/download-lldb.sh or set KDAP_LLDB_ROOT)")
            val lldbDap = LldbDapHarness.start()
            val stderrBuf = StringBuilder()
            thread(isDaemon = true) {
                lldbDap.errorStream.bufferedReader(Charsets.UTF_8).use { r ->
                    r.lineSequence().forEach { stderrBuf.appendLine(it) }
                }
            }
            return object : ConnectionContext {
                override val inputStream: InputStream = TimeoutInputStream(BufferedInputStream(lldbDap.inputStream, DAP_READ_BUFFER_SIZE))
                override val outputStream: OutputStream = lldbDap.outputStream
                override fun diagnostics(): String =
                    lldbDap.diagnostics() + processDiagnosticStderr(stderrBuf)
                override fun close() = lldbDap.close()
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
            val stderrBuf = StringBuilder()
            kotlin.concurrent.thread(isDaemon = true) {
                process.errorStream.bufferedReader(Charsets.UTF_8).use { r ->
                    r.lineSequence().forEach { stderrBuf.appendLine(it) }
                }
            }
            return object : ConnectionContext {
                override val inputStream: InputStream = TimeoutInputStream(BufferedInputStream(process.inputStream, DAP_READ_BUFFER_SIZE))
                override val outputStream: OutputStream = process.outputStream
                override fun diagnostics(): String = processDiagnostics("codelldb", process, stderr = stderrBuf)
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
            val stderrBuf = StringBuilder()
            kotlin.concurrent.thread(isDaemon = true) {
                process.errorStream.bufferedReader(Charsets.UTF_8).use { r ->
                    r.lineSequence().forEach { stderrBuf.appendLine(it) }
                }
            }
            val socket = TcpTestUtils.connectToPort(port, 30_000, expectedProcess = process).apply { soTimeout = 15_000 }
            return object : ConnectionContext {
                override val inputStream: InputStream = BufferedInputStream(socket.getInputStream(), DAP_READ_BUFFER_SIZE)
                override val outputStream: OutputStream = socket.getOutputStream()
                override fun diagnostics(): String = processDiagnostics("codelldb", process, stderr = stderrBuf)
                override fun close() {
                    socket.close()
                    CodeLldbHarness.stopProcess(process)
                }
            }
        }
    };

    abstract fun connect(): ConnectionContext

    /** e.g. "kdap (stdio)", "lldb-dap (tcp)", "codelldb (stdio)" — matches [CompareOverhead] labels. */
    override fun toString(): String {
        val product = when (serverKind) {
            ServerKind.OUR_SERVER -> "kdap"
            ServerKind.LLDB_DAP  -> "lldb-dap"
            ServerKind.CODELDB   -> "codelldb"
        }
        val transport = when (this) {
            STDIO, STDIO_LLDB, STDIO_CODELDB -> "stdio"
            TCP_LISTEN                       -> "tcp-listen"
            TCP_CONNECT                      -> "tcp-connect"
            TCP_CODELDB                      -> "tcp"
            IN_PROCESS                       -> "in-process/tcp"
        }
        return "$product ($transport)"
    }

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
 * Buffer size for [BufferedInputStream] wrapping all DAP input streams.
 *
 * A 64 KB buffer means [readDapMessage]'s byte-by-byte header reads hit the
 * in-memory buffer instead of issuing a syscall per byte. More importantly,
 * the large read calls on the underlying socket keep the TCP receive buffer
 * drained, preventing TCP back-pressure that can trigger WSAEWOULDBLOCK errors
 * in servers that use non-blocking sockets (e.g. lldb-dap on Windows).
 */
private const val DAP_READ_BUFFER_SIZE = 65_536

/**
 * A connection to the DAP server: input/output streams, [close] for cleanup,
 * and [diagnostics] for failure reporting.
 */
interface ConnectionContext : AutoCloseable {
    val inputStream: InputStream
    val outputStream: OutputStream

    /**
     * If non-null, the DAP `initialize` exchange was already completed during
     * [ConnectionMode.connect] as a readiness handshake. The value is the raw
     * JSON response body. Tests should use this cached response instead of
     * sending a redundant initialize request.
     */
    val initializeResponse: String? get() = null

    /**
     * Returns a human-readable summary of the server's state for failure
     * diagnostics: stderr output, process exit code, etc. Called on test
     * failure to enrich exception messages.
     */
    fun diagnostics(): String = "(no diagnostics available)"
}

/**
 * Wraps a [Process] [InputStream] (which has no native timeout support) with
 * a polling timeout using [available]. If no byte becomes available within
 * [timeoutMs], throws [java.net.SocketTimeoutException].
 *
 * This gives STDIO-mode connections the same timeout behavior as TCP sockets
 * with `soTimeout`, preventing tests from hanging indefinitely when a debug
 * adapter stops producing output.
 */
class TimeoutInputStream(
    private val delegate: InputStream,
    private val timeoutMs: Long = 30_000L
) : InputStream() {

    override fun read(): Int {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (true) {
            if (delegate.available() > 0) return delegate.read()
            if (System.currentTimeMillis() >= deadline) {
                throw java.net.SocketTimeoutException(
                    "STDIO read timed out: no data received within ${timeoutMs}ms"
                )
            }
            Thread.sleep(5)
        }
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (true) {
            if (delegate.available() > 0) return delegate.read(b, off, len)
            if (System.currentTimeMillis() >= deadline) {
                throw java.net.SocketTimeoutException(
                    "STDIO read timed out: no data received within ${timeoutMs}ms"
                )
            }
            Thread.sleep(5)
        }
    }

    override fun available(): Int = delegate.available()
    override fun close() = delegate.close()
}

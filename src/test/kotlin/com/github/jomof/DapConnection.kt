package com.github.jomof

import com.github.jomof.dap.messages.DapEvent
import com.github.jomof.dap.messages.DapMessage
import com.github.jomof.dap.messages.DapResponse
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * A strongly-typed connection to a DAP server. Sends and receives
 * [DapMessage] objects and manages server process lifecycles.
 *
 * Use the [Companion] factory methods to create instances.
 */
interface DapConnection : AutoCloseable {
    /** Send a strongly-typed DAP message. */
    fun send(message: DapMessage)

    /** Read the next DAP message from the server. */
    fun receive(): DapMessage

    /** e.g. "remote-macosx" or "remote-linux" */
    val platformName: String

    /** e.g. "connect://127.0.0.1:12345" or "unix:///tmp/lldb.sock" */
    val platformConnectUrl: String

    /** Human-readable diagnostic info for failure reporting. */
    fun diagnostics(): String

    /**
     * Reads messages until a [DapEvent] with the given [eventType] is found.
     * Non-matching messages are skipped. Throws if [maxMessages] messages
     * are read without finding it.
     */
    fun waitForEvent(eventType: String, maxMessages: Int = 50): DapEvent {
        repeat(maxMessages) {
            val message = receive()
            if (message is DapEvent && message.event == eventType) return message
        }
        error("No '$eventType' event within $maxMessages messages")
    }

    /**
     * Reads messages until a [DapResponse] with the given [command] is found.
     * Non-matching messages are skipped. Throws if [maxMessages] messages
     * are read without finding it.
     */
    fun waitForResponse(command: String, maxMessages: Int = 50): DapResponse {
        repeat(maxMessages) {
            val message = receive()
            if (message is DapResponse && message.command == command) return message
        }
        error("No '$command' response within $maxMessages messages")
    }

    companion object {
        /**
         * Start lldb-server + CodeLLDB adapter, return a ready [DapConnection].
         *
         * Chain: test → CodeLLDB → lldb-server (remote platform).
         */
        fun createRemoteCodeLldb(debuggee: File): DapConnection {
            require(CodeLldbHarness.isAvailable()) { "CodeLLDB adapter not available" }
            val server = startLldbServer(debuggee)
            val adapterProcess = CodeLldbHarness.startAdapter()
            return buildRemoteConnection("codelldb", adapterProcess, server) {
                CodeLldbHarness.stopProcess(it)
            }
        }

        /**
         * Start lldb-server + KDAP adapter using TCP,
         * return a ready [DapConnection].
         *
         * Chain: test → KDAP → lldb-dap → lldb-server (remote platform via TCP).
         *
         * @param sbLogFile when non-null, KDAP writes SB API call trace here
         */
        fun createRemoteKdap(debuggee: File, sbLogFile: File? = null): DapConnection {
            val server = startLldbServer(debuggee)
            val extraArgs = if (sbLogFile != null) {
                arrayOf("--sb-log", sbLogFile.absolutePath)
            } else {
                emptyArray()
            }
            val adapterProcess = KdapHarness.startAdapter(*extraArgs)
            return buildRemoteConnection("kdap", adapterProcess, server) {
                KdapHarness.stopProcess(it)
            }
        }

        /** Start lldb-server on TCP and wait for it to accept connections. */
        private fun startLldbServer(debuggee: File): LldbServerContext {
            val lldbServer = LldbDapHarness.resolveLldbServer()
                ?: error("lldb-server not found next to lldb-dap")
            require(debuggee.isFile && debuggee.canExecute()) {
                "Debuggee not found or not executable: $debuggee"
            }

            val isMac = System.getProperty("os.name").lowercase().contains("mac")
            val tempDir = java.nio.file.Files.createTempDirectory("lldb-server-remote").toFile()
            val serverPort = ServerSocket(0).use { it.localPort }

            val cmd = listOf(
                lldbServer.absolutePath, "platform", "--server",
                "--listen", "127.0.0.1:$serverPort"
            )
            val pb = ProcessBuilder(cmd).redirectErrorStream(true)
            if (isMac) {
                val debugServer = LldbDapHarness.resolveDebugServer()
                    ?: error("debugserver not found (install Xcode CLT)")
                pb.environment()["LLDB_DEBUGSERVER_PATH"] = debugServer.absolutePath
            }
            pb.directory(tempDir)
            val process = pb.start()

            val output = StringBuilder()
            kotlin.concurrent.thread(isDaemon = true) {
                process.inputStream.bufferedReader(Charsets.UTF_8).use { r ->
                    r.lineSequence().forEach { output.appendLine(it) }
                }
            }

            // Poll until lldb-server accepts TCP connections
            val deadline = System.currentTimeMillis() + 5_000
            while (System.currentTimeMillis() < deadline) {
                if (!process.isAlive) {
                    throw IllegalStateException(
                        "lldb-server exited (exit=${process.exitValue()})\n$output"
                    )
                }
                try {
                    Socket().use { s ->
                        s.connect(InetSocketAddress("127.0.0.1", serverPort), 100)
                    }
                    break
                } catch (_: Exception) { Thread.sleep(10) }
            }
            require(process.isAlive) { "lldb-server not ready within 5s\n$output" }

            val platformName = if (isMac) "remote-macosx" else "remote-linux"
            return LldbServerContext(process, output, "connect://127.0.0.1:$serverPort", platformName)
        }

        /** Start lldb-server on a unix domain socket. */
        private fun startLldbServerUnix(debuggee: File): LldbServerContext {
            val lldbServer = LldbDapHarness.resolveLldbServer()
                ?: error("lldb-server not found next to lldb-dap")
            require(debuggee.isFile && debuggee.canExecute()) {
                "Debuggee not found or not executable: $debuggee"
            }

            val isMac = System.getProperty("os.name").lowercase().contains("mac")

            // Use /tmp with a short name to stay under macOS's 104-byte
            // sun_path limit. Java's createTempDirectory produces paths
            // like /var/folders/.../lldb-server-remote<random>/... which
            // can easily exceed the limit, causing EILSEQ ("Illegal byte
            // sequence") errors.
            val shortId = java.util.UUID.randomUUID().toString().take(8)
            val tempDir = File("/tmp/lldb-$shortId")
            tempDir.mkdirs()
            tempDir.deleteOnExit()

            val socketPath = File(tempDir, "s.sock").absolutePath
            val cmd = listOf(
                lldbServer.absolutePath, "platform", "--server",
                "--listen", socketPath
            )
            val pb = ProcessBuilder(cmd).redirectErrorStream(true)
            if (isMac) {
                val debugServer = LldbDapHarness.resolveDebugServer()
                    ?: error("debugserver not found (install Xcode CLT)")
                pb.environment()["LLDB_DEBUGSERVER_PATH"] = debugServer.absolutePath
            }
            pb.directory(tempDir)
            val process = pb.start()

            val output = StringBuilder()
            kotlin.concurrent.thread(isDaemon = true) {
                process.inputStream.bufferedReader(Charsets.UTF_8).use { r ->
                    r.lineSequence().forEach { output.appendLine(it) }
                }
            }

            // Poll until the socket file appears
            val socketFile = File(socketPath)
            val deadline = System.currentTimeMillis() + 5_000
            while (System.currentTimeMillis() < deadline) {
                if (!process.isAlive) {
                    throw IllegalStateException(
                        "lldb-server exited (exit=${process.exitValue()})\n$output"
                    )
                }
                if (socketFile.exists()) break
                Thread.sleep(10)
            }
            require(process.isAlive) { "lldb-server not ready within 5s\n$output" }
            require(socketFile.exists()) { "Socket file not created: $socketPath\n$output" }

            val platformName = if (isMac) "remote-macosx" else "remote-linux"
            return LldbServerContext(process, output, "unix-connect://$socketPath", platformName)
        }

        /** Shared: wrap an adapter process + lldb-server into a [DapConnection]. */
        private fun buildRemoteConnection(
            adapterName: String,
            adapterProcess: Process,
            server: LldbServerContext,
            stopAdapter: (Process) -> Unit,
        ): DapConnection {
            val stderrBuf = StringBuilder()
            kotlin.concurrent.thread(isDaemon = true) {
                adapterProcess.errorStream.bufferedReader(Charsets.UTF_8).use { r ->
                    r.lineSequence().forEach { stderrBuf.appendLine(it) }
                }
            }

            val input: InputStream = TimeoutInputStream(
                BufferedInputStream(adapterProcess.inputStream, 65_536)
            )
            val output: OutputStream = adapterProcess.outputStream

            return RemoteDapConnection(
                adapterName = adapterName,
                input = input,
                output = output,
                adapterProcess = adapterProcess,
                serverProcess = server.process,
                stderrBuf = stderrBuf,
                serverOutput = server.output,
                platformName = server.platformName,
                platformConnectUrl = server.connectUrl,
                stopAdapter = stopAdapter,
            )
        }
    }
}

/** Bundles lldb-server process state for the factory helpers. */
private data class LldbServerContext(
    val process: Process,
    val output: StringBuilder,
    val connectUrl: String,
    val platformName: String,
)

/**
 * [DapConnection] backed by a DAP adapter (CodeLLDB or KDAP) talking to
 * a remote lldb-server platform.
 */
private class RemoteDapConnection(
    private val adapterName: String,
    private val input: InputStream,
    private val output: OutputStream,
    private val adapterProcess: Process,
    private val serverProcess: Process,
    private val stderrBuf: StringBuilder,
    private val serverOutput: StringBuilder,
    override val platformName: String,
    override val platformConnectUrl: String,
    private val stopAdapter: (Process) -> Unit,
) : DapConnection {

    private val nextSeq = AtomicInteger(1)

    override fun send(message: DapMessage) {
        val jsonObj = JSONObject(message.toJson())
        jsonObj.put("seq", nextSeq.getAndIncrement())
        val bytes = jsonObj.toString().toByteArray(StandardCharsets.UTF_8)
        val header = "Content-Length: ${bytes.size}\r\n\r\n"
        output.write(header.toByteArray(StandardCharsets.UTF_8))
        output.write(bytes)
        output.flush()
    }

    override fun receive(): DapMessage {
        val raw = DapTestUtils.readDapMessage(input)
        return DapMessage.parse(raw)
    }

    override fun diagnostics(): String = buildString {
        appendLine("$adapterName diagnostics:")
        if (adapterProcess.isAlive) {
            appendLine("  process: alive (pid ${adapterProcess.pid()})")
        } else {
            appendLine("  process: exited with code ${adapterProcess.exitValue()}")
        }
        val err = stderrBuf.toString().trim().takeLast(2000)
        if (err.isNotEmpty()) appendLine("  stderr:\n    ${err.replace("\n", "\n    ")}")

        appendLine("lldb-server diagnostics:")
        if (serverProcess.isAlive) {
            appendLine("  process: alive (pid ${serverProcess.pid()})")
        } else {
            appendLine("  process: exited with code ${serverProcess.exitValue()}")
        }
        val out = serverOutput.toString().trim().takeLast(2000)
        if (out.isNotEmpty()) appendLine("  stdout:\n    ${out.replace("\n", "\n    ")}")
    }

    override fun close() {
        stopAdapter(adapterProcess)
        serverProcess.destroy()
        serverProcess.waitFor(3, TimeUnit.SECONDS)
        if (serverProcess.isAlive) serverProcess.destroyForcibly()
    }
}

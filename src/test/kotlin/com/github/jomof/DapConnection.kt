package com.github.jomof

import com.github.jomof.dap.messages.DapEvent
import com.github.jomof.dap.messages.DapMessage
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

    /** e.g. "connect://127.0.0.1:12345" */
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

    companion object {
        /**
         * Start lldb-server in remote platform mode, start CodeLLDB, and
         * return a ready [DapConnection].
         *
         * The returned connection owns both processes; [close] tears them
         * down. The initialize handshake is NOT performed — callers control
         * the full protocol sequence.
         *
         * @param debuggee the binary to debug (used only for availability
         *   checks here; the actual program path goes in the launch request).
         */
        fun createRemoteCodeLldb(debuggee: File): DapConnection {
            require(CodeLldbHarness.isAvailable()) { "CodeLLDB adapter not available" }
            val lldbServer = LldbDapHarness.resolveLldbServer()
                ?: error("lldb-server not found next to lldb-dap")
            require(debuggee.isFile && debuggee.canExecute()) {
                "Debuggee not found or not executable: $debuggee"
            }

            // Platform detection
            val isMac = System.getProperty("os.name").lowercase().contains("mac")

            // Temp working dir — CodeLLDB copies the program here via
            // the remote platform's file transfer mechanism.
            val tempDir = java.nio.file.Files.createTempDirectory("lldb-server-remote").toFile()

            // Start lldb-server on OS-assigned TCP port
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
            val serverProcess = pb.start()

            // Drain lldb-server output in background
            val serverOutput = StringBuilder()
            kotlin.concurrent.thread(isDaemon = true) {
                serverProcess.inputStream.bufferedReader(Charsets.UTF_8).use { r ->
                    r.lineSequence().forEach { serverOutput.appendLine(it) }
                }
            }

            // Poll until lldb-server accepts TCP connections
            val deadline = System.currentTimeMillis() + 5_000
            while (System.currentTimeMillis() < deadline) {
                if (!serverProcess.isAlive) {
                    throw IllegalStateException(
                        "lldb-server exited (exit=${serverProcess.exitValue()})\n$serverOutput"
                    )
                }
                try {
                    Socket().use { s ->
                        s.connect(InetSocketAddress("127.0.0.1", serverPort), 100)
                    }
                    break
                } catch (_: Exception) { Thread.sleep(10) }
            }
            require(serverProcess.isAlive) {
                "lldb-server not ready within 5s\n$serverOutput"
            }

            // Start CodeLLDB adapter via stdio
            val codelldbProcess = CodeLldbHarness.startAdapter()
            val stderrBuf = StringBuilder()
            kotlin.concurrent.thread(isDaemon = true) {
                codelldbProcess.errorStream.bufferedReader(Charsets.UTF_8).use { r ->
                    r.lineSequence().forEach { stderrBuf.appendLine(it) }
                }
            }

            val platformName = if (isMac) "remote-macosx" else "remote-linux"
            val platformConnectUrl = "connect://127.0.0.1:$serverPort"

            val input: InputStream = TimeoutInputStream(
                BufferedInputStream(codelldbProcess.inputStream, 65_536)
            )
            val output: OutputStream = codelldbProcess.outputStream

            return RemoteCodeLldbDapConnection(
                input = input,
                output = output,
                codelldbProcess = codelldbProcess,
                serverProcess = serverProcess,
                stderrBuf = stderrBuf,
                serverOutput = serverOutput,
                platformName = platformName,
                platformConnectUrl = platformConnectUrl,
            )
        }
    }
}

/**
 * [DapConnection] backed by a CodeLLDB adapter talking to a remote
 * lldb-server platform. Created by [DapConnection.createRemoteCodeLldb].
 */
private class RemoteCodeLldbDapConnection(
    private val input: InputStream,
    private val output: OutputStream,
    private val codelldbProcess: Process,
    private val serverProcess: Process,
    private val stderrBuf: StringBuilder,
    private val serverOutput: StringBuilder,
    /** e.g. "remote-macosx" or "remote-linux" */
    override val platformName: String,
    /** e.g. "connect://127.0.0.1:12345" */
    override val platformConnectUrl: String,
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
        appendLine("codelldb diagnostics:")
        if (codelldbProcess.isAlive) {
            appendLine("  process: alive (pid ${codelldbProcess.pid()})")
        } else {
            appendLine("  process: exited with code ${codelldbProcess.exitValue()}")
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
        CodeLldbHarness.stopProcess(codelldbProcess)
        serverProcess.destroy()
        serverProcess.waitFor(3, TimeUnit.SECONDS)
        if (serverProcess.isAlive) serverProcess.destroyForcibly()
    }
}

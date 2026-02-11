package com.github.jomof

import com.github.jomof.dap.messages.CommonLaunchFields
import com.github.jomof.dap.messages.LaunchRequest
import com.github.jomof.dap.messages.LaunchRequestArguments
import com.github.jomof.dap.messages.TerminalKind
import org.json.JSONObject
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.io.BufferedInputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit

/**
 * Tests that CodeLLDB can launch a debuggee via lldb-server remote
 * debugging (the "Remote launch" workflow from MANUAL.md).
 *
 * The sequence is:
 * 1. Start `lldb-server platform --server --listen 127.0.0.1:<port>`
 * 2. Connect CodeLLDB via stdio
 * 3. Send `initialize`
 * 4. Send `launch` with `initCommands` that select + connect to the
 *    remote platform
 * 5. Wait for `initialized` → send `configurationDone` → wait for
 *    `terminated`
 *
 * This verifies the full remote launch lifecycle locally (loopback).
 */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class DapRemoteLaunchTest {

    @Test
    fun `codelldb remote launch via lldb-server`() {
        assumeTrue(CodeLldbHarness.isAvailable(), "CodeLLDB adapter not available")
        val lldbServer = LldbDapHarness.resolveLldbServer()
        assumeTrue(lldbServer != null, "lldb-server not found next to lldb-dap")
        val debuggee = DapTestUtils.resolveDebuggeeBinary()

        // 1. Start lldb-server on a TCP port
        //    CodeLLDB requires a TCP-reachable platform to spawn its GDB server;
        //    unix sockets (abstract or file-based) connect but fail at GDB launch
        //    with "unable to launch a GDB server on '127.0.0.1'"
        val isMac = System.getProperty("os.name").lowercase().contains("mac")
        val platformName = if (isMac) "remote-macosx" else "remote-linux"

        // Use a temp directory as working dir — CodeLLDB copies the program
        // here via the remote platform's file transfer mechanism.
        val tempDir = java.nio.file.Files.createTempDirectory("lldb-server-remote").toFile()

        val serverPort = java.net.ServerSocket(0).use { it.localPort }
        val cmd = listOf(
            lldbServer!!.absolutePath, "platform", "--server",
            "--listen", "127.0.0.1:$serverPort"
        )
        val platformConnectUrl = "connect://127.0.0.1:$serverPort"

        val pb = ProcessBuilder(cmd).redirectErrorStream(true)
        if (isMac) {
            val debugServer = LldbDapHarness.resolveDebugServer()
            assumeTrue(debugServer != null, "debugserver not found (install Xcode CLT)")
            pb.environment()["LLDB_DEBUGSERVER_PATH"] = debugServer!!.absolutePath
        }
        pb.directory(tempDir)
        val serverProcess = pb.start()

        // Drain lldb-server output in background
        val serverOutput = StringBuilder()
        @Suppress("UNUSED_VARIABLE")
        val drainThread = kotlin.concurrent.thread(isDaemon = true) {
            serverProcess.inputStream.bufferedReader(Charsets.UTF_8).use { r ->
                r.lineSequence().forEach { serverOutput.appendLine(it) }
            }
        }

        // Poll until lldb-server accepts TCP connections (no hard sleep)
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline) {
            if (!serverProcess.isAlive) {
                throw IllegalArgumentException(
                    "lldb-server exited (exit=${serverProcess.exitValue()})\n$serverOutput"
                )
            }
            try {
                java.net.Socket().use { s ->
                    s.connect(java.net.InetSocketAddress("127.0.0.1", serverPort), 100)
                }
                break
            } catch (_: Exception) { Thread.sleep(10) }
        }
        require(serverProcess.isAlive) {
            "lldb-server not ready within 5s\n$serverOutput"
        }

        try {
            // 2. Connect to CodeLLDB via stdio
            val codelldbProcess = CodeLldbHarness.startAdapter()
            val stderrBuf = StringBuilder()
            kotlin.concurrent.thread(isDaemon = true) {
                codelldbProcess.errorStream.bufferedReader(Charsets.UTF_8).use { r ->
                    r.lineSequence().forEach { stderrBuf.appendLine(it) }
                }
            }

            val input: InputStream = TimeoutInputStream(
                BufferedInputStream(codelldbProcess.inputStream, 65_536)
            )
            val output: OutputStream = codelldbProcess.outputStream

            val messageLog = mutableListOf<String>()
            var phase = "setup"

            try {
                // 3. Initialize
                phase = "initialize"
                messageLog.add("→ sent: initialize request")
                DapTestUtils.sendInitializeRequest(output)
                val initResponse = DapTestUtils.readDapMessage(input)
                messageLog.add("← recv: initialize response")
                DapTestUtils.assertValidInitializeResponse(initResponse)

                // 4. Launch with initCommands for remote platform
                phase = "launch (remote via lldb-server: $platformConnectUrl)"

                val launchRequest = LaunchRequest(
                    seq = 2,
                    arguments = LaunchRequestArguments(
                        common = CommonLaunchFields(
                            name = "remote-test",
                            initCommands = listOf(
                                "platform select $platformName",
                                "platform connect $platformConnectUrl",
                            ),
                        ),
                        program = debuggee.absolutePath,
                        terminal = TerminalKind.Console,
                    ),
                )
                messageLog.add("→ sent: launch request (remote: $platformConnectUrl)")
                sendDapMessage(output, launchRequest.toJson())

                // 5. Wait for 'initialized' event
                phase = "waiting for 'initialized' event"
                readEventAndLog(input, "initialized", messageLog)

                // 6. configurationDone
                phase = "configurationDone"
                messageLog.add("→ sent: configurationDone request")
                DapTestUtils.sendConfigurationDoneRequest(output, seq = 3)

                // 7. Wait for 'terminated' event
                phase = "waiting for 'terminated' event"
                readEventAndLog(input, "terminated", messageLog, maxMessages = 500)

                // Success!
            } catch (e: Exception) {
                val log = messageLog.joinToString("\n  ", prefix = "  ")
                throw AssertionError(
                    "Failed during phase: $phase\n" +
                    "DAP message log (${messageLog.size} entries):\n$log\n" +
                    "codelldb stderr:\n  ${stderrBuf.toString().trim().takeLast(2000)}\n" +
                    "lldb-server output:\n  ${serverOutput.toString().trim().takeLast(2000)}", e
                )
            } finally {
                CodeLldbHarness.stopProcess(codelldbProcess)
            }
        } finally {
            serverProcess.destroy()
            serverProcess.waitFor(3, TimeUnit.SECONDS)
            if (serverProcess.isAlive) serverProcess.destroyForcibly()
        }
    }

    /** Sends a raw DAP-framed message. */
    private fun sendDapMessage(output: OutputStream, json: String) {
        val bytes = json.toByteArray(StandardCharsets.UTF_8)
        val header = "Content-Length: ${bytes.size}\r\n\r\n"
        output.write(header.toByteArray(StandardCharsets.UTF_8))
        output.write(bytes)
        output.flush()
    }

    /** Reads DAP messages until [eventType] is found. */
    private fun readEventAndLog(
        input: InputStream,
        eventType: String,
        log: MutableList<String>,
        maxMessages: Int = 50,
    ): String {
        repeat(maxMessages) {
            val message = DapTestUtils.readDapMessage(input)
            val json = JSONObject(message)
            val type = json.optString("type", "?")
            when (type) {
                "event" -> {
                    val event = json.optString("event")
                    if (event == eventType) {
                        log.add("← recv: event:$event (TARGET — found)")
                        return message
                    }
                    log.add("← recv: event:$event")
                }
                "response" -> {
                    val cmd = json.optString("command")
                    val success = json.optBoolean("success")
                    val msg = json.optString("message", "")
                    if (success) {
                        log.add("← recv: response:$cmd success=true")
                    } else {
                        log.add("← recv: response:$cmd success=false message=\"$msg\" body=${json.optJSONObject("body")}")
                    }
                }
                else -> log.add("← recv: $type")
            }
        }
        error("No '$eventType' event within $maxMessages messages")
    }
}

package com.github.jomof

import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

/**
 * Test harness for starting the KDAP adapter as a subprocess.
 * Mirrors [CodeLldbHarness]'s API so tests start both adapters the same way.
 *
 * Callers only pass transport arguments (e.g. `--port N`, `--connect N`, or
 * nothing for stdio) — exactly like CodeLLDB callers pass `--port N` or
 * nothing. The `--lldb-dap <path>` argument is appended automatically, just
 * as [CodeLldbHarness] resolves its extension root internally.
 */
object KdapHarness {

    private const val MAIN_CLASS = "com.github.jomof.MainKt"

    /**
     * Result of starting the KDAP adapter in TCP listen mode. Bundles the
     * process, the actual bound port (read from stderr), and a [StringBuilder]
     * that is continuously drained by a background thread. Callers should
     * use [stderrBuf] for diagnostics instead of reading `process.errorStream`
     * directly (it's already being consumed).
     */
    data class TcpAdapterResult(
        val process: Process,
        val port: Int,
        val stderrBuf: StringBuilder,
    )

    /**
     * Starts the KDAP adapter with [args] (e.g. empty for stdio, or "--port", port.toString()).
     * `--lldb-dap <path>` is appended automatically.
     */
    fun startAdapter(vararg args: String): Process {
        val javaHome = System.getProperty("java.home")
        val javaBin = File(File(javaHome, "bin"), "java").absolutePath
        val classpath = System.getProperty("java.class.path")
        val lldbDapPath = resolveLldbDapPath()
        val cmd = mutableListOf(javaBin, "-cp", classpath, MAIN_CLASS)
        cmd.addAll(args.toList())
        cmd.addAll(listOf("--lldb-dap", lldbDapPath))
        val processBuilder = ProcessBuilder(cmd).redirectErrorStream(false)
        processBuilder.environment().remove("TERM")
        return processBuilder.start()
    }

    fun stopProcess(process: Process) {
        process.destroy()
        process.waitFor(2, TimeUnit.SECONDS)
        if (process.isAlive) process.destroyForcibly()
    }

    /**
     * Regex matching the "Listening on port <N>" line printed to stderr by
     * KDAP when using `--port` (including `--port 0`).
     */
    private val LISTENING_REGEX = Regex("""Listening on port (\d+)""")

    /**
     * Starts the KDAP adapter in TCP listen mode using `--port 0` so the OS
     * assigns an available port. The actual bound port is read from the
     * process's stderr (`Listening on port <N>`), eliminating the TOCTOU
     * race that previously caused intermittent `BindException` failures.
     *
     * A background daemon thread continues draining stderr into [TcpAdapterResult.stderrBuf]
     * after the port is discovered, so callers must not start their own stderr reader.
     */
    fun startAdapterTcp(): TcpAdapterResult {
        val process = startAdapter("--port", "0")
        val stderrBuf = StringBuilder()
        val stderr = BufferedReader(InputStreamReader(process.errorStream))
        val port = readPortFromStderr(stderr, process, stderrBuf)
        // Continue draining remaining stderr in the background.
        kotlin.concurrent.thread(isDaemon = true, name = "kdap-stderr-drain") {
            try {
                stderr.lineSequence().forEach { stderrBuf.appendLine(it) }
            } catch (_: Exception) { /* process closed */ }
        }
        return TcpAdapterResult(process, port, stderrBuf)
    }

    /**
     * Reads lines from [stderr] until the "Listening on port <N>" message
     * appears, or the process dies. Lines read are appended to [buf] for
     * diagnostics. Throws if the port cannot be determined.
     */
    private fun readPortFromStderr(stderr: BufferedReader, process: Process, buf: StringBuilder): Int {
        val deadline = System.currentTimeMillis() + PORT_DISCOVERY_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            if (!process.isAlive) {
                error("KDAP process exited with code ${process.exitValue()} before reporting port")
            }
            // Use ready() to avoid blocking indefinitely if the process
            // doesn't print anything.
            if (stderr.ready()) {
                val line = stderr.readLine() ?: break
                buf.appendLine(line)
                val match = LISTENING_REGEX.find(line)
                if (match != null) {
                    return match.groupValues[1].toInt()
                }
            } else {
                Thread.sleep(20)
            }
        }
        if (process.isAlive) process.destroyForcibly()
        error("KDAP process did not report 'Listening on port <N>' within ${PORT_DISCOVERY_TIMEOUT_MS}ms")
    }

    /** How long to wait for the server to report its port. */
    private const val PORT_DISCOVERY_TIMEOUT_MS = 10_000L

    private fun resolveLldbDapPath(): String {
        val path = LldbDapHarness.resolveLldbDapPath()
            ?: error("lldb-dap not found (run scripts/download-lldb.sh or set KDAP_LLDB_ROOT)")
        return path.absolutePath
    }
}

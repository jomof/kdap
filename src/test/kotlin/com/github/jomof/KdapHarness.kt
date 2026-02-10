package com.github.jomof

import java.io.File
import java.net.ServerSocket
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
     * Starts the KDAP adapter in TCP listen mode (--port N).
     *
     * Retries up to [MAX_PORT_RETRIES] times with different ports to avoid
     * TOCTOU races where the port is grabbed between discovery and bind.
     * After starting, waits briefly and verifies the process is still alive.
     * KDAP is a JVM process so startup is slower than native CodeLLDB, but
     * a BindException exits quickly via the [Transport.TcpListen] retry logic.
     */
    fun startAdapterTcp(): Pair<Process, Int> {
        repeat(MAX_PORT_RETRIES) {
            val port = ServerSocket(0).use { it.localPort }
            val process = startAdapter("--port", port.toString())
            // JVM processes take longer to start; the bind-retry in
            // Transport.TcpListen gives the server ~2s to bind before
            // giving up, so we don't need a long check here — just
            // enough for a fast BindException to propagate.
            Thread.sleep(PORT_BIND_CHECK_MS)
            if (process.isAlive) return process to port
            // Process died — likely port conflict. Clean up and retry.
            process.destroyForcibly()
        }
        error("Failed to start KDAP adapter in TCP mode after $MAX_PORT_RETRIES attempts (port conflicts)")
    }

    private const val MAX_PORT_RETRIES = 3
    private const val PORT_BIND_CHECK_MS = 500L

    private fun resolveLldbDapPath(): String {
        val path = LldbDapHarness.resolveLldbDapPath()
            ?: error("lldb-dap not found (run scripts/download-lldb.sh or set KDAP_LLDB_ROOT)")
        return path.absolutePath
    }
}

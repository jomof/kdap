package com.github.jomof

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
        val javaBin = "$javaHome/bin/java"
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
     * Picks a free port and returns (process, port).
     */
    fun startAdapterTcp(): Pair<Process, Int> {
        val port = ServerSocket(0).use { it.localPort }
        val process = startAdapter("--port", port.toString())
        return process to port
    }

    private fun resolveLldbDapPath(): String {
        val path = LldbDapHarness.resolveLldbDapPath()
            ?: error("lldb-dap not found (run scripts/download-lldb.sh or set KDAP_LLDB_ROOT)")
        return path.absolutePath
    }
}

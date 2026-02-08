package com.github.jomof

import java.net.Socket
import java.util.concurrent.TimeUnit

/**
 * Test harness for starting and stopping the DAP server process and connecting to it.
 * Reusable across tests that run the server as a subprocess (stdio or TCP).
 */
object DapProcessHarness {

    private const val MAIN_CLASS = "com.github.jomof.MainKt"

    /**
     * Starts the DAP server process with the given [extraArgs] (e.g. `--port`, `1234`).
     */
    fun startProcess(vararg extraArgs: String): Process {
        val javaHome = System.getProperty("java.home")
        val javaBin = "$javaHome/bin/java"
        val classpath = System.getProperty("java.class.path")
        val cmd = mutableListOf(javaBin, "-cp", classpath, MAIN_CLASS)
        cmd.addAll(extraArgs)
        val processBuilder = ProcessBuilder(cmd).redirectErrorStream(false)
        processBuilder.environment().remove("TERM")
        return processBuilder.start()
    }

    /**
     * Stops the process, waiting up to 2 seconds before forcing.
     */
    fun stopProcess(process: Process) {
        process.destroy()
        process.waitFor(2, TimeUnit.SECONDS)
        if (process.isAlive) process.destroyForcibly()
    }

    /**
     * Connects to [port] on localhost, retrying until success or [timeoutMs].
     * Use the returned socket for the test (do not close and reconnect elsewhere).
     */
    fun connectToPort(port: Int, timeoutMs: Long = 5000): Socket {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            try {
                return Socket("127.0.0.1", port)
            } catch (_: Exception) {
                Thread.sleep(20)
            }
        }
        error("Port $port did not become reachable within ${timeoutMs}ms")
    }
}

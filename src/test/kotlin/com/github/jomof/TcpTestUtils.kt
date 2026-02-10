package com.github.jomof

import java.net.Socket

/**
 * TCP utilities shared across test harnesses (KDAP, CodeLLDB, lldb-dap).
 */
object TcpTestUtils {

    /**
     * Connects to [port] on localhost, retrying until success or [timeoutMs].
     * Use the returned socket for the test (do not close and reconnect elsewhere).
     *
     * If [expectedProcess] is provided, each retry checks that the process is
     * still alive. If it exits before a connection succeeds, the error is
     * reported immediately instead of waiting for timeout (which could connect
     * to a stale server left over from a previous test on the same port).
     */
    fun connectToPort(
        port: Int,
        timeoutMs: Long = 5000,
        expectedProcess: Process? = null,
    ): Socket {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (expectedProcess != null && !expectedProcess.isAlive) {
                error(
                    "Server process exited with code ${expectedProcess.exitValue()} " +
                    "before accepting connections on port $port"
                )
            }
            try {
                return Socket("127.0.0.1", port)
            } catch (_: Exception) {
                Thread.sleep(20)
            }
        }
        error("Port $port did not become reachable within ${timeoutMs}ms")
    }
}

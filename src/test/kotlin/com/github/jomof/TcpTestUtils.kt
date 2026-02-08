package com.github.jomof

import java.net.Socket

/**
 * TCP utilities shared across test harnesses (KDAP, CodeLLDB, lldb-dap).
 */
object TcpTestUtils {

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

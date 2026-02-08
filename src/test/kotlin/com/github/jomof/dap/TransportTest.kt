package com.github.jomof.dap

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread

class TransportTest {

    @Test
    fun `Stdio run invokes block with System in and out`() {
        Transport.Stdio.run { input, output ->
            assertSame(System.`in`, input)
            assertSame(System.out, output)
        }
    }

    @Test
    fun `TcpListen run invokes block with accepted socket streams`() {
        val freePort = ServerSocket(0).use { it.localPort }
        val blockRan = CountDownLatch(1)
        val received = ByteArray(2)
        val serverThread = thread {
            Transport.TcpListen(freePort).run { input, output ->
                output.write("ok".toByteArray())
                output.flush()
                blockRan.countDown()
            }
        }
        val client = connectWithRetry("127.0.0.1", freePort)
        client.use {
            it.soTimeout = 5000
            it.getInputStream().read(received)
        }
        blockRan.await()
        serverThread.join(2000)
        assertArrayEquals("ok".toByteArray(), received)
    }

    private fun connectWithRetry(host: String, port: Int, timeoutMs: Long = 5000): Socket {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            try {
                return Socket(host, port)
            } catch (_: Exception) {
                Thread.sleep(20)
            }
        }
        error("Could not connect to $host:$port within ${timeoutMs}ms")
    }

    @Test
    fun `TcpConnect run invokes block with connected socket streams`() {
        val server = ServerSocket(0)
        val port = server.localPort
        val blockRan = CountDownLatch(1)
        val clientThread = thread {
            Transport.TcpConnect("127.0.0.1", port).run { input, output ->
                val received = ByteArray(2)
                input.read(received)
                blockRan.countDown()
                assertArrayEquals("ok".toByteArray(), received)
            }
        }
        server.accept().use { clientSocket ->
            clientSocket.getOutputStream().write("ok".toByteArray())
            clientSocket.getOutputStream().flush()
        }
        blockRan.await()
        clientThread.join(2000)
        server.close()
    }
}

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
        val portReady = CountDownLatch(1)
        var boundPort = 0
        val blockRan = CountDownLatch(1)
        val received = ByteArray(2)
        val serverThread = thread {
            Transport.TcpListen(0, onBound = { port ->
                boundPort = port
                portReady.countDown()
            }).run { _, output ->
                output.write("ok".toByteArray())
                output.flush()
                blockRan.countDown()
            }
        }
        portReady.await()
        val client = Socket("127.0.0.1", boundPort)
        client.use {
            it.soTimeout = 5000
            it.getInputStream().read(received)
        }
        blockRan.await()
        serverThread.join(2000)
        assertArrayEquals("ok".toByteArray(), received)
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

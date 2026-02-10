package com.github.jomof

import com.github.jomof.dap.DapFraming
import com.github.jomof.dap.DapSession
import com.github.jomof.dap.DapSession.RequestAction
import com.github.jomof.dap.messages.*
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.concurrent.TimeUnit

/**
 * Tests for [DapSession], the concurrent message router that sits between
 * a client and a backend (e.g., lldb-dap). All tests use piped streams —
 * no real subprocess is needed, so they are fast and deterministic.
 *
 * Each test creates a [TestPipes] harness that wires four piped-stream
 * pairs into a [DapSession]:
 *
 * ```
 * test (as client) ──clientOut──▶ session ──backendIn──▶ test (as backend)
 * test (as client) ◀──clientIn── session ◀──backendOut── test (as backend)
 * ```
 */
@Timeout(10, unit = TimeUnit.SECONDS)
class DapSessionTest {

    // ── Helpers ──────────────────────────────────────────────────────────

    /**
     * Wired piped streams connecting test code (acting as both client and
     * backend) to a [DapSession] under test.
     */
    private class TestPipes(
        interceptor: DapSession.Interceptor = DapSession.Interceptor.PASS_THROUGH,
    ) : AutoCloseable {
        // Client → Session
        private val clientWritePipe = PipedOutputStream()
        private val sessionReadClient = PipedInputStream(clientWritePipe)

        // Session → Client
        private val sessionWriteClient = PipedOutputStream()
        private val clientReadPipe = PipedInputStream(sessionWriteClient)

        // Backend → Session
        private val backendWritePipe = PipedOutputStream()
        private val sessionReadBackend = PipedInputStream(backendWritePipe)

        // Session → Backend
        private val sessionWriteBackend = PipedOutputStream()
        private val backendReadPipe = PipedInputStream(sessionWriteBackend)

        /** Test writes here, pretending to be the client. */
        val clientOut: PipedOutputStream = clientWritePipe

        /** Test reads here, pretending to be the client. */
        val clientIn: PipedInputStream = clientReadPipe

        /** Test writes here, pretending to be the backend. */
        val backendOut: PipedOutputStream = backendWritePipe

        /** Test reads here, pretending to be the backend. */
        val backendIn: PipedInputStream = backendReadPipe

        val session = DapSession(
            clientInput = sessionReadClient,
            clientOutput = sessionWriteClient,
            backendInput = sessionReadBackend,
            backendOutput = sessionWriteBackend,
            interceptor = interceptor,
        )

        override fun close() {
            listOf(
                clientWritePipe, sessionWriteClient,
                backendWritePipe, sessionWriteBackend,
                clientReadPipe, sessionReadClient,
                backendReadPipe, sessionReadBackend,
            ).forEach { runCatching { it.close() } }
        }
    }

    /** Minimal DAP request JSON. */
    private fun dapRequest(seq: Int, command: String): String =
        """{"type":"request","seq":$seq,"command":"$command","arguments":{}}"""

    /** Minimal DAP response JSON. */
    private fun dapResponse(requestSeq: Int, command: String): String =
        """{"type":"response","request_seq":$requestSeq,"seq":0,"command":"$command","success":true,"body":{}}"""

    /** Minimal DAP event JSON. */
    private fun dapEvent(event: String, seq: Int = 0): String =
        """{"type":"event","seq":$seq,"event":"$event"}"""

    /** Reads one DAP message from [input] on the IO dispatcher. */
    private suspend fun readMessage(input: PipedInputStream): String? =
        withContext(Dispatchers.IO) { DapFraming.readMessage(input) }

    /** Shuts down a test session cleanly by closing both stream endpoints. */
    private suspend fun shutdownAndJoin(pipes: TestPipes, job: Job) {
        pipes.clientOut.close()
        pipes.backendOut.close()
        job.join()
    }

    /**
     * Asserts that two JSON strings are semantically equivalent (same keys
     * and values, regardless of key order). Used for interceptor-generated
     * messages where serialization order may differ from test helpers.
     */
    private fun assertJsonEquals(expected: String, actual: String?, message: String) {
        assertNotNull(actual, message)
        val expectedObj = JSONObject(expected)
        val actualObj = JSONObject(actual!!)
        assertTrue(expectedObj.similar(actualObj), "$message\nExpected: $expected\nActual:   $actual")
    }

    // ── Pass-through tests ───────────────────────────────────────────────

    @Test
    fun `forwards client request to backend`() = runBlocking {
        TestPipes().use { pipes ->
            val job = launch { pipes.session.run() }

            val request = dapRequest(1, "initialize")
            DapFraming.writeMessage(pipes.clientOut, request)

            val received = readMessage(pipes.backendIn)
            assertEquals(request, received, "Backend should receive the exact request from the client")

            shutdownAndJoin(pipes, job)
        }
    }

    @Test
    fun `forwards backend response to client`() = runBlocking {
        TestPipes().use { pipes ->
            val job = launch { pipes.session.run() }

            // Client sends request → forwarded to backend
            DapFraming.writeMessage(pipes.clientOut, dapRequest(1, "initialize"))
            readMessage(pipes.backendIn) // consume on backend side

            // Backend sends response → forwarded to client
            val response = dapResponse(1, "initialize")
            DapFraming.writeMessage(pipes.backendOut, response)

            val received = readMessage(pipes.clientIn)
            assertEquals(response, received, "Client should receive the exact response from the backend")

            shutdownAndJoin(pipes, job)
        }
    }

    @Test
    fun `forwards backend event to client`() = runBlocking {
        TestPipes().use { pipes ->
            val job = launch { pipes.session.run() }

            // Backend sends an event (no prior request needed)
            val event = dapEvent("stopped")
            DapFraming.writeMessage(pipes.backendOut, event)

            val received = readMessage(pipes.clientIn)
            assertEquals(event, received, "Client should receive the event from the backend")

            shutdownAndJoin(pipes, job)
        }
    }

    // ── Ordering test ────────────────────────────────────────────────────

    @Test
    fun `preserves message order from backend`() = runBlocking {
        TestPipes().use { pipes ->
            val job = launch { pipes.session.run() }

            // Client sends request
            DapFraming.writeMessage(pipes.clientOut, dapRequest(1, "continue"))
            readMessage(pipes.backendIn) // consume on backend side

            // Backend sends event, then response
            val event = dapEvent("output", seq = 1)
            val response = dapResponse(1, "continue")
            DapFraming.writeMessage(pipes.backendOut, event)
            DapFraming.writeMessage(pipes.backendOut, response)

            val first = readMessage(pipes.clientIn)
            val second = readMessage(pipes.clientIn)
            assertEquals(event, first, "Event should arrive before the response")
            assertEquals(response, second, "Response should arrive after the event")

            shutdownAndJoin(pipes, job)
        }
    }

    // ── Multiple cycles ──────────────────────────────────────────────────

    @Test
    fun `handles multiple sequential request-response cycles`() = runBlocking {
        TestPipes().use { pipes ->
            val job = launch { pipes.session.run() }

            repeat(5) { i ->
                val seq = i + 1
                val command = "cmd$seq"
                val request = dapRequest(seq, command)
                val response = dapResponse(seq, command)

                DapFraming.writeMessage(pipes.clientOut, request)
                val backendReceived = readMessage(pipes.backendIn)
                assertEquals(request, backendReceived, "Backend should receive request #$seq")

                DapFraming.writeMessage(pipes.backendOut, response)
                val clientReceived = readMessage(pipes.clientIn)
                assertEquals(response, clientReceived, "Client should receive response #$seq")
            }

            shutdownAndJoin(pipes, job)
        }
    }

    // ── Interceptor tests ────────────────────────────────────────────────

    @Test
    fun `interceptor handles request locally without forwarding`() = runBlocking {
        val localResponse = DapResponse(
            seq = 0, requestSeq = 1, command = "initialize",
            success = true, body = emptyMap(),
        )
        val interceptor = DapSession.Interceptor { RequestAction.Respond(localResponse) }

        TestPipes(interceptor).use { pipes ->
            val job = launch { pipes.session.run() }

            // Client sends request → interceptor handles it locally
            DapFraming.writeMessage(pipes.clientOut, dapRequest(1, "initialize"))

            // Client should receive the interceptor's response
            val received = readMessage(pipes.clientIn)
            assertJsonEquals(
                dapResponse(1, "initialize"), received,
                "Client should receive the interceptor's local response"
            )

            // Shut down and verify nothing was forwarded to backend
            shutdownAndJoin(pipes, job)
            assertEquals(
                0, pipes.backendIn.available(),
                "No messages should have been forwarded to the backend"
            )
        }
    }

    @Test
    fun `interceptor mixes local and forwarded requests`() = runBlocking {
        val interceptor = DapSession.Interceptor { request ->
            // Handle "initialize" locally; forward everything else
            if (request is InitializeRequest) {
                RequestAction.Respond(
                    DapResponse(
                        seq = 0, requestSeq = request.seq, command = "initialize",
                        success = true, body = emptyMap(),
                    )
                )
            } else {
                RequestAction.Forward
            }
        }

        TestPipes(interceptor).use { pipes ->
            val job = launch { pipes.session.run() }

            // First: initialize → handled locally
            DapFraming.writeMessage(pipes.clientOut, dapRequest(1, "initialize"))
            val localReply = readMessage(pipes.clientIn)
            assertJsonEquals(
                dapResponse(1, "initialize"), localReply,
                "Initialize should be handled locally by the interceptor"
            )

            // Second: launch → forwarded to backend
            val launchRequest = dapRequest(2, "launch")
            DapFraming.writeMessage(pipes.clientOut, launchRequest)
            val forwarded = readMessage(pipes.backendIn)
            assertEquals(launchRequest, forwarded, "Launch request should be forwarded to backend")

            // Backend responds to launch → forwarded to client
            val launchResponse = dapResponse(2, "launch")
            DapFraming.writeMessage(pipes.backendOut, launchResponse)
            val clientReply = readMessage(pipes.clientIn)
            assertEquals(launchResponse, clientReply, "Client should receive backend's launch response")

            shutdownAndJoin(pipes, job)
        }
    }

    @Test
    fun `interceptor transforms request before forwarding`() = runBlocking {
        val interceptor = DapSession.Interceptor { request ->
            // Rewrite unknown "foo" command to "bar" before forwarding
            if (request is UnknownRequest && request.command == "foo") {
                RequestAction.ForwardModified(UnknownRequest(request.seq, "bar"))
            } else {
                RequestAction.Forward
            }
        }

        TestPipes(interceptor).use { pipes ->
            val job = launch { pipes.session.run() }

            // Client sends "foo" command
            DapFraming.writeMessage(pipes.clientOut, dapRequest(1, "foo"))

            // Backend should receive it rewritten as "bar"
            val received = readMessage(pipes.backendIn)
            assertNotNull(received, "Backend should receive a message")
            val receivedJson = JSONObject(received!!)
            assertEquals("bar", receivedJson.getString("command"),
                "Backend should receive the transformed request with command 'bar'")
            assertEquals(1, receivedJson.getInt("seq"),
                "Seq should be preserved in the transformed request")

            shutdownAndJoin(pipes, job)
        }
    }

    // ── Disconnect tests ─────────────────────────────────────────────────

    @Test
    fun `terminates when client disconnects`() = runBlocking {
        TestPipes().use { pipes ->
            val job = launch { pipes.session.run() }

            // Client disconnects (close write end → session's clientReader gets EOF)
            pipes.clientOut.close()

            // Session should terminate without hanging
            withTimeout(5_000) { job.join() }
        }
    }

    @Test
    fun `terminates when backend disconnects`() = runBlocking {
        TestPipes().use { pipes ->
            val job = launch { pipes.session.run() }

            // Backend disconnects (close write end → session's backendReader gets EOF)
            pipes.backendOut.close()

            // Session should terminate without hanging
            withTimeout(5_000) { job.join() }
        }
    }
}

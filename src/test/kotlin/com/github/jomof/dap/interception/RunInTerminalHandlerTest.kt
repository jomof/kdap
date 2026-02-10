package com.github.jomof.dap.interception

import com.github.jomof.dap.DapSession.AsyncRequestContext
import com.github.jomof.dap.DapSession.RequestAction
import com.github.jomof.dap.messages.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Unit tests for [RunInTerminalHandler].
 *
 * These tests verify the handler's decision-making (when to intercept vs.
 * forward), and the async flow including TCP handshake and stdio injection.
 * A fake TTY path is used so no real terminal is needed.
 */
@Timeout(15, unit = TimeUnit.SECONDS)
class RunInTerminalHandlerTest {

    // ── Decision tests ───────────────────────────────────────────────────

    @Test
    fun `forwards console launch unchanged`() {
        val handler = RunInTerminalHandler()
        handler.onRequest(InitializeRequest(seq = 1, supportsRunInTerminalRequest = true))
        val action = handler.onRequest(LaunchRequest(seq = 2, program = "/test/prog"))
        assertTrue(action is RequestAction.Forward, "Console launch should be forwarded")
    }

    @Test
    fun `forwards terminal launch when client does not support runInTerminal`() {
        val handler = RunInTerminalHandler()
        handler.onRequest(InitializeRequest(seq = 1, supportsRunInTerminalRequest = false))
        val action = handler.onRequest(LaunchRequest(seq = 2, program = "/test/prog", terminal = "integrated"))
        assertTrue(action is RequestAction.Forward,
            "Terminal launch should be forwarded when client doesn't support runInTerminal")
    }

    @Test
    fun `intercepts integrated terminal launch as HandleAsync`() {
        val handler = RunInTerminalHandler()
        handler.onRequest(InitializeRequest(seq = 1, supportsRunInTerminalRequest = true))
        val action = handler.onRequest(LaunchRequest(seq = 2, program = "/test/prog", terminal = "integrated"))
        assertTrue(action is RequestAction.HandleAsync,
            "Integrated terminal launch should return HandleAsync")
    }

    @Test
    fun `intercepts external terminal launch as HandleAsync`() {
        val handler = RunInTerminalHandler()
        handler.onRequest(InitializeRequest(seq = 1, supportsRunInTerminalRequest = true))
        val action = handler.onRequest(LaunchRequest(seq = 2, program = "/test/prog", terminal = "external"))
        assertTrue(action is RequestAction.HandleAsync,
            "External terminal launch should return HandleAsync")
    }

    // ── Async flow tests ─────────────────────────────────────────────────

    /**
     * Runs a [RunInTerminalHandler] async block end-to-end, simulating the
     * TCP handshake from `kdap-launch` with the given [ttyValue].
     *
     * The tricky part: the handler's flow is sequential:
     * ```
     * sendReverseRequest → awaitResponse → serverSocket.accept → read → write → forward
     * ```
     * The TCP client (simulating `kdap-launch`) must run concurrently. We
     * launch it in a separate coroutine, signaled by [reverseRequestReady]
     * when the port is known.
     *
     * @return the JSON string forwarded to the backend by the handler
     */
    private fun runAsyncHandlerFlow(
        terminal: String,
        ttyValue: Any, // String path or JSONObject.NULL
    ): Pair<String?, String?> = runBlocking {
        val handler = RunInTerminalHandler()
        handler.onRequest(InitializeRequest(seq = 1, supportsRunInTerminalRequest = true))

        val rawJson = """{"type":"request","seq":2,"command":"launch","arguments":{"program":"/test/prog","terminal":"$terminal","name":"test"}}"""
        val action = handler.onRequest(LaunchRequest(seq = 2, program = "/test/prog", terminal = terminal))
        assertTrue(action is RequestAction.HandleAsync)

        val forwardedJson = AtomicReference<String>()
        val reverseRequestJson = AtomicReference<String>()
        val reverseRequestReady = CompletableDeferred<Unit>()

        val asyncBlock = (action as RequestAction.HandleAsync).block

        // Launch the TCP client in a separate coroutine. It waits until
        // the reverse request is available (which means the server socket
        // is already listening), then connects and performs the handshake.
        val tcpClientJob = launch(Dispatchers.IO) {
            reverseRequestReady.await()
            val reverseReq = JSONObject(reverseRequestJson.get())
            val args = reverseReq.getJSONObject("arguments").getJSONArray("args")
            val connectArg = (0 until args.length())
                .map { args.getString(it) }
                .first { it.startsWith("--connect=") }
            val address = connectArg.removePrefix("--connect=")
            val lastColon = address.lastIndexOf(':')
            val port = address.substring(lastColon + 1).toInt()

            val socket = Socket("127.0.0.1", port)
            val ttyJson = JSONObject().apply { put("tty", ttyValue) }
            socket.getOutputStream().write(
                ttyJson.toString().toByteArray(StandardCharsets.UTF_8)
            )
            socket.getOutputStream().flush()
            socket.shutdownOutput()
            // Read the success response from the handler
            socket.getInputStream().bufferedReader(StandardCharsets.UTF_8).readText()
            socket.close()
        }

        // Run the handler's async block with a simple context.
        // sendReverseRequest stores the JSON and signals the TCP client.
        // awaitResponse returns immediately (the TCP client runs concurrently).
        asyncBlock(rawJson, object : AsyncRequestContext {
            override suspend fun sendReverseRequest(json: String): Int {
                reverseRequestJson.set(json)
                reverseRequestReady.complete(Unit)
                return 999
            }

            override suspend fun awaitResponse(requestSeq: Int): DapResponse {
                return DapResponse(
                    seq = 0, requestSeq = requestSeq,
                    command = "runInTerminal", success = true,
                )
            }

            override suspend fun forwardToBackend(json: String) {
                forwardedJson.set(json)
            }
        })

        tcpClientJob.join()
        Pair(forwardedJson.get(), reverseRequestJson.get())
    }

    @Test
    fun `async handler injects stdio when TTY is available`() {
        val (forwardedJsonStr, _) = runAsyncHandlerFlow("integrated", "/dev/fake/tty")

        assertNotNull(forwardedJsonStr, "Handler should forward the modified launch request")
        val forwarded = JSONObject(forwardedJsonStr!!)
        val args = forwarded.getJSONObject("arguments")
        assertTrue(args.has("stdio"), "Modified launch should have stdio field")
        val stdio = args.getJSONArray("stdio")
        assertEquals(3, stdio.length(), "stdio should have 3 elements (in, out, err)")
        assertEquals("/dev/fake/tty", stdio.getString(0))
        assertEquals("/dev/fake/tty", stdio.getString(1))
        assertEquals("/dev/fake/tty", stdio.getString(2))
        assertFalse(args.has("terminal"), "terminal key should be stripped")
        assertEquals("/test/prog", args.getString("program"), "program should be preserved")
    }

    @Test
    fun `async handler skips stdio when TTY is null`() {
        val (forwardedJsonStr, _) = runAsyncHandlerFlow("external", JSONObject.NULL)

        assertNotNull(forwardedJsonStr, "Handler should forward the launch request")
        val forwarded = JSONObject(forwardedJsonStr!!)
        val args = forwarded.getJSONObject("arguments")
        assertFalse(args.has("stdio"), "No stdio should be injected when TTY is null")
        assertFalse(args.has("terminal"), "terminal key should be stripped")
        assertEquals("/test/prog", args.getString("program"), "program should be preserved")
    }

    @Test
    fun `reverse request has correct kind and args structure`() {
        val (_, reverseRequestJsonStr) = runAsyncHandlerFlow("integrated", JSONObject.NULL)

        assertNotNull(reverseRequestJsonStr)
        val req = JSONObject(reverseRequestJsonStr!!)
        assertEquals("request", req.getString("type"))
        assertEquals("runInTerminal", req.getString("command"))

        val arguments = req.getJSONObject("arguments")
        assertEquals("integrated", arguments.getString("kind"))
        assertEquals("KDAP Debug", arguments.getString("title"))

        val args = arguments.getJSONArray("args")
        assertTrue(args.length() >= 4, "args should have at least 4 elements")
        val lastArg = args.getString(args.length() - 1)
        assertTrue(lastArg.startsWith("--connect="), "Last arg should be --connect=HOST:PORT")
        val allArgs = (0 until args.length()).map { args.getString(it) }
        assertTrue(allArgs.any { it.contains("KdapLaunchKt") },
            "args should reference KdapLaunchKt: $allArgs")
    }
}

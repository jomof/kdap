package com.github.jomof.dap.interception

import com.github.jomof.dap.DapSession.AsyncRequestContext
import com.github.jomof.dap.DapSession.RequestAction
import com.github.jomof.dap.messages.*
import org.json.JSONArray
import org.json.JSONObject
import java.net.InetAddress
import java.net.ServerSocket
import java.nio.charset.StandardCharsets
import java.util.logging.Logger

/**
 * Intercepts `launch` requests with `terminal = "integrated"` or `"external"`
 * and orchestrates the `runInTerminal` reverse request flow.
 *
 * ## Flow
 *
 * 1. **Observe `initialize`**: extracts `supportsRunInTerminalRequest` from
 *    the client's capabilities. If not supported, all launch requests pass through.
 * 2. **Intercept `launch`**: if the typed [LaunchRequest.terminal] is
 *    `"integrated"` or `"external"` AND client supports `runInTerminal`,
 *    returns [RequestAction.HandleAsync].
 * 3. **Async block**:
 *    - Binds a TCP listener on a random port
 *    - Locates `kdap-launch` via own classpath and `java.home`
 *    - Sends a `runInTerminal` reverse request to the client with
 *      `args: [java, -cp, classpath, com.github.jomof.KdapLaunchKt, --connect=HOST:PORT]`
 *    - Waits for the client's response
 *    - Accepts TCP connection from `kdap-launch`, reads TTY info JSON
 *    - Sends `{"success": true}` back to `kdap-launch`
 *    - If TTY is available, injects `"stdio": [tty, tty, tty]` into the
 *      launch JSON
 *    - Strips the `terminal` key and forwards to the backend
 *
 * ## Thread safety
 *
 * [onRequest] is called from the client-reader coroutine. The async block
 * runs in its own coroutine. State is managed via `@Volatile` fields.
 */
class RunInTerminalHandler : InterceptionHandler {

    @Volatile
    private var clientSupportsRunInTerminal = false

    override fun onRequest(request: DapRequest): RequestAction {
        // Observe initialize to capture client capabilities
        if (request is InitializeRequest) {
            clientSupportsRunInTerminal = request.supportsRunInTerminalRequest
            return RequestAction.Forward
        }

        if (request is LaunchRequest) {
            val terminal = request.terminal
            if (terminal != null && (terminal == "integrated" || terminal == "external")
                && clientSupportsRunInTerminal
            ) {
                return RequestAction.HandleAsync { rawJson, ctx ->
                    handleTerminalLaunch(rawJson, ctx, terminal)
                }
            }
        }

        return RequestAction.Forward
    }

    private suspend fun handleTerminalLaunch(
        rawJson: String,
        ctx: AsyncRequestContext,
        terminalKind: String,
    ) {
        // 1. Bind TCP listener for kdap-launch to connect to
        val serverSocket = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        serverSocket.soTimeout = TCP_ACCEPT_TIMEOUT_MS
        val port = serverSocket.localPort
        val host = "127.0.0.1"

        try {
            // 2. Build runInTerminal reverse request
            val javaHome = System.getProperty("java.home")
            val javaBin = "$javaHome/bin/java"
            val classpath = System.getProperty("java.class.path")

            val runInTerminalArgs = JSONArray().apply {
                put(javaBin)
                put("-cp")
                put(classpath)
                put("com.github.jomof.KdapLaunchKt")
                put("--connect=$host:$port")
            }

            val reverseRequest = JSONObject().apply {
                put("type", "request")
                put("command", "runInTerminal")
                put("arguments", JSONObject().apply {
                    put("args", runInTerminalArgs)
                    put("kind", terminalKind)
                    put("title", "KDAP Debug")
                })
            }

            // 3. Send reverse request to client and wait for response
            val seq = ctx.sendReverseRequest(reverseRequest.toString())
            val response = ctx.awaitResponse(seq)

            if (!response.success) {
                log.warning { "RunInTerminalHandler: client rejected runInTerminal: ${response.message}" }
                // Fall through to forward without modification
                ctx.forwardToBackend(rawJson)
                return
            }

            // 4. Accept TCP connection from kdap-launch
            val ttyPath: String? = serverSocket.use { ss ->
                val socket = ss.accept()
                socket.use { s ->
                    // Read terminal info JSON from kdap-launch
                    val input = s.getInputStream()
                        .bufferedReader(StandardCharsets.UTF_8)
                        .readText()
                    val ttyJson = JSONObject(input)
                    val tty = if (ttyJson.isNull("tty")) null else ttyJson.optString("tty", null)

                    // Send success response
                    val successResponse = JSONObject().apply {
                        put("success", true)
                    }
                    s.getOutputStream().write(
                        successResponse.toString().toByteArray(StandardCharsets.UTF_8)
                    )
                    s.getOutputStream().flush()

                    tty
                }
            }

            // 5. Modify launch request and forward to backend
            val launchObj = JSONObject(rawJson)
            val launchArgs = launchObj.optJSONObject("arguments") ?: JSONObject()

            // Inject stdio if TTY is available
            if (ttyPath != null) {
                launchArgs.put("stdio", JSONArray().apply {
                    put(ttyPath)
                    put(ttyPath)
                    put(ttyPath)
                })
                log.info { "RunInTerminalHandler: injected stdio=$ttyPath" }
            } else {
                log.info { "RunInTerminalHandler: no TTY available, forwarding without stdio" }
            }

            // Remove terminal key (lldb-dap ignores it, but keep it clean)
            launchArgs.remove("terminal")
            launchObj.put("arguments", launchArgs)

            ctx.forwardToBackend(launchObj.toString())

        } catch (e: Exception) {
            log.warning { "RunInTerminalHandler: error during terminal launch: ${e.message}" }
            // On failure, forward the original request unchanged as a fallback
            ctx.forwardToBackend(rawJson)
        } finally {
            if (!serverSocket.isClosed) {
                serverSocket.close()
            }
        }
    }

    companion object {
        private val log = Logger.getLogger(RunInTerminalHandler::class.java.name)
        private const val TCP_ACCEPT_TIMEOUT_MS = 30_000
    }
}

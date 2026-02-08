package com.github.jomof.dap

import kotlinx.coroutines.runBlocking
import java.io.InputStream
import java.io.OutputStream

/**
 * DAP server: runs as a decorator in front of `lldb-dap`, forwarding messages
 * bidirectionally via [DapSession]. An optional [DapSession.Interceptor] can
 * handle specific requests locally or transform them before forwarding.
 *
 * Supports stdio, TCP listen, and TCP connect transports (same as CodeLLDB).
 */
object DapServer {

    /**
     * Runs the server in decorator mode on [transport], forwarding messages
     * to and from a backend (e.g., `lldb-dap`) via its [backendInput] and
     * [backendOutput] streams.
     *
     * The [interceptor] decides per-request whether to handle locally or
     * forward to the backend. By default, all requests are forwarded
     * (pure pass-through proxy).
     *
     * This method blocks until the session ends (either side disconnects).
     *
     * @param transport      the client-facing transport (stdio, TCP, etc.)
     * @param backendInput   stream to read DAP messages from the backend
     * @param backendOutput  stream to write DAP messages to the backend
     * @param interceptor    per-request handler
     */
    fun runDecorator(
        transport: Transport,
        backendInput: InputStream,
        backendOutput: OutputStream,
        interceptor: DapSession.Interceptor = DapSession.Interceptor.PASS_THROUGH,
    ) {
        transport.run { clientInput, clientOutput ->
            runDecorator(clientInput, clientOutput, backendInput, backendOutput, interceptor)
        }
    }

    /**
     * Runs the server in decorator mode over caller-managed streams.
     *
     * @param clientInput    stream to read DAP messages from the client
     * @param clientOutput   stream to write DAP messages to the client
     * @param backendInput   stream to read DAP messages from the backend
     * @param backendOutput  stream to write DAP messages to the backend
     * @param interceptor    per-request handler
     */
    fun runDecorator(
        clientInput: InputStream,
        clientOutput: OutputStream,
        backendInput: InputStream,
        backendOutput: OutputStream,
        interceptor: DapSession.Interceptor = DapSession.Interceptor.PASS_THROUGH,
    ) {
        val session = DapSession(
            clientInput = clientInput,
            clientOutput = clientOutput,
            backendInput = backendInput,
            backendOutput = backendOutput,
            interceptor = interceptor,
        )
        runBlocking {
            session.run()
        }
    }
}

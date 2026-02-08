package com.github.jomof.dap

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.selects.select
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.logging.Logger

/**
 * A concurrent DAP session that routes messages bidirectionally between a
 * client (IDE) and a backend (`lldb-dap`) using Kotlin coroutines.
 *
 * ## Message flow
 *
 * - Client requests are read by [clientReader], passed to the [interceptor],
 *   and either handled locally (response sent to client) or forwarded to
 *   the backend via the `toBackend` channel.
 * - Backend messages (responses and events) are read by [backendReader] and
 *   forwarded to the client via the `toClient` channel.
 * - Two dedicated writer coroutines ([clientWriter] and [backendWriter])
 *   are the sole writers to their respective output streams, eliminating
 *   the need for explicit locking.
 *
 * ## Shutdown
 *
 * The session terminates when either reader detects EOF (stream closed).
 * Writers drain any remaining buffered messages, then both output streams
 * are closed. Closing the backend's output (lldb-dap's stdin) causes it
 * to exit, which closes its stdout and unblocks the backend reader —
 * ensuring prompt shutdown even with streams that ignore thread interruption
 * (e.g., `Process.getInputStream()`).
 *
 * ## Thread safety
 *
 * All blocking I/O runs on [Dispatchers.IO]. Communication between
 * coroutines uses [Channel], so no shared mutable state exists.
 *
 * @param clientInput  stream to read DAP messages from the client
 * @param clientOutput stream to write DAP messages to the client
 * @param backendInput  stream to read DAP messages from the backend
 * @param backendOutput stream to write DAP messages to the backend
 * @param interceptor   decides per-request whether to handle locally or forward
 */
class DapSession(
    private val clientInput: InputStream,
    private val clientOutput: OutputStream,
    private val backendInput: InputStream,
    private val backendOutput: OutputStream,
    private val interceptor: Interceptor = Interceptor.PASS_THROUGH,
) {
    /**
     * The result of an [Interceptor] deciding what to do with a client request.
     */
    sealed class RequestAction {
        /** Forward the original request to the backend unchanged. */
        data object Forward : RequestAction()

        /** Send this response directly to the client; do not forward to the backend. */
        data class Respond(val response: String) : RequestAction()

        /** Forward a modified version of the request to the backend. */
        data class ForwardModified(val modifiedRequest: String) : RequestAction()
    }

    /**
     * Per-request decision point. Examines each client request and decides
     * whether to forward it, handle it locally, or transform it before
     * forwarding.
     */
    fun interface Interceptor {
        /**
         * Called for each DAP request read from the client.
         *
         * @param request the raw JSON body of the DAP request
         * @return a [RequestAction] indicating how to handle this request
         */
        fun onRequest(request: String): RequestAction

        companion object {
            /** Forwards every request to the backend without modification. */
            val PASS_THROUGH = Interceptor { RequestAction.Forward }
        }
    }

    /**
     * Runs the session until either the client or backend disconnects.
     *
     * This is a suspending function that returns normally when the session
     * ends. It does not throw on EOF; IOException from a closed stream
     * during write is caught and treated as a disconnect.
     */
    suspend fun run() = coroutineScope {
        val toClient = Channel<String>(CHANNEL_CAPACITY)
        val toBackend = Channel<String>(CHANNEL_CAPACITY)

        val clientWriterJob = launchWriter("clientWriter", toClient, clientOutput)
        val backendWriterJob = launchWriter("backendWriter", toBackend, backendOutput)

        val backendReaderJob = launchReader("backendReader", backendInput) { message ->
            toClient.send(message)
        }

        val clientReaderJob = launchReader("clientReader", clientInput) { message ->
            when (val action = interceptor.onRequest(message)) {
                is RequestAction.Forward -> toBackend.send(message)
                is RequestAction.Respond -> toClient.send(action.response)
                is RequestAction.ForwardModified -> toBackend.send(action.modifiedRequest)
            }
        }

        // When either reader finishes (EOF or error), the session is over.
        select {
            clientReaderJob.onJoin {}
            backendReaderJob.onJoin {}
        }

        // Close channels so writers drain remaining messages.
        toClient.close()
        toBackend.close()

        // Wait for writers to finish draining their channels.
        clientWriterJob.join()
        backendWriterJob.join()

        // Close all streams to ensure prompt shutdown. Output streams signal
        // the other side that the session is over; input streams unblock any
        // reader still waiting on data (e.g., Process.getInputStream() which
        // ignores thread interruption).
        closeQuietly(backendOutput, "backendOutput")
        closeQuietly(clientOutput, "clientOutput")
        closeQuietly(backendInput, "backendInput")
        closeQuietly(clientInput, "clientInput")

        // Cancel readers for structured-concurrency hygiene.
        clientReaderJob.cancel()
        backendReaderJob.cancel()
    }

    /**
     * Launches a coroutine that reads DAP messages from [input] and calls
     * [onMessage] for each one. Returns when the stream reaches EOF or an
     * I/O error occurs.
     *
     * Uses [runInterruptible] so that coroutine cancellation interrupts the
     * blocking [InputStream.read] call, allowing prompt shutdown when the
     * other side of the session disconnects.
     */
    private fun CoroutineScope.launchReader(
        name: String,
        input: InputStream,
        onMessage: suspend (String) -> Unit,
    ): Job = launch(Dispatchers.IO + CoroutineName(name)) {
        try {
            while (isActive) {
                val message = runInterruptible { DapFraming.readMessage(input) } ?: break
                onMessage(message)
            }
        } catch (e: IOException) {
            log.fine { "$name: stream closed (${e.message})" }
        } catch (_: CancellationException) {
            // Normal shutdown; coroutine was cancelled after the other
            // reader finished.
        }
        log.fine { "$name: exiting" }
    }

    /**
     * Launches a coroutine that drains [channel] and writes each message
     * to [output] using DAP framing. Exits when the channel is closed
     * and all buffered messages have been written.
     */
    private fun CoroutineScope.launchWriter(
        name: String,
        channel: Channel<String>,
        output: OutputStream,
    ): Job = launch(Dispatchers.IO + CoroutineName(name)) {
        try {
            for (message in channel) {
                DapFraming.writeMessage(output, message)
            }
        } catch (e: IOException) {
            log.fine { "$name: write failed (${e.message})" }
        } catch (_: CancellationException) {
            // Normal shutdown.
        }
        log.fine { "$name: exiting" }
    }

    /** Closes [stream] ignoring any IOException (stream may already be closed). */
    private fun closeQuietly(stream: OutputStream, name: String) {
        try {
            stream.close()
        } catch (e: IOException) {
            log.fine { "$name: close failed (${e.message})" }
        }
    }

    /** Closes [stream] ignoring any IOException (stream may already be closed). */
    private fun closeQuietly(stream: InputStream, name: String) {
        try {
            stream.close()
        } catch (e: IOException) {
            log.fine { "$name: close failed (${e.message})" }
        }
    }

    companion object {
        private val log = Logger.getLogger(DapSession::class.java.name)

        /**
         * Channel buffer capacity. Large enough to avoid back-pressure under
         * normal operation; small enough to bound memory if a writer stalls.
         */
        private const val CHANNEL_CAPACITY = 64
    }
}

package com.github.jomof.dap

import com.github.jomof.dap.messages.DapMessage
import com.github.jomof.dap.messages.DapRequest
import com.github.jomof.dap.messages.DapResponse
import com.github.jomof.dap.messages.OutputEvent
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.selects.select
import org.json.JSONObject
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.logging.Logger

/**
 * A concurrent DAP session that routes messages bidirectionally between a
 * client (IDE) and a backend (`lldb-dap`) using Kotlin coroutines.
 *
 * ## Message flow
 *
 * - Client requests are read by [clientReader], parsed into typed
 *   [DapRequest] objects, and passed to the [interceptor]. The interceptor
 *   decides whether to forward the original request, handle it locally
 *   (sending a response directly to the client), or forward a modified
 *   version.
 * - Backend messages (responses and events) are read by [backendReader],
 *   parsed into typed [DapMessage] objects, and passed to the interceptor
 *   which may inject additional messages before/after.
 * - Two dedicated writer coroutines ([clientWriter] and [backendWriter])
 *   are the sole writers to their respective output streams, eliminating
 *   the need for explicit locking.
 *
 * ## Raw JSON passthrough
 *
 * For forwarded messages, the original raw JSON string is sent to preserve
 * perfect wire fidelity (no re-serialization). Serialization via
 * [DapMessage.toJson] is only used for interceptor-created or modified
 * messages.
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
        data class Respond(val response: DapMessage) : RequestAction()

        /** Forward a modified version of the request to the backend. */
        data class ForwardModified(val modifiedRequest: DapRequest) : RequestAction()

        /**
         * Handle the request asynchronously in a separate coroutine.
         *
         * The [block] is launched as a child coroutine so the client reader
         * can continue processing messages (e.g., responses to reverse
         * requests sent by this handler). The block receives the original
         * raw JSON and an [AsyncRequestContext] for sending reverse requests
         * to the client, awaiting responses, and forwarding to the backend.
         */
        data class HandleAsync(
            val block: suspend (rawJson: String, ctx: AsyncRequestContext) -> Unit,
        ) : RequestAction()
    }

    /**
     * Context provided to [RequestAction.HandleAsync] blocks for interacting
     * with the DAP session's message channels.
     */
    interface AsyncRequestContext {
        /**
         * Sends a reverse request to the client, assigning a fresh sequence
         * number. Returns the assigned seq for use with [awaitResponse].
         */
        suspend fun sendReverseRequest(json: String): Int

        /**
         * Waits for the client to respond to a reverse request with the given
         * `request_seq`. The response is removed from the pending map.
         */
        suspend fun awaitResponse(requestSeq: Int): DapResponse

        /**
         * Forwards a (potentially modified) request JSON to the backend.
         */
        suspend fun forwardToBackend(json: String)

        /**
         * Sends a DAP event (or any message) directly to the client.
         *
         * Use this to inject events (e.g., `initialized`, `output`) from
         * within a [RequestAction.HandleAsync] block without going through
         * the backend.
         */
        suspend fun sendEventToClient(json: String)

        /**
         * Sends a DAP request to the backend and suspends until its response
         * arrives. Assigns a fresh sequence number to avoid collisions.
         *
         * The matching response is routed directly back here, bypassing the
         * interceptor's [Interceptor.onBackendMessage] chain.
         */
        suspend fun sendRequestToBackendAndAwait(json: String): DapResponse

        /**
         * Like [sendRequestToBackendAndAwait], but suppresses console `output`
         * events from the backend while waiting for the response.
         *
         * This is used by Python SB API calls (via `script` commands) that
         * produce auto-displayed output. Python's `PRINT_EXPR` writes to
         * `sys.stdout`, which LLDB routes both into the evaluate response's
         * `result` field **and** as separate DAP `output` events. The result
         * field is how we read return values; the output events are unwanted
         * noise that would pollute the client's event stream.
         */
        suspend fun sendSilentRequestToBackendAndAwait(json: String): DapResponse

        /**
         * Intercepts the next client request whose `command` matches
         * [command]. The request is consumed (not forwarded to the backend
         * or passed to [Interceptor.onRequest]).
         *
         * Returns the raw JSON of the intercepted request. Used to wait for
         * protocol milestones like `configurationDone`.
         */
        suspend fun interceptClientRequest(command: String): String

        /**
         * Activates the event gate. While active, backend messages that
         * have passed through the interceptor chain are buffered instead
         * of being sent to the client. This prevents backend-originated
         * events (e.g., process exit) from racing ahead of events sent
         * by the [RequestAction.HandleAsync] block.
         *
         * Call [releaseEventGate] to flush buffered messages to the client
         * in order.
         */
        fun activateEventGate()

        /**
         * Releases the event gate and flushes all buffered messages to
         * the client in the order they were received. After this call,
         * backend messages flow directly to the client again.
         */
        suspend fun releaseEventGate()
    }

    /**
     * Per-request decision point. Examines each client request and decides
     * whether to forward it, handle it locally, or transform it before
     * forwarding. Also observes backend messages (responses and events)
     * before they reach the client, with the ability to inject additional
     * messages.
     */
    fun interface Interceptor {
        /**
         * Called for each DAP request read from the client.
         *
         * @param request the parsed, typed DAP request
         * @return a [RequestAction] indicating how to handle this request
         */
        fun onRequest(request: DapRequest): RequestAction

        /**
         * Called for each DAP message (response or event) read from the
         * backend before it is sent to the client. Returns the list of
         * messages that should actually be sent to the client.
         *
         * The default implementation forwards the message unchanged. Override
         * to inject additional messages before or after the backend message,
         * filter messages, or transform them.
         *
         * Examples:
         * - `listOf(message)` — forward unchanged (default)
         * - `listOf(extra, message)` — inject a message before
         * - `listOf(message, extra)` — inject a message after
         * - `emptyList()` — suppress the message entirely
         *
         * @param message the parsed, typed DAP message from the backend
         * @return messages to send to the client, in order
         */
        fun onBackendMessage(message: DapMessage): List<DapMessage> = listOf(message)

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

        // Seq counter for reverse requests sent to the client.
        val reverseSeq = AtomicInteger(1_000_000)

        // Seq counter for requests sent to the backend by HandleAsync blocks.
        // Starts at 2,000,000 to avoid collisions with client-originated seqs.
        val backendSeq = AtomicInteger(2_000_000)

        // Pending reverse request responses: keyed by the seq we assigned
        // to the reverse request. The HandleAsync block awaits on these.
        val pendingReverseResponses = ConcurrentHashMap<Int, CompletableDeferred<DapResponse>>()

        // Pending backend responses: keyed by seq we assigned to a request
        // sent via sendRequestToBackendAndAwait. The backendReader routes
        // matching responses here, bypassing the interceptor chain.
        val pendingBackendResponses = ConcurrentHashMap<Int, CompletableDeferred<DapResponse>>()

        // Pending client request interceptions: keyed by command name.
        // When the clientReader sees a request matching a registered command,
        // it delivers the raw JSON here instead of processing it normally.
        val pendingClientInterceptions = ConcurrentHashMap<String, CompletableDeferred<String>>()

        // Counter for pending "silent" evaluate requests. When > 0, console
        // output events from the backend are suppressed (not forwarded to the
        // client). This prevents Python SB API calls from polluting the DAP
        // event stream with auto-display output.
        val pendingSilentRequests = AtomicInteger(0)

        // Set of seq numbers belonging to silent evaluate requests. Used by
        // the backend reader to know when to schedule a deferred decrement.
        val silentRequestSeqs = ConcurrentHashMap.newKeySet<Int>()

        // Number of decrements to apply to pendingSilentRequests AFTER the
        // backend reader finishes processing the current message. This ensures
        // the counter stays elevated through the output event that may trail
        // the evaluate response (race condition: deferred.complete() can
        // resume the awaiting coroutine before the backend reader processes
        // the next message).
        val deferredDecrements = AtomicInteger(0)

        // Event gate: when non-null, backend messages that have passed
        // through the interceptor chain are buffered here instead of
        // being sent to the client. This prevents process-exit events
        // from racing ahead of launch-sequence events sent by an async
        // handler. Responses routed to pending callers are NOT gated
        // (they are consumed before the gate check).
        val eventGate = AtomicReference<ConcurrentLinkedQueue<String>?>(null)

        val asyncCtx = object : AsyncRequestContext {
            override suspend fun sendReverseRequest(json: String): Int {
                val seq = reverseSeq.getAndIncrement()
                // Inject our assigned seq into the JSON
                val obj = JSONObject(json)
                obj.put("seq", seq)
                val deferred = CompletableDeferred<DapResponse>()
                pendingReverseResponses[seq] = deferred
                toClient.send(obj.toString())
                return seq
            }

            override suspend fun awaitResponse(requestSeq: Int): DapResponse {
                val deferred = pendingReverseResponses[requestSeq]
                    ?: throw IllegalStateException("No pending reverse request for seq $requestSeq")
                return deferred.await()
            }

            override suspend fun forwardToBackend(json: String) {
                toBackend.send(json)
            }

            override suspend fun sendEventToClient(json: String) {
                toClient.send(json)
            }

            override suspend fun sendRequestToBackendAndAwait(json: String): DapResponse {
                val seq = backendSeq.getAndIncrement()
                val obj = JSONObject(json)
                obj.put("seq", seq)
                val deferred = CompletableDeferred<DapResponse>()
                pendingBackendResponses[seq] = deferred
                toBackend.send(obj.toString())
                return deferred.await()
            }

            override suspend fun sendSilentRequestToBackendAndAwait(json: String): DapResponse {
                val seq = backendSeq.getAndIncrement()
                val obj = JSONObject(json)
                obj.put("seq", seq)
                val deferred = CompletableDeferred<DapResponse>()
                pendingBackendResponses[seq] = deferred
                silentRequestSeqs.add(seq)
                pendingSilentRequests.incrementAndGet()
                toBackend.send(obj.toString())
                try {
                    return deferred.await()
                } finally {
                    // Only decrement here if the backend reader didn't handle
                    // it (error/cancellation case where the response was never
                    // processed). If the backend reader already removed the seq,
                    // this is a no-op.
                    if (silentRequestSeqs.remove(seq)) {
                        pendingSilentRequests.decrementAndGet()
                    }
                }
            }

            override suspend fun interceptClientRequest(command: String): String {
                val deferred = CompletableDeferred<String>()
                pendingClientInterceptions[command] = deferred
                return deferred.await()
            }

            override fun activateEventGate() {
                eventGate.set(ConcurrentLinkedQueue())
            }

            override suspend fun releaseEventGate() {
                val queue = eventGate.getAndSet(null) ?: return
                // Drain buffered messages. After clearing the gate reference,
                // the backend reader may still have a reference from a
                // concurrent check, so yield and drain once more.
                drainGateQueue(queue, toClient)
                yield()
                drainGateQueue(queue, toClient)
            }
        }

        val clientWriterJob = launchWriter("clientWriter", toClient, clientOutput)
        val backendWriterJob = launchWriter("backendWriter", toBackend, backendOutput)

        val backendReaderJob = launchReader("backendReader", backendInput) { rawJson ->
            val message = DapMessage.parse(rawJson)

            // Route responses to pending sendRequestToBackendAndAwait callers.
            if (message is DapResponse) {
                val deferred = pendingBackendResponses.remove(message.requestSeq)
                if (deferred != null) {
                    // If this response belongs to a silent evaluate, schedule
                    // a deferred decrement. The counter stays elevated through
                    // the NEXT message so that any trailing output event from
                    // the same script command is still suppressed.
                    if (silentRequestSeqs.remove(message.requestSeq)) {
                        deferredDecrements.incrementAndGet()
                    }
                    deferred.complete(message)
                    return@launchReader
                }
            }

            // Suppress console output events BEFORE the interceptor chain.
            // This must happen before the chain because:
            // 1. OutputCategoryNormalizer may reclassify console → stdout
            // 2. OutputCoalescingHandler may buffer the reclassified event
            //    and flush it on a later message when suppression is inactive
            // By suppressing early, the chain never sees auto-display noise.
            if (pendingSilentRequests.get() > 0
                && message is OutputEvent
                && message.category == "console"
            ) {
                log.fine { "backendReader: suppressed console output during silent evaluate: ${message.output.take(80)}" }
                // Still apply deferred decrements
                val n = deferredDecrements.getAndSet(0)
                if (n > 0) pendingSilentRequests.addAndGet(-n)
                return@launchReader
            }

            val results = interceptor.onBackendMessage(message)
            for (msg in results) {
                // Identity check: if the interceptor returned the same object,
                // use the original raw JSON for perfect wire fidelity.
                val json = if (msg === message) rawJson else msg.toJson()

                // If the event gate is active, buffer instead of sending.
                val gate = eventGate.get()
                if (gate != null) {
                    gate.add(json)
                } else {
                    toClient.send(json)
                }
            }

            // Apply deferred decrements AFTER processing this message, so the
            // counter was still elevated while we checked output events above.
            val n = deferredDecrements.getAndSet(0)
            if (n > 0) pendingSilentRequests.addAndGet(-n)
        }

        val clientReaderJob = launchReader("clientReader", clientInput) { rawJson ->
            val message = DapMessage.parse(rawJson)
            when (message) {
                is DapResponse -> {
                    // Route responses to pending reverse request handlers.
                    val deferred = pendingReverseResponses.remove(message.requestSeq)
                    if (deferred != null) {
                        deferred.complete(message)
                    } else {
                        // Unmatched response — forward to backend defensively
                        log.warning { "clientReader: unmatched response for request_seq=${message.requestSeq}, forwarding to backend" }
                        toBackend.send(rawJson)
                    }
                }
                is DapRequest -> {
                    // Check if an async handler is waiting for this command.
                    val interceptionDeferred = pendingClientInterceptions.remove(message.command)
                    if (interceptionDeferred != null) {
                        interceptionDeferred.complete(rawJson)
                    } else {
                        when (val action = interceptor.onRequest(message)) {
                            is RequestAction.Forward -> toBackend.send(rawJson)
                            is RequestAction.Respond -> toClient.send(action.response.toJson())
                            is RequestAction.ForwardModified -> toBackend.send(action.modifiedRequest.toJson())
                            is RequestAction.HandleAsync -> {
                                // Launch async handler as a child coroutine so the
                                // client reader continues processing messages.
                                launch {
                                    action.block(rawJson, asyncCtx)
                                }
                            }
                        }
                    }
                }
                else -> {
                    // Unexpected message type from client — forward to backend
                    log.warning { "clientReader: unexpected message type ${message::class.simpleName}, forwarding to backend" }
                    toBackend.send(rawJson)
                }
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

        /** Drains all messages from [queue] into [channel]. */
        private suspend fun drainGateQueue(
            queue: ConcurrentLinkedQueue<String>,
            channel: Channel<String>,
        ) {
            while (true) {
                val json = queue.poll() ?: break
                channel.send(json)
            }
        }
    }
}

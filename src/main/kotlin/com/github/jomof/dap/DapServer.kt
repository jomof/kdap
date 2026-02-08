package com.github.jomof.dap

import java.io.InputStream
import java.io.OutputStream
import java.util.logging.Logger

/**
 * DAP server: runs the request loop over a [Transport]. Uses [DapFraming] for
 * Content-Length framing and [DapRequestHandler] for dispatch. Same protocol
 * as CodeLLDB on stdio and TCP.
 */
object DapServer {
    private val log = Logger.getLogger(DapServer::class.java.name)

    /**
     * Runs the server on [transport]. Reads one request at a time, handles it,
     * and writes the response. Exits when the input is closed or a read fails.
     */
    fun run(transport: Transport) {
        transport.run { input, output ->
            runLoop(input, output)
        }
    }

    /**
     * Runs the server over [input] and [output]. Used when the caller manages the streams.
     */
    fun run(input: InputStream, output: OutputStream) {
        runLoop(input, output)
    }

    private fun runLoop(input: InputStream, output: OutputStream) {
        while (true) {
            val body = DapFraming.readMessage(input) ?: break
            val response = try {
                DapRequestHandler.handle(body)
            } catch (e: Exception) {
                log.warning("Request handling failed: ${e.message}")
                DapRequestHandler.buildInternalErrorResponse(body, e.message ?: "Internal error")
            }
            DapFraming.writeMessage(output, response)
        }
    }
}

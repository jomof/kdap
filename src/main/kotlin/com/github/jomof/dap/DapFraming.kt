package com.github.jomof.dap

import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets

/**
 * DAP message framing: each message is "Content-Length: N\r\n\r\n" followed by
 * N bytes of UTF-8 JSON. Same format on stdio and TCP; used by CodeLLDB and the DAP spec.
 */
object DapFraming {
    private const val CRLF = "\r\n"
    private const val HEADER_PREFIX = "Content-Length: "

    /**
     * Reads one framed message from [input]. Returns the JSON body, or null on EOF or invalid header.
     */
    fun readMessage(input: InputStream): String? {
        val contentLength = readContentLength(input) ?: return null
        if (contentLength <= 0) return null
        val bytes = input.readNBytes(contentLength)
        if (bytes.size < contentLength) return null
        return bytes.toString(StandardCharsets.UTF_8)
    }

    /**
     * Writes one framed message to [output]: header then UTF-8 JSON [body].
     */
    fun writeMessage(output: OutputStream, body: String) {
        val bytes = body.toByteArray(StandardCharsets.UTF_8)
        val header = "$HEADER_PREFIX${bytes.size}$CRLF$CRLF"
        output.write(header.toByteArray(StandardCharsets.UTF_8))
        output.write(bytes)
        output.flush()
    }

    private fun readContentLength(input: InputStream): Int? {
        val firstLine = readLine(input) ?: return null
        if (!firstLine.startsWith(HEADER_PREFIX)) return null
        val rest = firstLine.substring(HEADER_PREFIX.length).trim()
        val value = rest.toIntOrNull() ?: return null
        // Consume remaining header lines until blank line
        while (true) {
            val line = readLine(input) ?: return null
            if (line.isEmpty()) break
        }
        return value
    }

    private fun readLine(input: InputStream): String? {
        val buf = mutableListOf<Byte>()
        while (true) {
            val b = input.read()
            if (b == -1) return if (buf.isEmpty()) null else buf.toByteArray().toString(StandardCharsets.UTF_8)
            if (b == '\r'.code) {
                val next = input.read()
                if (next == '\n'.code) return buf.toByteArray().toString(StandardCharsets.UTF_8)
                buf.add(b.toByte())
                if (next != -1) buf.add(next.toByte())
                continue
            }
            if (b == '\n'.code) return buf.toByteArray().toString(StandardCharsets.UTF_8)
            buf.add(b.toByte())
        }
    }
}

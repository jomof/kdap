package com.github.jomof

import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit

/**
 * Terminal info reporter for KDAP's `runInTerminal` support.
 *
 * This is a lightweight helper that runs inside the terminal created by
 * the IDE client. Its sole job is to detect the terminal device (TTY)
 * and report it back to KDAP via a TCP connection.
 *
 * ## Usage
 *
 * ```
 * java -cp <classpath> com.github.jomof.KdapLaunchKt --connect=HOST:PORT
 * ```
 *
 * ## Protocol
 *
 * 1. Connects to KDAP's TCP listener at HOST:PORT
 * 2. Sends a JSON object: `{"tty": "/dev/ttys003"}` or `{"tty": null}`
 * 3. Reads a response: `{"success": true}` or `{"success": false, "message": "..."}`
 * 4. Exits with code 0 on success, 1 on failure
 *
 * ## Role 2 rejection
 *
 * Unlike CodeLLDB's `codelldb-launch`, this helper does NOT support
 * receiving a command to execute (Role 2 / RPC external launch).
 * If positional arguments are provided, it exits with an error.
 *
 * ## Platform support
 *
 * TTY detection uses the Unix `tty` command and is supported on
 * macOS and Linux. Windows support is deferred (requires JNI for
 * `GetConsoleProcessList`).
 */
fun main(args: Array<String>) = kdapLaunchMain(args)

/**
 * Implementation extracted for testability.
 */
fun kdapLaunchMain(args: Array<String>) {
    var connectAddress: String? = null
    val positionalArgs = mutableListOf<String>()

    for (arg in args) {
        when {
            arg.startsWith("--connect=") -> connectAddress = arg.removePrefix("--connect=")
            arg.startsWith("--") -> {
                // Ignore unknown flags (e.g., --clear-screen for compatibility)
            }
            else -> positionalArgs.add(arg)
        }
    }

    // Reject Role 2: KDAP does not support external launch via RPC.
    if (positionalArgs.isNotEmpty()) {
        System.err.println("kdap-launch: error: positional arguments are not supported.")
        System.err.println("KDAP does not support external launch (Role 2). Only terminal")
        System.err.println("info reporting is supported (--connect=HOST:PORT).")
        System.err.println("Unexpected args: $positionalArgs")
        System.exit(1)
        return
    }

    if (connectAddress == null) {
        System.err.println("kdap-launch: error: --connect=HOST:PORT is required")
        System.exit(1)
        return
    }

    // Detect TTY (Unix only)
    val ttyName = detectTty()

    // Connect to KDAP and exchange JSON
    try {
        val (host, port) = parseHostPort(connectAddress)
        Socket(host, port).use { socket ->
            // Send terminal info
            val request = JSONObject()
            if (ttyName != null) {
                request.put("tty", ttyName)
            } else {
                request.put("tty", JSONObject.NULL)
            }
            val requestBytes = request.toString().toByteArray(StandardCharsets.UTF_8)
            socket.getOutputStream().write(requestBytes)
            socket.getOutputStream().flush()
            socket.shutdownOutput()

            // Read response
            val responseText = socket.getInputStream()
                .bufferedReader(StandardCharsets.UTF_8)
                .readText()
            if (responseText.isNotBlank()) {
                val response = JSONObject(responseText)
                if (!response.optBoolean("success", false)) {
                    val message = response.optString("message", "unknown error")
                    System.err.println("kdap-launch: adapter error: $message")
                    System.exit(1)
                    return
                }
            }
        }
    } catch (e: Exception) {
        System.err.println("kdap-launch: connection failed: ${e.message}")
        System.exit(1)
        return
    }
}

/**
 * Detects the terminal device name using the Unix `tty` command.
 *
 * Returns the TTY path (e.g., `/dev/ttys003`) if running in a terminal,
 * or `null` if not (e.g., in a headless CI environment where `tty`
 * prints "not a tty").
 */
internal fun detectTty(): String? {
    return try {
        val process = ProcessBuilder("tty")
            .redirectInput(ProcessBuilder.Redirect.INHERIT)
            .start()
        val output = process.inputStream.bufferedReader().readLine()?.trim()
        process.waitFor(5, TimeUnit.SECONDS)
        if (output != null && !output.contains("not a tty", ignoreCase = true) && output.startsWith("/")) {
            output
        } else {
            null
        }
    } catch (e: Exception) {
        // tty command not available (e.g., Windows)
        null
    }
}

/**
 * Parses "HOST:PORT" into a pair. Supports IPv4 addresses and hostnames.
 */
internal fun parseHostPort(address: String): Pair<String, Int> {
    val lastColon = address.lastIndexOf(':')
    require(lastColon > 0) { "Invalid address format: $address (expected HOST:PORT)" }
    val host = address.substring(0, lastColon)
    val port = address.substring(lastColon + 1).toIntOrNull()
        ?: throw IllegalArgumentException("Invalid port in address: $address")
    return host to port
}

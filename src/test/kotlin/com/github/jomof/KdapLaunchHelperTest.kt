package com.github.jomof

import org.json.JSONObject
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.net.ServerSocket
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit

/**
 * Unit tests for `kdap-launch` (the terminal info reporter helper).
 *
 * These test the public utility functions and the TCP protocol without
 * needing a real terminal.
 */
@Timeout(15, unit = TimeUnit.SECONDS)
class KdapLaunchHelperTest {

    // ── parseHostPort ────────────────────────────────────────────────────

    @Test
    fun `parseHostPort parses valid address`() {
        val (host, port) = parseHostPort("127.0.0.1:12345")
        assertEquals("127.0.0.1", host)
        assertEquals(12345, port)
    }

    @Test
    fun `parseHostPort parses localhost`() {
        val (host, port) = parseHostPort("localhost:8080")
        assertEquals("localhost", host)
        assertEquals(8080, port)
    }

    @Test
    fun `parseHostPort rejects missing port`() {
        assertThrows(IllegalArgumentException::class.java) {
            parseHostPort("127.0.0.1")
        }
    }

    @Test
    fun `parseHostPort rejects invalid port`() {
        assertThrows(IllegalArgumentException::class.java) {
            parseHostPort("127.0.0.1:abc")
        }
    }

    // ── TCP protocol ─────────────────────────────────────────────────────

    @Test
    fun `sends TTY info and receives success response`() {
        // Start a test TCP server that simulates KDAP's listener
        val server = ServerSocket(0)
        val port = server.localPort

        // Run kdap-launch in a thread (it connects to our server)
        val thread = Thread {
            kdapLaunchMain(arrayOf("--connect=127.0.0.1:$port"))
        }
        thread.start()

        // Accept connection and verify protocol
        val socket = server.accept()
        socket.soTimeout = 5_000

        // Read the JSON sent by kdap-launch
        val input = socket.getInputStream()
            .bufferedReader(StandardCharsets.UTF_8)
            .readText()
        val json = JSONObject(input)
        // In test environment, tty will be null (no real TTY)
        assertTrue(json.has("tty"), "JSON should have 'tty' field")

        // Send success response
        val response = JSONObject().apply { put("success", true) }
        socket.getOutputStream().write(
            response.toString().toByteArray(StandardCharsets.UTF_8)
        )
        socket.getOutputStream().flush()
        socket.close()
        server.close()

        thread.join(5_000)
        assertFalse(thread.isAlive, "kdap-launch should have exited")
    }

    // ── Role 2 rejection ─────────────────────────────────────────────────

    @Test
    fun `rejects positional arguments (Role 2)`() {
        // Capture System.exit calls by testing the logic before System.exit
        // We can't easily test System.exit in JUnit, but we can test
        // the parsing logic. Let's verify that positional args cause
        // an error message to stderr.

        // Use a separate process to test the actual exit code
        val javaHome = System.getProperty("java.home")
        val javaBin = "$javaHome/bin/java"
        val classpath = System.getProperty("java.class.path")
        val process = ProcessBuilder(
            javaBin, "-cp", classpath, "com.github.jomof.KdapLaunchKt",
            "--connect=127.0.0.1:12345", "some-command"
        ).redirectErrorStream(true).start()

        val output = process.inputStream.bufferedReader().readText()
        val exitCode = process.waitFor()

        assertEquals(1, exitCode, "Should exit with code 1 for Role 2 rejection")
        assertTrue(output.contains("positional arguments are not supported"),
            "Should print error about positional args: $output")
    }

    @Test
    fun `rejects missing connect argument`() {
        val javaHome = System.getProperty("java.home")
        val javaBin = "$javaHome/bin/java"
        val classpath = System.getProperty("java.class.path")
        val process = ProcessBuilder(
            javaBin, "-cp", classpath, "com.github.jomof.KdapLaunchKt"
        ).redirectErrorStream(true).start()

        val output = process.inputStream.bufferedReader().readText()
        val exitCode = process.waitFor()

        assertEquals(1, exitCode, "Should exit with code 1 when --connect is missing")
        assertTrue(output.contains("--connect=HOST:PORT is required"),
            "Should print error about missing --connect: $output")
    }
}

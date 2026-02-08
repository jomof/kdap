package com.github.jomof.dap

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class CliTest {

    @Test
    fun `no args returns stdio`() {
        val config = Cli.parse(emptyArray())
        assertEquals(Transport.Stdio, config?.transport)
    }

    @Test
    fun `--port N returns TcpListen`() {
        val config = Cli.parse(arrayOf("--port", "0"))
        assertEquals(Transport.TcpListen(0), config?.transport)

        val config2 = Cli.parse(arrayOf("--port", "12345"))
        assertEquals(Transport.TcpListen(12345), config2?.transport)
    }

    @Test
    fun `--connect N returns TcpConnect to 127_0_0_1`() {
        val config = Cli.parse(arrayOf("--connect", "9999"))
        assertEquals(Transport.TcpConnect("127.0.0.1", 9999), config?.transport)
    }

    @Test
    fun `both --port and --connect returns null`() {
        assertNull(Cli.parse(arrayOf("--port", "1", "--connect", "2")))
        assertNull(Cli.parse(arrayOf("--connect", "2", "--port", "1")))
    }

    @Test
    fun `--port with missing value returns null`() {
        assertNull(Cli.parse(arrayOf("--port")))
    }

    @Test
    fun `--connect with missing value returns null`() {
        assertNull(Cli.parse(arrayOf("--connect")))
    }

    @Test
    fun `--port with non-numeric returns null`() {
        assertNull(Cli.parse(arrayOf("--port", "x")))
    }

    @Test
    fun `--port out of range returns null`() {
        assertNull(Cli.parse(arrayOf("--port", "-1")))
        assertNull(Cli.parse(arrayOf("--port", "65536")))
    }

    @Test
    fun `--connect port 0 returns null`() {
        assertNull(Cli.parse(arrayOf("--connect", "0")))
    }

    @Test
    fun `unknown args are skipped`() {
        val config = Cli.parse(arrayOf("--port", "42", "other", "ignored"))
        assertEquals(Transport.TcpListen(42), config?.transport)
    }

    @Test
    fun `--lldb-dap sets path`() {
        val config = Cli.parse(arrayOf("--lldb-dap", "/usr/bin/lldb-dap"))
        assertEquals(Transport.Stdio, config?.transport)
        assertEquals("/usr/bin/lldb-dap", config?.lldbDapPath)
    }

    @Test
    fun `--lldb-dap with missing value returns null`() {
        assertNull(Cli.parse(arrayOf("--lldb-dap")))
    }

    @Test
    fun `--lldb-dap combines with --port`() {
        val config = Cli.parse(arrayOf("--port", "8080", "--lldb-dap", "/path/to/lldb-dap"))
        assertEquals(Transport.TcpListen(8080), config?.transport)
        assertEquals("/path/to/lldb-dap", config?.lldbDapPath)
    }

    @Test
    fun `no --lldb-dap leaves path null`() {
        val config = Cli.parse(emptyArray())
        assertNull(config?.lldbDapPath)
    }
}

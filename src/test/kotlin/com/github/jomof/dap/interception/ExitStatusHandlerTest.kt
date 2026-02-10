package com.github.jomof.dap.interception

import com.github.jomof.dap.messages.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/**
 * Unit tests for [ExitStatusHandler]. Verifies that lldb-dap's process exit
 * output is reformatted to match CodeLLDB's format.
 */
class ExitStatusHandlerTest {

    private val handler = ExitStatusHandler()

    @Test
    fun `reformats lldb-dap exit message to CodeLLDB format`() {
        val input = OutputEvent.console("Process 12345 exited with status = 255 (0x000000ff) \n")
        val result = handler.onBackendMessage(input)

        assertEquals(1, result.size)
        val output = assertInstanceOf(OutputEvent::class.java, result[0])
        assertEquals("console", output.category)
        assertEquals("Process exited with code 255.\n", output.output)
    }

    @Test
    fun `reformats exit code zero`() {
        val input = OutputEvent.console("Process 67890 exited with status = 0 (0x00000000) \n")
        val result = handler.onBackendMessage(input)

        assertEquals(1, result.size)
        val output = assertInstanceOf(OutputEvent::class.java, result[0])
        assertEquals("Process exited with code 0.\n", output.output)
    }

    @Test
    fun `non-exit console output is forwarded unchanged`() {
        val input = OutputEvent.console("Launching: /usr/bin/test\n")
        val result = handler.onBackendMessage(input)

        assertEquals(1, result.size)
        assertSame(input, result[0])
    }

    @Test
    fun `stdout output events are forwarded unchanged`() {
        val input = OutputEvent(seq = 0, category = "stdout", output = "Process 1 exited with status = 0\n")
        val result = handler.onBackendMessage(input)

        assertEquals(1, result.size)
        assertSame(input, result[0])
    }

    @Test
    fun `non-output events are forwarded unchanged`() {
        val event = ExitedEvent(seq = 0, exitCode = 255)
        val result = handler.onBackendMessage(event)

        assertEquals(1, result.size)
        assertSame(event, result[0])
    }
}

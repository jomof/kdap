package com.github.jomof.dap.interception

import com.github.jomof.dap.messages.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/**
 * Unit tests for [OutputCategoryHandler]. Verifies that debuggee output
 * arriving as `category: "console"` (Windows lldb-dap behavior) is
 * reclassified to `stdout` after the process starts running.
 */
class OutputCategoryHandlerTest {

    private val handler = OutputCategoryHandler()

    @Test
    fun `console output before continued event is unchanged`() {
        val input = OutputEvent.console("Launching: /usr/bin/test\n")
        val result = handler.onBackendMessage(input)

        assertEquals(1, result.size)
        assertSame(input, result[0])
    }

    @Test
    fun `continued event passes through and activates reclassification`() {
        val event = ContinuedEvent(seq = 0, allThreadsContinued = true)
        val result = handler.onBackendMessage(event)

        assertEquals(1, result.size)
        assertSame(event, result[0])
    }

    @Test
    fun `console output after continued event is reclassified to stdout`() {
        // Activate running state
        handler.onBackendMessage(ContinuedEvent(seq = 0, allThreadsContinued = true))

        val input = OutputEvent.console("No testcase was specified.\n")
        val result = handler.onBackendMessage(input)

        assertEquals(1, result.size)
        val output = assertInstanceOf(OutputEvent::class.java, result[0])
        assertEquals("stdout", output.category)
        assertEquals("No testcase was specified.\n", output.output)
    }

    @Test
    fun `exit message after continued event stays as console`() {
        handler.onBackendMessage(ContinuedEvent(seq = 0, allThreadsContinued = true))

        val input = OutputEvent.console("Process 5800 exited with status = -1 (0xffffffff) \n")
        val result = handler.onBackendMessage(input)

        assertEquals(1, result.size)
        val output = assertInstanceOf(OutputEvent::class.java, result[0])
        assertEquals("console", output.category)
    }

    @Test
    fun `exit message with non-negative code stays as console`() {
        handler.onBackendMessage(ContinuedEvent(seq = 0, allThreadsContinued = true))

        val input = OutputEvent.console("Process 12345 exited with status = 255 (0x000000ff) \n")
        val result = handler.onBackendMessage(input)

        assertEquals(1, result.size)
        val output = assertInstanceOf(OutputEvent::class.java, result[0])
        assertEquals("console", output.category)
    }

    @Test
    fun `stdout output after continued event is unchanged`() {
        handler.onBackendMessage(ContinuedEvent(seq = 0, allThreadsContinued = true))

        val input = OutputEvent(seq = 0, category = "stdout", output = "hello\n")
        val result = handler.onBackendMessage(input)

        assertEquals(1, result.size)
        assertSame(input, result[0])
    }

    @Test
    fun `non-output events pass through unchanged`() {
        handler.onBackendMessage(ContinuedEvent(seq = 0, allThreadsContinued = true))

        val event = ExitedEvent(seq = 0, exitCode = 0)
        val result = handler.onBackendMessage(event)

        assertEquals(1, result.size)
        assertSame(event, result[0])
    }
}

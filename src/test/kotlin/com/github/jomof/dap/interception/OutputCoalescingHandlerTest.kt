package com.github.jomof.dap.interception

import com.github.jomof.dap.messages.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/**
 * Unit tests for [OutputCoalescingHandler]. Verifies that consecutive
 * debuggee output events (stdout/stderr) are combined into single events,
 * matching CodeLLDB's behavior.
 */
class OutputCoalescingHandlerTest {

    private val handler = OutputCoalescingHandler()

    @Test
    fun `combines consecutive stdout events`() {
        // Two stdout events arrive back-to-back
        val r1 = handler.onBackendMessage(OutputEvent(seq = 1, category = "stdout", output = "hello\n"))
        assertEquals(0, r1.size, "First stdout should be buffered")

        val r2 = handler.onBackendMessage(OutputEvent(seq = 2, category = "stdout", output = "world\n"))
        assertEquals(0, r2.size, "Second stdout should be buffered")

        // Non-output event flushes the buffer
        val r3 = handler.onBackendMessage(ExitedEvent(seq = 3, exitCode = 0))
        assertEquals(2, r3.size, "Should flush combined output + forward event")
        val combined = assertInstanceOf(OutputEvent::class.java, r3[0])
        assertEquals("stdout", combined.category)
        assertEquals("hello\nworld\n", combined.output)
        assertInstanceOf(ExitedEvent::class.java, r3[1])
    }

    @Test
    fun `single stdout event flushed by next non-output`() {
        val r1 = handler.onBackendMessage(OutputEvent(seq = 1, category = "stdout", output = "only\n"))
        assertEquals(0, r1.size)

        val r2 = handler.onBackendMessage(TerminatedEvent(seq = 2))
        assertEquals(2, r2.size)
        val output = assertInstanceOf(OutputEvent::class.java, r2[0])
        assertEquals("only\n", output.output)
        assertInstanceOf(TerminatedEvent::class.java, r2[1])
    }

    @Test
    fun `console output is not coalesced`() {
        val console = OutputEvent.console("adapter message\n")
        val result = handler.onBackendMessage(console)
        assertEquals(1, result.size)
        assertSame(console, result[0])
    }

    @Test
    fun `console output flushes pending stdout`() {
        handler.onBackendMessage(OutputEvent(seq = 1, category = "stdout", output = "buffered\n"))

        val result = handler.onBackendMessage(OutputEvent.console("Process exited.\n"))
        assertEquals(2, result.size)
        val flushed = assertInstanceOf(OutputEvent::class.java, result[0])
        assertEquals("stdout", flushed.category)
        assertEquals("buffered\n", flushed.output)
        val console = assertInstanceOf(OutputEvent::class.java, result[1])
        assertEquals("console", console.category)
    }

    @Test
    fun `different coalesced category flushes previous`() {
        handler.onBackendMessage(OutputEvent(seq = 1, category = "stdout", output = "out\n"))

        val result = handler.onBackendMessage(OutputEvent(seq = 2, category = "stderr", output = "err\n"))
        assertEquals(1, result.size, "Flush stdout, buffer stderr")
        val flushed = assertInstanceOf(OutputEvent::class.java, result[0])
        assertEquals("stdout", flushed.category)
        assertEquals("out\n", flushed.output)

        // Verify stderr is still buffered
        val r2 = handler.onBackendMessage(ExitedEvent(seq = 3, exitCode = 0))
        assertEquals(2, r2.size)
        val stderr = assertInstanceOf(OutputEvent::class.java, r2[0])
        assertEquals("stderr", stderr.category)
        assertEquals("err\n", stderr.output)
    }

    @Test
    fun `non-output events pass through when no buffer`() {
        val event = ContinuedEvent(seq = 1, allThreadsContinued = true)
        val result = handler.onBackendMessage(event)
        assertEquals(1, result.size)
        assertSame(event, result[0])
    }

    @Test
    fun `responses pass through and flush buffer`() {
        handler.onBackendMessage(OutputEvent(seq = 1, category = "stdout", output = "data\n"))

        val response = DapResponse(seq = 2, requestSeq = 1, command = "launch", success = true)
        val result = handler.onBackendMessage(response)
        assertEquals(2, result.size)
        assertInstanceOf(OutputEvent::class.java, result[0])
        assertSame(response, result[1])
    }
}

package com.github.jomof.dap.interception

import com.github.jomof.dap.messages.InitializedEvent
import com.github.jomof.dap.messages.OutputEvent
import com.github.jomof.dap.messages.StoppedEvent
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/**
 * Unit tests for [ConsoleModeHandler]. Verifies that a console mode
 * announcement is injected before the first `initialized` event, and
 * not injected again on subsequent events.
 */
class ConsoleModeHandlerTest {

    private val handler = ConsoleModeHandler()

    @Test
    fun `injects console mode event before initialized event`() {
        val initialized = InitializedEvent(seq = 1)
        val result = handler.onBackendMessage(initialized)

        assertEquals(2, result.size, "Should inject one event before initialized")
        val injected = assertInstanceOf(OutputEvent::class.java, result[0])
        assertEquals("console", injected.category)
        assertEquals(ConsoleModeHandler.CONSOLE_MODE_MESSAGE, injected.output)
        assertSame(initialized, result[1])
    }

    @Test
    fun `does not inject console mode event twice`() {
        val initialized = InitializedEvent(seq = 1)
        handler.onBackendMessage(initialized) // first time — injects
        val result = handler.onBackendMessage(initialized) // second time — no injection
        assertEquals(1, result.size, "Second initialized should not trigger injection")
        assertSame(initialized, result[0])
    }

    @Test
    fun `non-initialized events are forwarded unchanged`() {
        val event = StoppedEvent(seq = 10)
        val result = handler.onBackendMessage(event)
        assertEquals(1, result.size)
        assertSame(event, result[0])
    }
}

package com.github.jomof.dap.interception

import com.github.jomof.dap.DapSession.RequestAction
import com.github.jomof.dap.messages.DapResponse
import com.github.jomof.dap.messages.InitializeRequest
import com.github.jomof.dap.messages.UnknownRequest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/**
 * Unit tests for [TriggerErrorHandler]. Verifies that the `_triggerError`
 * test hook returns a local error response, while other commands pass through.
 */
class TriggerErrorHandlerTest {

    private val handler = TriggerErrorHandler()

    @Test
    fun `_triggerError returns local error response`() {
        val request = UnknownRequest(seq = 10, command = "_triggerError")
        val action = assertInstanceOf(RequestAction.Respond::class.java, handler.onRequest(request))
        val response = assertInstanceOf(DapResponse::class.java, action.response)
        assertEquals(10, response.requestSeq)
        assertEquals("_triggerError", response.command)
        assertFalse(response.success)
        assertEquals(TriggerErrorHandler.INTERNAL_ERROR_MESSAGE, response.message)
    }

    @Test
    fun `_triggerError with seq 0`() {
        val request = UnknownRequest(seq = 0, command = "_triggerError")
        val action = assertInstanceOf(RequestAction.Respond::class.java, handler.onRequest(request))
        val response = assertInstanceOf(DapResponse::class.java, action.response)
        assertEquals(0, response.requestSeq)
    }

    @Test
    fun `other unknown commands are forwarded`() {
        val request = UnknownRequest(seq = 5, command = "somethingElse")
        assertInstanceOf(RequestAction.Forward::class.java, handler.onRequest(request))
    }

    @Test
    fun `non-unknown request is forwarded`() {
        val request = InitializeRequest(seq = 1)
        assertInstanceOf(RequestAction.Forward::class.java, handler.onRequest(request))
    }
}

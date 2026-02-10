package com.github.jomof.dap.interception

import com.github.jomof.dap.DapSession.RequestAction
import com.github.jomof.dap.debugsession.DebugSession
import com.github.jomof.dap.messages.InitializeRequest
import com.github.jomof.dap.messages.SetBreakpointsRequest
import com.github.jomof.dap.messages.TerminateRequest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/**
 * Unit tests for [TerminateHandler]. Verifies that `terminate` requests
 * return [RequestAction.HandleAsync] and other requests pass through.
 */
class TerminateHandlerTest {

    private val session = DebugSession()
    private val handler = TerminateHandler(session)

    @Test
    fun `terminate request returns HandleAsync`() {
        val request = TerminateRequest(seq = 1)
        assertInstanceOf(RequestAction.HandleAsync::class.java, handler.onRequest(request))
    }

    @Test
    fun `initialize request is forwarded unchanged`() {
        val request = InitializeRequest(seq = 2)
        assertInstanceOf(RequestAction.Forward::class.java, handler.onRequest(request))
    }

    @Test
    fun `non-terminate request is forwarded unchanged`() {
        val request = SetBreakpointsRequest(seq = 3)
        assertInstanceOf(RequestAction.Forward::class.java, handler.onRequest(request))
    }
}

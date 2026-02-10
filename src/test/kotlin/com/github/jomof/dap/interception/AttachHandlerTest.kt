package com.github.jomof.dap.interception

import com.github.jomof.dap.DapSession.RequestAction
import com.github.jomof.dap.debugsession.DebugSession
import com.github.jomof.dap.messages.AttachRequest
import com.github.jomof.dap.messages.InitializeRequest
import com.github.jomof.dap.messages.SetBreakpointsRequest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/**
 * Unit tests for [AttachHandler]. Verifies that `attach` requests
 * return [RequestAction.HandleAsync] and other requests pass through.
 */
class AttachHandlerTest {

    private val session = DebugSession()
    private val handler = AttachHandler(session)

    @Test
    fun `attach request returns HandleAsync`() {
        val request = AttachRequest(seq = 1)
        assertInstanceOf(RequestAction.HandleAsync::class.java, handler.onRequest(request))
    }

    @Test
    fun `initialize request is forwarded unchanged`() {
        val request = InitializeRequest(seq = 2)
        assertInstanceOf(RequestAction.Forward::class.java, handler.onRequest(request))
    }

    @Test
    fun `non-attach request is forwarded unchanged`() {
        val request = SetBreakpointsRequest(seq = 3)
        assertInstanceOf(RequestAction.Forward::class.java, handler.onRequest(request))
    }
}

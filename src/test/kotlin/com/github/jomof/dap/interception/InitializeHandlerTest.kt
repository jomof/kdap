package com.github.jomof.dap.interception

import com.github.jomof.dap.DapSession.RequestAction
import com.github.jomof.dap.debugsession.DebugSession
import com.github.jomof.dap.messages.InitializeRequest
import com.github.jomof.dap.messages.LaunchRequest
import com.github.jomof.dap.messages.LaunchRequestArguments
import com.github.jomof.dap.messages.SetBreakpointsRequest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/**
 * Unit tests for [InitializeHandler]. Verifies that `initialize`
 * requests are observed (forwarded) and other requests pass through.
 */
class InitializeHandlerTest {

    private val session = DebugSession()
    private val handler = InitializeHandler(session)

    @Test
    fun `initialize request returns Forward`() {
        val request = InitializeRequest(seq = 1)
        assertInstanceOf(RequestAction.Forward::class.java, handler.onRequest(request))
    }

    @Test
    fun `initialize with supportsRunInTerminal returns Forward`() {
        val request = InitializeRequest(seq = 2, supportsRunInTerminalRequest = true)
        assertInstanceOf(RequestAction.Forward::class.java, handler.onRequest(request))
    }

    @Test
    fun `launch request is forwarded unchanged`() {
        val request = LaunchRequest(seq = 3, arguments = LaunchRequestArguments(program = "/bin/ls"))
        assertInstanceOf(RequestAction.Forward::class.java, handler.onRequest(request))
    }

    @Test
    fun `non-initialize request is forwarded unchanged`() {
        val request = SetBreakpointsRequest(seq = 4)
        assertInstanceOf(RequestAction.Forward::class.java, handler.onRequest(request))
    }
}

package com.github.jomof.dap.interception

import com.github.jomof.dap.DapSession.RequestAction
import com.github.jomof.dap.messages.EvaluateRequest
import com.github.jomof.dap.messages.InitializeRequest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/**
 * Unit tests for [EvaluateContextRewriter]. Verifies that CodeLLDB-style
 * `_command` context is rewritten to `repl`, while other contexts and
 * non-evaluate requests pass through unchanged.
 */
class EvaluateContextRewriterTest {

    private val handler = EvaluateContextRewriter()

    @Test
    fun `evaluate with _command context is rewritten to repl`() {
        val request = EvaluateRequest(seq = 5, expression = "version", context = "_command")
        val action = assertInstanceOf(RequestAction.ForwardModified::class.java, handler.onRequest(request))
        val modified = assertInstanceOf(EvaluateRequest::class.java, action.modifiedRequest)
        assertEquals("repl", modified.context)
        assertEquals("version", modified.expression)
        assertEquals(5, modified.seq)
    }

    @Test
    fun `evaluate with repl context is forwarded unchanged`() {
        val request = EvaluateRequest(seq = 3, expression = "version", context = "repl")
        assertInstanceOf(RequestAction.Forward::class.java, handler.onRequest(request))
    }

    @Test
    fun `evaluate with watch context is forwarded unchanged`() {
        val request = EvaluateRequest(seq = 3, expression = "x", context = "watch")
        assertInstanceOf(RequestAction.Forward::class.java, handler.onRequest(request))
    }

    @Test
    fun `evaluate without context is forwarded unchanged`() {
        val request = EvaluateRequest(seq = 4, expression = "1+1", context = null)
        assertInstanceOf(RequestAction.Forward::class.java, handler.onRequest(request))
    }

    @Test
    fun `rewrite preserves rest of request`() {
        val request = EvaluateRequest(seq = 7, expression = "bt", context = "_command", frameId = 42)
        val action = assertInstanceOf(RequestAction.ForwardModified::class.java, handler.onRequest(request))
        val modified = assertInstanceOf(EvaluateRequest::class.java, action.modifiedRequest)
        assertEquals("bt", modified.expression)
        assertEquals(42, modified.frameId)
        assertEquals(7, modified.seq)
        assertEquals("repl", modified.context)
    }

    @Test
    fun `non-evaluate request is forwarded unchanged`() {
        val request = InitializeRequest(seq = 1)
        assertInstanceOf(RequestAction.Forward::class.java, handler.onRequest(request))
    }
}

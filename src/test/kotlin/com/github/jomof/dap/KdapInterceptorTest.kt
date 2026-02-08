package com.github.jomof.dap

import com.github.jomof.dap.DapSession.RequestAction
import org.json.JSONObject
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/**
 * Unit tests for [KdapInterceptor]. These exercise the interceptor in isolation
 * (no DapSession, no lldb-dap) to verify request classification and transformation.
 */
class KdapInterceptorTest {

    // ── _command → repl rewriting ──────────────────────────────────────────

    @Test
    fun `evaluate with _command context is rewritten to repl`() {
        val request = """{"type":"request","seq":5,"command":"evaluate","arguments":{"expression":"version","context":"_command"}}"""
        val action = assertInstanceOf(RequestAction.ForwardModified::class.java, KdapInterceptor.onRequest(request))
        assertTrue(action.modifiedRequest.contains(""""context":"repl"""")) {
            "Expected context rewritten to repl, got: ${action.modifiedRequest}"
        }
        assertFalse(action.modifiedRequest.contains("_command")) {
            "Original _command context should be removed: ${action.modifiedRequest}"
        }
    }

    @Test
    fun `evaluate with repl context is forwarded unchanged`() {
        val request = """{"type":"request","seq":3,"command":"evaluate","arguments":{"expression":"version","context":"repl"}}"""
        assertInstanceOf(RequestAction.Forward::class.java, KdapInterceptor.onRequest(request))
    }

    @Test
    fun `evaluate with watch context is forwarded unchanged`() {
        val request = """{"type":"request","seq":3,"command":"evaluate","arguments":{"expression":"x","context":"watch"}}"""
        assertInstanceOf(RequestAction.Forward::class.java, KdapInterceptor.onRequest(request))
    }

    @Test
    fun `evaluate without context is forwarded unchanged`() {
        val request = """{"type":"request","seq":4,"command":"evaluate","arguments":{"expression":"1+1"}}"""
        assertInstanceOf(RequestAction.Forward::class.java, KdapInterceptor.onRequest(request))
    }

    @Test
    fun `rewrite preserves rest of request`() {
        val request = """{"type":"request","seq":7,"command":"evaluate","arguments":{"expression":"bt","context":"_command","frameId":42}}"""
        val action = assertInstanceOf(RequestAction.ForwardModified::class.java, KdapInterceptor.onRequest(request))
        assertTrue(action.modifiedRequest.contains(""""expression":"bt""""))
        assertTrue(action.modifiedRequest.contains(""""frameId":42"""))
        assertTrue(action.modifiedRequest.contains(""""seq":7"""))
    }

    // ── _triggerError (test hook) ──────────────────────────────────────────

    @Test
    fun `_triggerError returns local error response`() {
        val request = """{"type":"request","seq":10,"command":"_triggerError","arguments":{}}"""
        val action = assertInstanceOf(RequestAction.Respond::class.java, KdapInterceptor.onRequest(request))
        val json = JSONObject(action.response)
        assertEquals("response", json.getString("type"))
        assertEquals(10, json.getInt("request_seq"))
        assertEquals("_triggerError", json.getString("command"))
        assertEquals(false, json.getBoolean("success"))
        assertEquals(KdapInterceptor.INTERNAL_ERROR_MESSAGE, json.getString("message"))
    }

    @Test
    fun `_triggerError with missing seq defaults to 0`() {
        val request = """{"type":"request","command":"_triggerError","arguments":{}}"""
        val action = assertInstanceOf(RequestAction.Respond::class.java, KdapInterceptor.onRequest(request))
        val json = JSONObject(action.response)
        assertEquals(0, json.getInt("request_seq"))
    }

    // ── Pass-through for non-intercepted commands ──────────────────────────

    @Test
    fun `initialize request is forwarded unchanged`() {
        val request = """{"type":"request","seq":1,"command":"initialize","arguments":{"clientID":"test"}}"""
        assertInstanceOf(RequestAction.Forward::class.java, KdapInterceptor.onRequest(request))
    }

    @Test
    fun `launch request is forwarded unchanged`() {
        val request = """{"type":"request","seq":2,"command":"launch","arguments":{"program":"/bin/ls"}}"""
        assertInstanceOf(RequestAction.Forward::class.java, KdapInterceptor.onRequest(request))
    }

    @Test
    fun `setBreakpoints request is forwarded unchanged`() {
        val request = """{"type":"request","seq":6,"command":"setBreakpoints","arguments":{"source":{"path":"main.cpp"},"breakpoints":[{"line":10}]}}"""
        assertInstanceOf(RequestAction.Forward::class.java, KdapInterceptor.onRequest(request))
    }

    @Test
    fun `disconnect request is forwarded unchanged`() {
        val request = """{"type":"request","seq":99,"command":"disconnect","arguments":{"terminateDebuggee":true}}"""
        assertInstanceOf(RequestAction.Forward::class.java, KdapInterceptor.onRequest(request))
    }
}

package com.github.jomof

import org.junit.jupiter.api.Assertions
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.junit.jupiter.params.provider.MethodSource

/**
 * Tests that the DAP server responds to `initialize`. Runs for each connection mode
 * (our server: stdio, TCP listen, TCP connect, in-process; lldb-dap: TCP_LLDB; CodeLLDB: STDIO_CODELDB, TCP_CODELDB).
 *
 * Each server has a complete capabilities baseline below so the full response shape is
 * documented and structural differences between server types are immediately visible.
 * Strict comparison catches both missing and unexpected keys.
 */
class DapInitializeTest {

    /**
     * Current capabilities from our KDAP server. KDAP is a transparent proxy in front
     * of lldb-dap, so capabilities are identical to lldb-dap's. If KDAP ever augments
     * capabilities (e.g. adding CodeLLDB-specific ones), this baseline should diverge.
     */
    private val expectedOurCapabilitiesBaseline get() = expectedLldbDapInitializeCapabilitiesBaseline

    /** Current capabilities from lldb-dap (LLVM 21.1.8 prebuilts). */
    private val expectedLldbDapInitializeCapabilitiesBaseline = """
        {
          "supportTerminateDebuggee": true,
          "supportsCancelRequest": true,
          "supportsBreakpointLocationsRequest": true,
          "supportsCompletionsRequest": true,
          "supportsConditionalBreakpoints": true,
          "supportsConfigurationDoneRequest": true,
          "supportsDataBreakpoints": true,
          "supportsDelayedStackTraceLoading": true,
          "supportsDisassembleRequest": true,
          "supportsEvaluateForHovers": true,
          "supportsExceptionFilterOptions": true,
          "supportsExceptionInfoRequest": true,
          "supportsFunctionBreakpoints": true,
          "supportsHitConditionalBreakpoints": true,
          "supportsInstructionBreakpoints": true,
          "supportsLogPoints": true,
          "supportsModulesRequest": true,
          "supportsReadMemoryRequest": true,
          "supportsSetVariable": true,
          "supportsSteppingGranularity": true,
          "supportsValueFormattingOptions": true,
          "supportsWriteMemoryRequest": true,
          "completionTriggerCharacters": [".", " ", "\t"],
          "exceptionBreakpointFilters": [
            { "filter": "cpp_catch",  "supportsCondition": true, "description": "C++ Catch",            "label": "C++ Catch" },
            { "filter": "cpp_throw",  "supportsCondition": true, "description": "C++ Throw",            "label": "C++ Throw" },
            { "filter": "objc_catch", "supportsCondition": true, "description": "Objective-C Catch",    "label": "Objective-C Catch" },
            { "filter": "objc_throw", "supportsCondition": true, "description": "Objective-C Throw",    "label": "Objective-C Throw" }
          ],
          "${'$'}__lldb_version": "lldb version 21.1.8 (https://github.com/llvm/llvm-project revision 2078da43e25a4623cab2d0d60decddf709aaea28)\n  clang revision 2078da43e25a4623cab2d0d60decddf709aaea28\n  llvm revision 2078da43e25a4623cab2d0d60decddf709aaea28"
        }
    """.trimIndent()

    /** Current capabilities from CodeLLDB adapter (codelldb-vsix, LLDB 21.1.7-codelldb). */
    private val expectedCodeLldbInitializeCapabilitiesBaseline = """
        {
          "supportTerminateDebuggee": true,
          "supportsCancelRequest": true,
          "supportsClipboardContext": true,
          "supportsCompletionsRequest": true,
          "supportsConditionalBreakpoints": true,
          "supportsConfigurationDoneRequest": true,
          "supportsDataBreakpoints": true,
          "supportsDataBreakpointBytes": true,
          "supportsDelayedStackTraceLoading": true,
          "supportsDisassembleRequest": true,
          "supportsEvaluateForHovers": true,
          "supportsExceptionFilterOptions": true,
          "supportsExceptionInfoRequest": true,
          "supportsFunctionBreakpoints": true,
          "supportsGotoTargetsRequest": true,
          "supportsHitConditionalBreakpoints": true,
          "supportsInstructionBreakpoints": true,
          "supportsLogPoints": true,
          "supportsModulesRequest": true,
          "supportsReadMemoryRequest": true,
          "supportsRestartRequest": true,
          "supportsSetVariable": true,
          "supportsStepInTargetsRequest": true,
          "supportsSteppingGranularity": true,
          "supportsWriteMemoryRequest": true,
          "exceptionBreakpointFilters": [
            { "filter": "cpp_throw", "default": true,  "supportsCondition": true, "label": "C++: on throw" },
            { "filter": "cpp_catch", "default": false, "supportsCondition": true, "label": "C++: on catch" }
          ]
        }
    """.trimIndent()

    @ParameterizedTest(name = "{0}")
    @EnumSource(ConnectionMode::class)
    fun `initialize receives valid response with capabilities`(mode: ConnectionMode) {
        mode.connect().use { ctx ->
            val responseBody = DapTestUtils.sendInitializeAndReadResponse(ctx.inputStream, ctx.outputStream)
            DapTestUtils.assertValidInitializeResponse(responseBody)
            val actualCapabilities = DapTestUtils.parseInitializeCapabilities(responseBody)
            val baseline = when (mode.serverKind) {
                ServerKind.OUR_SERVER -> expectedOurCapabilitiesBaseline
                ServerKind.LLDB_DAP -> expectedLldbDapInitializeCapabilitiesBaseline
                ServerKind.CODELDB -> expectedCodeLldbInitializeCapabilitiesBaseline
            }
            // $__lldb_version is platform-specific: macOS/Linux include git revision
            // details, Windows only returns "lldb version X.Y.Z". Exclude from strict
            // comparison; the field is still in the baseline for documentation.
            val diff = DapTestUtils.compareJsonStrict(
                actualCapabilities, baseline, excludeKeys = setOf("\$__lldb_version")
            )
            Assertions.assertTrue(diff == null) {
                "Initialize capabilities differ from baseline ($mode):\n$diff\n\nActual capabilities:\n${actualCapabilities.toString(2)}"
            }
        }
    }

    /** Exercises our server's catch block: _triggerError throws, server returns internal error. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("com.github.jomof.ConnectionMode#ourServerModes")
    fun `triggerError request receives expected error response`(mode: ConnectionMode) {
        mode.connect().use { ctx ->
            DapTestUtils.sendTriggerErrorRequest(ctx.outputStream)
            val responseBody = DapTestUtils.readResponseBody(ctx.inputStream)
            DapTestUtils.assertInternalErrorResponse(responseBody)
        }
    }
}

package com.github.jomof

import org.json.JSONObject
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource

/**
 * Proves that each LLDB-backed adapter is communicating with lldb/liblldb by running
 * the LLDB `version` command through a DAP evaluate request and asserting the response
 * contains information that could only come from a live LLDB instance.
 *
 * Inspired by the `pypath` test in codelldb's Rust test suite
 * (.codelldb-inspiration/src/codelldb/src/python.rs), which validates that the LLDB
 * command interpreter is functional after loading liblldb. Here we do the equivalent
 * over the DAP protocol: initialize the adapter, then ask LLDB for its version string.
 *
 * No debuggee binary is launched; we rely on the LLDB command interpreter being
 * available immediately after the adapter loads liblldb.
 *
 * Each server has an expected evaluate response body baseline below so the full
 * response shape is documented. The `result` field is shown for documentation but
 * excluded from strict comparison since the exact version string changes per release.
 */
class DapLldbVersionTest {

    /**
     * Evaluate response body from lldb-dap (repl context).
     *
     * Note: lldb-dap echoes the prompt and command (`(lldb) version\n`) before the
     * output, and includes full git revision, clang revision, and llvm revision lines.
     * The `result` value changes with each LLVM release and is excluded from strict
     * comparison — it is validated separately by asserting it contains "lldb version".
     */
    private val expectedLldbDapEvaluateBodyBaseline = """
        {
          "result": "(lldb) version\nlldb version 18.1.8 (https://github.com/llvm/llvm-project.git revision 3b5b5c1ec4a3095ab096dd780e84d7ab81f3d7ff)\n  clang revision 3b5b5c1ec4a3095ab096dd780e84d7ab81f3d7ff\n  llvm revision 3b5b5c1ec4a3095ab096dd780e84d7ab81f3d7ff\n",
          "variablesReference": 0
        }
    """.trimIndent()

    /**
     * Evaluate response body from CodeLLDB (_command context).
     *
     * Note: CodeLLDB returns a clean single-line version string without the prompt
     * echo or revision details. The version tag includes "-codelldb" to distinguish
     * from upstream LLVM builds. The `result` value changes with each CodeLLDB release
     * and is excluded from strict comparison.
     */
    private val expectedCodeLldbEvaluateBodyBaseline = """
        {
          "result": "lldb version 21.1.7-codelldb",
          "variablesReference": 0
        }
    """.trimIndent()

    @ParameterizedTest(name = "{0}")
    @EnumSource(ConnectionMode::class)
    fun `evaluate version command returns lldb version string`(mode: ConnectionMode) {
        assumeTrue(mode.serverKind.supportsEvaluate) {
            "${mode.serverKind} does not yet support evaluate"
        }

        mode.connect().use { ctx ->
            // 1. Initialize the DAP session (adapter loads liblldb during startup)
            DapTestUtils.sendInitializeRequest(ctx.outputStream)
            val initResponse = DapTestUtils.readDapMessage(ctx.inputStream)
            DapTestUtils.assertValidInitializeResponse(initResponse)

            // 2. Run "version" through the LLDB command interpreter (no launch needed).
            mode.serverKind.sendEvaluateRequest(ctx.outputStream, seq = 2, expression = "version")
            val evalResponse = DapTestUtils.readResponseForRequestSeq(ctx.inputStream, requestSeq = 2)

            // 3. The response must contain the LLDB version string, proving liblldb is alive
            val response = JSONObject(evalResponse)
            assertTrue(response.optBoolean("success", false)) {
                "evaluate 'version' should succeed ($mode): $evalResponse"
            }
            val body = response.getJSONObject("body")
            val result = body.getString("result")
            assertTrue(result.contains("lldb version", ignoreCase = true)) {
                "Expected LLDB version string from the command interpreter ($mode), got: $result"
            }

            // 4. Compare the full response body against baseline to document structural
            //    differences between server types. "result" is shown in the baseline for
            //    documentation but excluded from strict comparison (version string is dynamic).
            val bodyBaseline = when (mode.serverKind) {
                ServerKind.LLDB_DAP -> expectedLldbDapEvaluateBodyBaseline
                ServerKind.CODELDB -> expectedCodeLldbEvaluateBodyBaseline
                ServerKind.OUR_SERVER -> error("unreachable")
            }
            val diff = DapTestUtils.compareJsonStrict(body, bodyBaseline, excludeKeys = setOf("result"))
            assertTrue(diff == null) {
                "Evaluate response body differs from baseline ($mode):\n$diff\n\nActual body:\n${body.toString(2)}"
            }
        }
    }
}

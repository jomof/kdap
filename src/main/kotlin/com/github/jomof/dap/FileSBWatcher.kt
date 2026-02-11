package com.github.jomof.dap

import org.json.JSONObject
import java.io.File
import java.io.PrintWriter

/**
 * An [SBWatcher] that writes each SB API call as a single line to a file.
 *
 * Each line has the format:
 * ```
 * SBDebugger.createTarget(program="/path/to/hello") -> SBTarget
 * SBTarget.launch(launchInfo=SBLaunchInfo) -> SBProcess(pid=12345)
 * SBProcess.state() -> Running
 * SBProcess.kill() -> ERROR: process not running
 * ```
 *
 * DAP output events are abbreviated for readability:
 * ```
 * ←backend No testcase was specified.
 * ```
 *
 * Thread-safe: writes are synchronized on the writer instance.
 *
 * @param file the output file (created or truncated on construction)
 */
class FileSBWatcher(file: File) : SBWatcher {
    private val writer = PrintWriter(file.bufferedWriter(), /* autoFlush = */ true)

    override fun onCall(interfaceName: String, methodName: String, args: String, result: String) {
        val argsStr = if (args.isEmpty()) "()" else "($args)"
        val line = "$interfaceName.$methodName$argsStr -> $result"
        synchronized(writer) {
            writer.println(line)
        }
    }

    override fun onMessage(direction: String, message: String) {
        val abbreviated = abbreviateMessage(message)
        synchronized(writer) {
            writer.println("$direction $abbreviated")
        }
    }

    /**
     * Abbreviates DAP event messages for readability:
     * - output events → just the output text
     * - exited events → `exited(exitCode)`
     * - terminated events → `terminated`
     * Other messages are returned as-is.
     */
    private fun abbreviateMessage(message: String): String {
        return try {
            val json = JSONObject(message)
            if (json.optString("type") != "event") return message
            when (json.optString("event")) {
                "output" -> {
                    val body = json.optJSONObject("body") ?: return message
                    body.optString("output", "").trimEnd('\r', '\n')
                }
                "exited" -> {
                    val exitCode = json.optJSONObject("body")?.optInt("exitCode", -1) ?: -1
                    "exited($exitCode)"
                }
                "terminated" -> "terminated"
                else -> message
            }
        } catch (_: Exception) {
            message
        }
    }
}

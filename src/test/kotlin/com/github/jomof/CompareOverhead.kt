package com.github.jomof

import org.json.JSONObject
import java.io.InputStream
import java.io.OutputStream
import java.io.PrintStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/**
 * Benchmarks the overhead of KDAP's proxy layer by comparing pipelined
 * evaluate request throughput across connection modes.
 *
 * Each mode: start one server, open one connection, blast N evaluate("version")
 * requests without waiting, then collect all N responses. Measures per-request
 * latency (send→receive matched by seq) and overall throughput.
 *
 * Run from IDE (click play next to main) or via Gradle:
 *   ./gradlew benchmark
 *   ./gradlew benchmark -Pn=500
 */
fun main(args: Array<String>) {
    var n = 500
    var i = 0
    while (i < args.size) {
        when (args[i]) {
            "-n" -> { n = args[++i].toInt() }
        }
        i++
    }

    val console: PrintStream = System.out
    fun log(msg: String) { console.println(msg); console.flush() }

    log("")
    log("KDAP Proxy Overhead Benchmark (pipelined)")
    log("═".repeat(72))
    log("  N = $n requests per mode (single server, single connection)")
    log("")

    val modes = ConnectionMode.entries.toList()
    val results = mutableListOf<ModeResult>()
    val total = modes.size
    val perModeTimeout = 60L

    for ((idx, mode) in modes.withIndex()) {
        val label = mode.label()
        val prefix = "  [${idx + 1}/$total] $label"

        val resultRef = AtomicReference<ModeResult?>(null)
        val errorRef = AtomicReference<Throwable?>(null)
        val benchThread = thread(name = "bench-$mode") {
            try {
                resultRef.set(benchmark(mode, n))
            } catch (e: Throwable) {
                errorRef.set(e)
            }
        }

        var seconds = 0
        while (benchThread.isAlive) {
            benchThread.join(1000)
            if (benchThread.isAlive) {
                seconds++
                if (seconds >= perModeTimeout) {
                    log("$prefix  TIMEOUT after ${seconds}s")
                    benchThread.interrupt()
                    benchThread.join(2000)
                    break
                }
                log("$prefix  ... ${seconds}s")
            }
        }

        val err = errorRef.get()
        val result = resultRef.get()
        when {
            seconds >= perModeTimeout -> {
                results.add(ModeResult(mode, emptyList(), 0, "timeout after ${perModeTimeout}s"))
            }
            err != null -> {
                results.add(ModeResult(mode, emptyList(), 0, err.message?.take(80)))
                log("$prefix  SKIP: ${err.message?.take(60)}")
            }
            result != null -> {
                results.add(result)
                val lat = result.latencies
                log("$prefix  done: %,d reqs in %,d ms (avg %.1f ms)".format(
                    lat.size, result.totalMs, lat.average()
                ))
            }
        }
    }

    printReport(results, n, console)
}

// ─── Data ────────────────────────────────────────────────────────────────

private data class ModeResult(
    val mode: ConnectionMode,
    val latencies: List<Double>,   // sorted, ms
    val totalMs: Long,
    val error: String? = null,
)

/** Human-readable label: "kdap (stdio)", "lldb-dap (tcp)", "codelldb (stdio)", etc. */
private fun ConnectionMode.label(): String {
    val product = when (serverKind) {
        ServerKind.OUR_SERVER -> "kdap"
        ServerKind.LLDB_DAP  -> "lldb-dap"
        ServerKind.CODELDB   -> "codelldb"
    }
    val transport = when (this) {
        ConnectionMode.STDIO, ConnectionMode.STDIO_LLDB, ConnectionMode.STDIO_CODELDB -> "stdio"
        ConnectionMode.TCP_LISTEN   -> "tcp-listen"
        ConnectionMode.TCP_CONNECT  -> "tcp-connect"
        ConnectionMode.TCP_CODELDB  -> "tcp"
        ConnectionMode.IN_PROCESS   -> "in-process/tcp"
    }
    return "$product ($transport)"
}

// ─── Benchmark ───────────────────────────────────────────────────────────

/**
 * Blasts N requests without waiting for responses, then collects all N
 * responses. Per-request latency = response_received - request_sent,
 * matched by DAP sequence number.
 */
private fun benchmark(mode: ConnectionMode, n: Int): ModeResult {
    mode.connect().use { ctx ->
        initializeSession(ctx, mode)

        // Warmup (sequential, not measured)
        repeat(5) { seq ->
            sendAndReceiveEvaluate(ctx.inputStream, ctx.outputStream, mode, seq + 1)
        }

        // Track send time per seq
        val sendTimes = ConcurrentHashMap<Int, Long>() // seq → nanoTime
        val seqStart = 100
        val writerError = AtomicReference<Throwable?>(null)
        val writerDone = CountDownLatch(1)

        val startTime = System.nanoTime()

        // Writer thread: blast all N requests as fast as possible
        val writerThread = thread(name = "pipelined-writer") {
            try {
                for (i in 0 until n) {
                    val seq = seqStart + i
                    sendTimes[seq] = System.nanoTime()
                    mode.serverKind.testServer.sendEvaluateRequest(
                        ctx.outputStream, seq, "version"
                    )
                }
            } catch (e: Throwable) {
                writerError.set(e)
            } finally {
                writerDone.countDown()
            }
        }

        // Reader: collect N responses on the current thread
        val latencies = mutableListOf<Double>()
        var collected = 0
        while (collected < n) {
            val message = DapTestUtils.readDapMessage(ctx.inputStream)
            val json = JSONObject(message)
            val receiveTime = System.nanoTime()
            if (json.optString("type") == "response") {
                val reqSeq = json.optInt("request_seq", -1)
                val sentAt = sendTimes[reqSeq]
                if (sentAt != null) {
                    latencies.add((receiveTime - sentAt) / 1_000_000.0)
                    collected++
                }
            }
            // Events (e.g. output) are silently skipped
        }

        val totalMs = (System.nanoTime() - startTime) / 1_000_000

        writerDone.await()
        writerError.get()?.let { throw it as Exception }

        return ModeResult(mode, latencies.sorted(), totalMs)
    }
}

// ─── Report ──────────────────────────────────────────────────────────────

private fun printReport(results: List<ModeResult>, n: Int, console: PrintStream) {
    fun log(msg: String) { console.println(msg); console.flush() }

    log("")
    log("═".repeat(72))
    log("Results (N=$n, pipelined)")
    log("─".repeat(72))
    log(
        "%-22s  %7s  %7s  %7s  %7s  %9s  %7s".format(
            "Mode", "Avg(ms)", "P50(ms)", "P95(ms)", "P99(ms)", "Total(ms)", "Req/s"
        )
    )
    log("─".repeat(72))

    for (r in results) {
        val label = r.mode.label()
        if (r.error != null) {
            log("%-22s  %-48s".format(label, "(skipped: ${r.error})"))
            continue
        }
        val lat = r.latencies
        if (lat.isEmpty()) continue
        val avg = lat.average()
        val p50 = percentile(lat, 50)
        val p95 = percentile(lat, 95)
        val p99 = percentile(lat, 99)
        val reqPerSec = if (r.totalMs > 0) lat.size * 1000.0 / r.totalMs else 0.0
        log(
            "%-22s  %7.1f  %7.1f  %7.1f  %7.1f  %,9d  %,7.0f".format(
                label, avg, p50, p95, p99, r.totalMs, reqPerSec
            )
        )
    }

    // Overhead vs lldb-dap (stdio) baseline
    val baseline = results.find { it.mode == ConnectionMode.STDIO_LLDB && it.error == null }
    if (baseline != null && baseline.latencies.isNotEmpty()) {
        val baseAvg = baseline.latencies.average()
        log("")
        log("Overhead vs lldb-dap (stdio) — direct, no proxy:")
        for (r in results) {
            if (r.error != null || r.latencies.isEmpty()) continue
            if (r.mode == ConnectionMode.STDIO_LLDB) continue
            val label = r.mode.label()
            val avg = r.latencies.average()
            val overhead = avg - baseAvg
            val pct = overhead / baseAvg * 100
            val sign = if (pct >= 0) "+" else ""
            val bar = if (pct >= 0) "▓".repeat(minOf((pct / 5).toInt(), 30)) else ""
            log(
                "  %-22s  %s%5.0f%%  (%+.1f ms)  %s".format(
                    label, sign, pct, overhead, bar
                )
            )
        }
    }

    // Head-to-head: kdap (stdio) vs codelldb (stdio)
    val codelldb = results.find { it.mode == ConnectionMode.STDIO_CODELDB && it.error == null }
    val kdapStdio = results.find { it.mode == ConnectionMode.STDIO && it.error == null }
    if (codelldb != null && kdapStdio != null &&
        codelldb.latencies.isNotEmpty() && kdapStdio.latencies.isNotEmpty()
    ) {
        val codelldbAvg = codelldb.latencies.average()
        val kdapAvg = kdapStdio.latencies.average()
        val diff = kdapAvg - codelldbAvg
        val pct = diff / codelldbAvg * 100
        val sign = if (pct >= 0) "+" else ""
        log("")
        log(
            "kdap vs codelldb (stdio): %s%.0f%% (%+.1f ms, %.1f vs %.1f ms avg)".format(
                sign, pct, diff, kdapAvg, codelldbAvg
            )
        )
    }

    log("")
}

private fun percentile(sorted: List<Double>, p: Int): Double {
    val idx = minOf(sorted.size * p / 100, sorted.size - 1)
    return sorted[idx]
}

// ─── DAP helpers ─────────────────────────────────────────────────────────

private fun initializeSession(ctx: ConnectionContext, mode: ConnectionMode) {
    val response = ctx.initializeResponse ?: run {
        DapTestUtils.sendInitializeRequest(ctx.outputStream)
        DapTestUtils.readDapMessage(ctx.inputStream)
    }
    val json = JSONObject(response)
    check(json.optBoolean("success", false)) {
        "Initialize failed for $mode: $response"
    }
}

private fun sendAndReceiveEvaluate(
    input: InputStream,
    output: OutputStream,
    mode: ConnectionMode,
    seq: Int,
) {
    mode.serverKind.testServer.sendEvaluateRequest(output, seq, "version")
    DapTestUtils.readResponseForRequestSeq(input, seq)
}

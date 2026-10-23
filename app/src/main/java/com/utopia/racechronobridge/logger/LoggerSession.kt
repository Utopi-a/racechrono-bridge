package com.utopia.racechronobridge.logger

import com.utopia.racechronobridge.ssm2.Ssm2Client
import com.utopia.racechronobridge.ssm2.Ssm2Sampler

data class LoggerStats(
    val samples: Long,
    /** 直近 [RATE_WINDOW] 回の平均。 */
    val samplesPerSecond: Double,
    val requestsPerSample: Int,
    val failures: Long,
) {
    companion object {
        const val RATE_WINDOW = 20
    }
}

/**
 * プリセットの列を SSM2 で読み続け、CSV に書く。止めるまで待ち時間なしで読む（速さは往復の回数で決まる）。
 *
 * 1 回の読み出し失敗では止めず、[MAX_CONSECUTIVE_FAILURES] 回続いたら例外で終わる。
 * 変化の遅い列は [SLOW_EVERY] 回に 1 回読み、その間は前回値を CSV に書く。
 */
class LoggerSession(
    client: Ssm2Client,
    private val layout: LogLayout,
    private val writer: RomRaiderCsvWriter,
    private val clock: () -> Long = System::currentTimeMillis,
    private val onRecord: (LogRecord, LoggerStats) -> Unit = { _, _ -> },
    private val onLog: (String) -> Unit = {},
) {
    private val fast = Ssm2Sampler(client, layout.fastAddresses, clock, onLog)
    private val slow = layout.slowAddresses.takeIf { it.isNotEmpty() }?.let { Ssm2Sampler(client, it, clock, onLog) }

    @Volatile
    private var stopRequested = false

    fun requestStop() {
        stopRequested = true
    }

    fun run() {
        writer.writeHeader()
        val started = clock()
        var lastFlush = started
        var samples = 0L
        var failures = 0L
        var consecutiveFailures = 0
        val slowBytes = HashMap<Int, Int>()
        val recentTimes = ArrayDeque<Long>()
        onLog("Logger started: preset ${layout.preset.key} ${layout.columns.size} columns, fast plan ${fast.plan.size} requests" +
            (slow?.let { ", slow plan ${it.plan.size} requests every $SLOW_EVERY samples" } ?: ""))
        while (!stopRequested) {
            try {
                var requests = 0
                if (slow != null && samples % SLOW_EVERY == 0L) {
                    val s = slow.sample()
                    slowBytes.putAll(s.bytes)
                    requests += s.requests
                }
                val f = fast.sample()
                requests += f.requests
                val now = clock()
                val record = layout.decode(f.bytes + slowBytes, elapsedMillis = now - started)
                writer.write(record)
                samples++
                consecutiveFailures = 0
                recentTimes.addLast(now)
                if (recentTimes.size > LoggerStats.RATE_WINDOW) recentTimes.removeFirst()
                val span = recentTimes.last() - recentTimes.first()
                val rate = if (recentTimes.size > 1 && span > 0) (recentTimes.size - 1) * 1000.0 / span else 0.0
                onRecord(record, LoggerStats(samples, rate, requests, failures))
                if (now - lastFlush >= FLUSH_INTERVAL_MILLIS) {
                    writer.flush()
                    lastFlush = now
                }
            } catch (error: InterruptedException) {
                Thread.currentThread().interrupt()
                break
            } catch (error: Exception) {
                failures++
                consecutiveFailures++
                onLog("Logger sample failed ($consecutiveFailures in a row): ${error.message}")
                if (consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
                    writer.flush()
                    throw IllegalStateException("logger stopped after $consecutiveFailures consecutive failures: ${error.message}", error)
                }
            }
        }
        writer.flush()
        onLog("Logger stopped after $samples samples ($failures failures).")
    }

    companion object {
        const val SLOW_EVERY = 10
        const val MAX_CONSECUTIVE_FAILURES = 10
        private const val FLUSH_INTERVAL_MILLIS = 1_000L
    }
}

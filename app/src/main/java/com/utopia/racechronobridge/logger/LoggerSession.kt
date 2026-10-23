package com.utopia.racechronobridge.logger

import com.utopia.racechronobridge.ssm2.Ssm2Client
import com.utopia.racechronobridge.ssm2.Ssm2Sampler

data class LoggerStats(
    val samples: Long,
    /** 直近 [RATE_WINDOW] 回の平均。 */
    val samplesPerSecond: Double,
    val requestsPerSample: Int,
    val failures: Long,
    /** ブロック読み出しを A8 に切り替えた箇所があり、遅くなっている。 */
    val degraded: Boolean,
) {
    companion object {
        const val RATE_WINDOW = 20
    }
}

/**
 * プリセットの列を SSM2 で読み続け、CSV に書く。止めるまで待ち時間なしで読む（速さは往復の回数で決まる）。
 *
 * - 失敗したら、遅れて届く応答を読み捨てて通信を立て直してから続ける（要求と応答が 1 つずれたまま記録しないため）
 * - 失敗が [MAX_CONSECUTIVE_FAILURES] 回続いたら例外で終わる
 * - 変化の遅い列は [SLOW_EVERY] 回に 1 回読み、その間は前回値を書く。遅い列の失敗では速い列を止めない
 */
class LoggerSession(
    private val client: Ssm2Client,
    private val layout: LogLayout,
    private val writer: RomRaiderCsvWriter,
    /** 経過時間の基準。壁時計の補正で飛ばないよう、実機では SystemClock.elapsedRealtime を渡す。 */
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
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
        var slowDue = true
        val slowBytes = HashMap<Int, Int>()
        val recentTimes = ArrayDeque<Long>()
        onLog("Logger started: preset ${layout.preset.key} ${layout.columns.size} columns, fast plan ${fast.plan.size} requests" +
            (slow?.let { ", slow plan ${it.plan.size} requests every $SLOW_EVERY samples" } ?: ""))
        while (!stopRequested) {
            var requests = 0
            if (slow != null && slowDue) {
                try {
                    val s = slow.sample()
                    slowBytes.putAll(s.bytes)
                    requests += s.requests
                    slowDue = false
                } catch (error: Exception) {
                    if (error is InterruptedException) throw error
                    failures++
                    onLog("Logger slow sample failed: ${error.message}")
                    resynchronizeQuietly()
                    // 遅い列は前回値のまま、速い列の記録を続ける。次の周期で読み直す
                }
            }
            try {
                val f = fast.sample()
                requests += f.requests
                val now = clock()
                // 同じアドレスなら、今回読んだ速い列の値を優先する
                val record = layout.decode(slowBytes + f.bytes, elapsedMillis = now - started)
                writer.write(record)
                samples++
                if (samples % SLOW_EVERY == 0L) slowDue = true
                consecutiveFailures = 0
                recentTimes.addLast(now)
                if (recentTimes.size > LoggerStats.RATE_WINDOW) recentTimes.removeFirst()
                val span = recentTimes.last() - recentTimes.first()
                val rate = if (recentTimes.size > 1 && span > 0) (recentTimes.size - 1) * 1000.0 / span else 0.0
                val degraded = fast.degraded || slow?.degraded == true
                onRecord(record, LoggerStats(samples, rate, requests, failures, degraded))
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
                resynchronizeQuietly()
            }
        }
        writer.flush()
        onLog("Logger stopped after $samples samples ($failures failures).")
    }

    private fun resynchronizeQuietly() {
        runCatching { client.resynchronize() }.onFailure { onLog("Resync failed: ${it.message}") }
    }

    companion object {
        const val SLOW_EVERY = 10
        const val MAX_CONSECUTIVE_FAILURES = 10
        private const val FLUSH_INTERVAL_MILLIS = 1_000L
    }
}

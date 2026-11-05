package com.utopia.racechronobridge.ssm2

import com.utopia.racechronobridge.elm.Elm327Session
import com.utopia.racechronobridge.elm.Elm327Transport
import com.utopia.racechronobridge.elm.ElmResponseParser

/**
 * 確かめる読み出し。標準パラメータのアドレス、RAM（拡張パラメータ）のアドレス、RAM のブロック、
 * 1 回の要求でまとめて読む標準パラメータのアドレス。
 */
data class Ssm2LinkProbeTargets(
    val standardAddress: Int,
    val ramAddress: Int,
    val ramBlock: Ssm2Read.Block,
    val batchAddresses: List<Int>,
)

enum class Ssm2LinkCheckKind(val label: String) {
    IDENTIFY("識別"),
    STANDARD("標準"),
    RAM_ADDRESS("RAM"),
    RAM_BLOCK("ブロック"),

    /** 複数のアドレスを 1 回の A8 で読む。ELM327 は 7 バイトまでしか送れないので、STN 系の STPX が使えるかが要。 */
    BATCH("まとめ読み"),
}

enum class Ssm2LinkOutcome(val label: String) {
    OK("○"),

    /** ECU が 7F で断った。 */
    REJECTED("拒否"),

    /** 応答が無い・壊れているなど。 */
    FAILED("×"),
}

data class Ssm2LinkCheck(
    val kind: Ssm2LinkCheckKind,
    val outcome: Ssm2LinkOutcome,
    /** 成功なら値、失敗なら理由（ELM327 の応答を含む）。 */
    val detail: String,
    /** 成功したときの 1 回あたりの往復時間。 */
    val averageMillis: Long? = null,
    /** 表示名。まとめ読みは読んだアドレスの数を付ける。 */
    val label: String = kind.label,
)

/** アダプターが名乗った内容。[stnId] は STN 系（OBDLink と同じ ST コマンド）に答えたときだけ入る。 */
data class Ssm2AdapterInfo(
    val elmVersion: String,
    val stnId: String?,
) {
    fun summary(): String = "アダプター: $elmVersion / ST コマンド " + (stnId?.let { "あり（$it）" } ?: "なし")
}

data class Ssm2LinkProbeResult(
    val adapter: Ssm2AdapterInfo,
    val reports: List<Ssm2LinkReport>,
) {
    fun summary(): String = (listOf(adapter.summary()) + reports.map { it.summary() }).joinToString("\n")
}

data class Ssm2LinkReport(
    val link: Ssm2Link,
    /** ELM327 をこの経路に設定できなかった理由。null なら設定できた。 */
    val setupFailure: String? = null,
    val checks: List<Ssm2LinkCheck> = emptyList(),
) {
    fun outcome(kind: Ssm2LinkCheckKind): Ssm2LinkOutcome? = checks.firstOrNull { it.kind == kind }?.outcome

    /** 例: `K-line: 識別 ○ / 標準 ○ 48 ms / RAM ○ 52 ms / ブロック ○ 170 ms` */
    fun summary(): String {
        setupFailure?.let { return "${link.label}: 設定できず（$it）" }
        return "${link.label}: " + checks.joinToString(" / ") { c ->
            "${c.label} ${c.outcome.label}" + (c.averageMillis?.let { " $it ms" } ?: "")
        }
    }
}

/**
 * CAN と K-line のそれぞれで、ECU 識別・標準パラメータ・RAM の 1 バイト・RAM のブロック・まとめ読みができるかを確かめ、
 * 往復時間を測る。拡張パラメータが読めない原因（経路か、アダプターか、ECU か）と、アダプターで速く読む手があるかを実車で切り分ける。
 */
class Ssm2LinkProbe(
    private val transport: Elm327Transport,
    private val session: Elm327Session,
    private val clock: () -> Long = System::currentTimeMillis,
    private val onLog: (String) -> Unit = {},
) {
    private val parser = ElmResponseParser()

    /**
     * ELM327 は CAN の SSM2 用に設定しておく。終わったとき ELM327 は K-line のままなので、
     * 呼び出し側で [Elm327Session.initialize] し直して CAN に戻す。
     */
    fun run(targets: Ssm2LinkProbeTargets): Ssm2LinkProbeResult {
        val adapter = identifyAdapter()
        val can = check(Ssm2Client(transport, link = Ssm2Link.CAN), targets, adapter)
        val kLine = try {
            session.switchToKLine()
            check(Ssm2Client(transport, link = Ssm2Link.KLINE), targets, adapter)
        } catch (error: Exception) {
            if (error is InterruptedException) throw error
            onLog("K-line setup failed: ${error.message}")
            Ssm2LinkReport(Ssm2Link.KLINE, setupFailure = error.message ?: error.javaClass.simpleName)
        }
        return Ssm2LinkProbeResult(adapter, listOf(can, kLine))
    }

    /**
     * 版と、STN 系の ST コマンドに答えるかを聞く。ELM327 互換品は知らないコマンドに `?` を返す。
     * 何にでも `OK` を返す互換品もあるので、`OK` だけの答えは名乗りとみなさない。
     */
    private fun identifyAdapter(): Ssm2AdapterInfo {
        fun ask(command: String): String {
            val raw = runCatching { transport.sendCommand(command, ADAPTER_QUERY_TIMEOUT_MILLIS) }.getOrDefault("")
            return raw.replace(">", "").lines().map(String::trim).filter(String::isNotEmpty).joinToString(" ")
        }
        val elm = ask("ATI")
        val description = ask("AT@1")
        val sti = ask("STI")
        val stdi = ask("STDI")
        onLog("Adapter: ATI=$elm, AT@1=$description, STI=$sti, STDI=$stdi")
        val answered = sti.isNotEmpty() && sti != "OK" && !parser.isElmError(sti)
        return Ssm2AdapterInfo(elmVersion = elm, stnId = if (answered) sti else null)
    }

    private fun check(client: Ssm2Client, targets: Ssm2LinkProbeTargets, adapter: Ssm2AdapterInfo): Ssm2LinkReport {
        val block = targets.ramBlock
        // STPX が無ければ ELM327 の上限（K-line で ATAL のとき 8 バイト = 2 アドレス）で試す
        val viaStpx = adapter.stnId != null
        val batch = if (viaStpx) targets.batchAddresses else targets.batchAddresses.take(ELM_BATCH_ADDRESSES)
        val checks = listOf(
            attempt(client, Ssm2LinkCheckKind.IDENTIFY, times = 1) { "ECU ${client.identify().ecuId}" },
            attempt(client, Ssm2LinkCheckKind.STANDARD, TIMED_READS) { hexByte(client.readAddress(targets.standardAddress)) },
            attempt(client, Ssm2LinkCheckKind.RAM_ADDRESS, TIMED_READS) { hexByte(client.readAddress(targets.ramAddress)) },
            attempt(client, Ssm2LinkCheckKind.RAM_BLOCK, TIMED_BLOCK_READS) {
                "${client.readBlock(block.start, block.count).size} bytes"
            },
            attempt(client, Ssm2LinkCheckKind.BATCH, TIMED_READS, label = "まとめ読み ${batch.size} 件${if (viaStpx) "（STPX）" else ""}") {
                client.readAddresses(batch, viaStpx).joinToString(" ") { hexByte(it) }
            },
        )
        return Ssm2LinkReport(client.link, checks = checks).also { onLog("Link probe ${it.summary()}") }
    }

    /** [times] 回続けて読み、平均の往復時間を測る。1 回でも失敗したらそこで止め、通信を立て直す。 */
    private fun attempt(
        client: Ssm2Client,
        kind: Ssm2LinkCheckKind,
        times: Int,
        label: String = kind.label,
        read: () -> String,
    ): Ssm2LinkCheck {
        val started = clock()
        return try {
            var detail = ""
            repeat(times) { detail = read() }
            val average = (clock() - started) / times
            onLog("Link probe ${client.link.label} ${kind.name}: $detail, $average ms")
            Ssm2LinkCheck(kind, Ssm2LinkOutcome.OK, detail, average, label)
        } catch (error: Exception) {
            if (error is InterruptedException) throw error
            val outcome = if (error is Ssm2RequestRejected) Ssm2LinkOutcome.REJECTED else Ssm2LinkOutcome.FAILED
            onLog("Link probe ${client.link.label} ${kind.name} ${outcome.name}: ${error.message}")
            runCatching { client.resynchronize() }
            Ssm2LinkCheck(kind, outcome, error.message ?: error.javaClass.simpleName, label = label)
        }
    }

    companion object {
        /** 1 アドレスの読み出しを続けて測る回数。K-line（4800 bps）でも 1 秒前後で終わる。 */
        const val TIMED_READS = 10
        const val TIMED_BLOCK_READS = 5

        /** ELM327 が 1 回で送れる A8（K-line・ATAL で 8 バイト）に入るアドレスの数。 */
        const val ELM_BATCH_ADDRESSES = 2
        private const val ADAPTER_QUERY_TIMEOUT_MILLIS = 1_000L

        private fun hexByte(value: Int) = "0x%02X".format(value)
    }
}

package com.utopia.racechronobridge.logger

import com.utopia.racechronobridge.elm.Elm327Session
import com.utopia.racechronobridge.elm.Elm327Transport
import com.utopia.racechronobridge.ssm2.Ssm2Client
import com.utopia.racechronobridge.ssm2.Ssm2Link
import com.utopia.racechronobridge.ssm2.Ssm2RequestRejected
import com.utopia.racechronobridge.ssm2.catalog.SsmParameter
import com.utopia.racechronobridge.ssm2.catalog.SsmParameterKind

/** 記録に使う経路と、その経路で RAM（拡張パラメータ）が読めるか。 */
data class LoggerLinkChoice(
    val client: Ssm2Client,
    val ramReadable: Boolean,
)

/**
 * 記録に使う経路を決める。CAN は往復が速いので、RAM が読めるか、読めなくても標準パラメータで同じ値が取れるなら CAN のまま。
 * CAN で RAM を拒否され、プリセットに標準で代われない拡張パラメータがあるときだけ、遅くても K-line を試す。
 * K-line で RAM が読めなければ ELM327 を CAN に戻し、標準パラメータで記録する。
 */
class LoggerLinkSelector(
    private val transport: Elm327Transport,
    private val session: Elm327Session,
    private val onLog: (String) -> Unit = {},
) {
    /** ELM327 は CAN の SSM2 用に設定しておく。 */
    fun select(preset: LogPreset, parameters: Map<String, SsmParameter>): LoggerLinkChoice {
        val can = Ssm2Client(transport, link = Ssm2Link.CAN)
        // 拒否以外の失敗は判断材料にならないので、読めるものとして進める（読めなければ記録中にその列だけ外れる）
        if (probeRam(can, preset, parameters) != RamAccess.REJECTED) return LoggerLinkChoice(can, ramReadable = true)
        val lost = LogLayout.lostWithoutRam(preset, parameters)
        if (lost.isEmpty()) {
            onLog("CAN rejects RAM reads; standard parameters cover preset ${preset.key}, staying on CAN.")
            return LoggerLinkChoice(can, ramReadable = false)
        }
        onLog("CAN rejects RAM reads; trying K-line for ${lost.joinToString()}.")
        openKLine(preset, parameters)?.let { return LoggerLinkChoice(it, ramReadable = true) }
        onLog("K-line is not usable; returning to CAN with standard parameters.")
        session.initialize()
        return LoggerLinkChoice(can, ramReadable = false)
    }

    private fun openKLine(preset: LogPreset, parameters: Map<String, SsmParameter>): Ssm2Client? {
        try {
            session.switchToKLine()
        } catch (error: Exception) {
            if (error is InterruptedException) throw error
            onLog("K-line setup failed: ${error.message}")
            return null
        }
        val kLine = Ssm2Client(transport, link = Ssm2Link.KLINE)
        // 切り替え直後の 1 回目は取りこぼすことがあるので、拒否でなければ 1 回だけやり直す
        repeat(K_LINE_PROBE_ATTEMPTS) {
            when (probeRam(kLine, preset, parameters)) {
                RamAccess.READABLE -> return kLine
                RamAccess.REJECTED -> return null
                RamAccess.UNKNOWN -> Unit
            }
        }
        return null
    }

    private enum class RamAccess { READABLE, REJECTED, UNKNOWN }

    /** プリセットの拡張パラメータを 1 バイト読んで、この経路で RAM が読めるかを確かめる。 */
    private fun probeRam(client: Ssm2Client, preset: LogPreset, parameters: Map<String, SsmParameter>): RamAccess {
        val probe = preset.parameters.firstNotNullOfOrNull { ref -> parameters[ref.id]?.takeIf { it.kind == SsmParameterKind.EXTENDED } }
            ?: return RamAccess.READABLE
        return try {
            client.readAddress(probe.byteAddresses.first())
            RamAccess.READABLE
        } catch (rejected: Ssm2RequestRejected) {
            onLog("RAM read probe ${probe.id} over ${client.link.label} rejected: ${rejected.message}")
            RamAccess.REJECTED
        } catch (error: Exception) {
            if (error is InterruptedException) throw error
            onLog("RAM read probe ${probe.id} over ${client.link.label} failed: ${error.message}")
            runCatching { client.resynchronize() }
            RamAccess.UNKNOWN
        }
    }

    private companion object {
        const val K_LINE_PROBE_ATTEMPTS = 2
    }
}

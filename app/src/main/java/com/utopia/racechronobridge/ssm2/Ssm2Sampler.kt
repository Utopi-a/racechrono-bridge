package com.utopia.racechronobridge.ssm2

sealed interface Ssm2Read {
    val addresses: List<Int>

    data class Block(val start: Int, val count: Int) : Ssm2Read {
        override val addresses: List<Int> = (start until start + count).toList()
    }

    data class Address(val address: Int) : Ssm2Read {
        override val addresses: List<Int> = listOf(address)
    }
}

/**
 * 読みたいアドレスの集合を、要求の並びにする。
 *
 * RAM（[BLOCK_READ_MIN_ADDRESS] 以上）の近いアドレスは A0 ブロックにまとめ、往復の回数を減らす。
 * 標準パラメータのアドレス（0x000000 付近）は ECU が個別に値を返す仮想アドレスなので、A8 で 1 つずつ読む。
 */
object Ssm2ReadPlanner {
    const val BLOCK_READ_MIN_ADDRESS = 0xFF0000

    /**
     * ブロックに含めてよい、読む必要の無いアドレスの連続数。これを超える隙間ではブロックを分ける。
     * 往復 1 回（BLE 越しに数十 ms）に比べ、余分な数十バイトの受信（CAN は数フレーム、BLE は数 ms）は安い。
     */
    const val MAX_GAP = 48

    /** 1 ブロックの上限。長い応答は BLE での受け取りに時間がかかり、ELM327 のバッファも溢れやすい。 */
    const val MAX_BLOCK_BYTES = 128

    fun plan(addresses: Collection<Int>, addressOnly: Set<Int> = emptySet()): List<Ssm2Read> {
        val sorted = addresses.toSortedSet()
        val (blockable, single) = sorted.partition { it >= BLOCK_READ_MIN_ADDRESS && it !in addressOnly }
        val reads = mutableListOf<Ssm2Read>()
        var start = -1
        var end = -1
        for (a in blockable) {
            when {
                start < 0 -> { start = a; end = a }
                a - end - 1 <= MAX_GAP && a - start + 1 <= MAX_BLOCK_BYTES -> end = a
                else -> { reads += Ssm2Read.Block(start, end - start + 1); start = a; end = a }
            }
        }
        if (start >= 0) reads += Ssm2Read.Block(start, end - start + 1)
        reads += single.map(Ssm2Read::Address)
        return reads
    }
}

data class Ssm2Sample(
    /** アドレス → 生のバイト値。 */
    val bytes: Map<Int, Int>,
    val requests: Int,
    val elapsedMillis: Long,
)

/**
 * 決めたアドレスを毎回読み、ブロック読み出しが失敗したら、そのブロックだけを A8 に切り替える。
 * 切り替えはこのインスタンスが続く間は保たれる（失敗するブロックを毎回送って遅くしないため）。
 */
class Ssm2Sampler(
    private val client: Ssm2Client,
    addresses: Collection<Int>,
    private val clock: () -> Long = System::currentTimeMillis,
    private val onLog: (String) -> Unit = {},
) {
    private val wanted = addresses.toSet()
    private val addressOnly = mutableSetOf<Int>()
    var plan: List<Ssm2Read> = Ssm2ReadPlanner.plan(wanted)
        private set

    fun sample(): Ssm2Sample {
        val started = clock()
        val bytes = HashMap<Int, Int>(wanted.size * 2)
        var requests = 0
        val queue = ArrayDeque(plan)
        while (queue.isNotEmpty()) {
            when (val read = queue.removeFirst()) {
                is Ssm2Read.Address -> {
                    requests++
                    bytes[read.address] = client.readAddress(read.address)
                }
                is Ssm2Read.Block -> {
                    requests++
                    try {
                        client.readBlock(read.start, read.count).forEachIndexed { i, b -> bytes[read.start + i] = b }
                    } catch (error: Ssm2RequestFailed) {
                        val fallback = read.addresses.filter { it in wanted }
                        addressOnly += fallback
                        plan = Ssm2ReadPlanner.plan(wanted, addressOnly)
                        onLog("Block read 0x${read.start.toString(16).uppercase()}+${read.count} failed (${error.message}); using A8 for ${fallback.size} addresses.")
                        fallback.forEach { queue.addFirst(Ssm2Read.Address(it)) }
                    }
                }
            }
        }
        return Ssm2Sample(bytes = bytes.filterKeys { it in wanted }, requests = requests, elapsedMillis = clock() - started)
    }
}

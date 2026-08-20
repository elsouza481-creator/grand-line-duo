package grandlineduo.sim

import java.util.PriorityQueue
import java.util.Random

data class NetworkProfile(
    val maxDelayTicks: Int,
    val dropPercent: Int,
    val duplicatePercent: Int,
) {
    init {
        require(maxDelayTicks >= 0)
        require(dropPercent in 0..100)
        require(duplicatePercent in 0..100)
    }
}

class AdverseNetwork<T>(
    seed: Long,
    private val profile: NetworkProfile,
) {
    private data class Scheduled<T>(
        val deliverAt: Long,
        val ordinal: Long,
        val value: T,
    )

    private val random = Random(seed)
    private val queue = PriorityQueue<Scheduled<T>>(
        compareBy<Scheduled<T>> { it.deliverAt }.thenBy { it.ordinal }
    )
    private var tick = 0L
    private var ordinal = 0L
    private var connected = true

    fun send(value: T) {
        if (!connected) return
        if (roll(profile.dropPercent)) return
        schedule(value)
        if (roll(profile.duplicatePercent)) schedule(value)
    }

    fun advance(ticks: Int, deliver: (T) -> Unit) {
        require(ticks >= 0)
        tick += ticks
        while (queue.isNotEmpty() && queue.peek().deliverAt <= tick) {
            deliver(queue.remove().value)
        }
    }

    fun drain(deliver: (T) -> Unit) {
        while (queue.isNotEmpty()) {
            val target = queue.peek().deliverAt
            tick = maxOf(tick, target)
            advance(0, deliver)
        }
    }

    fun disconnect() {
        connected = false
        queue.clear()
    }

    fun reconnect() {
        connected = true
    }

    private fun schedule(value: T) {
        val delay = if (profile.maxDelayTicks == 0) 0 else random.nextInt(profile.maxDelayTicks + 1)
        queue += Scheduled(tick + delay, ordinal++, value)
    }

    private fun roll(percent: Int): Boolean =
        percent > 0 && random.nextInt(100) < percent
}

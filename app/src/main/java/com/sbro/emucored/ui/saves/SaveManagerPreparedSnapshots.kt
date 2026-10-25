package com.sbro.emucored.ui.saves

/** One-shot snapshots never cross screen contexts; epochs reject cancelled/superseded scans. */
internal class SaveManagerPreparedSnapshots<T>(
    private val capacity: Int = 4,
    private val clockMs: () -> Long = { System.nanoTime() / 1_000_000 },
    private val maximumAgeMs: Long = 10_000
) {
    private var generation = 0L
    private val requests = linkedMapOf<String, Long>()
    private val ready = linkedMapOf<String, Pair<T, Long>>()

    @Synchronized fun begin(key: String): Long {
        ready.remove(key)
        requests.remove(key)
        requests[key] = ++generation
        while (requests.size > capacity) {
            val oldest = requests.keys.first()
            requests.remove(oldest)
            ready.remove(oldest)
        }
        return generation
    }

    @Synchronized fun publish(key: String, request: Long, snapshot: T): Boolean {
        if (requests[key] != request) return false
        ready[key] = snapshot to clockMs()
        return true
    }

    @Synchronized fun take(key: String): T? {
        requests.remove(key)
        val (snapshot, published) = ready.remove(key) ?: return null
        return snapshot.takeIf { clockMs() - published in 0..maximumAgeMs }
    }

    @Synchronized fun clear() { requests.clear(); ready.clear() }
}

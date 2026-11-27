package dev.photowidget.engine

import dev.photowidget.model.PhotoRef
import dev.photowidget.model.PhotoStat
import kotlin.math.ceil
import kotlin.random.Random

/**
 * Input for one selection round.
 *
 * Everything needed for a decision is passed in, so the engine is pure, testable
 * and safe to run from a widget broadcast receiver.
 */
data class ShuffleRequest(
    val candidates: List<PhotoRef>,
    val stats: Map<String, PhotoStat> = emptyMap(),
    val cycleId: Long = 1L,
    /** 0..100: percentage of the most recently shown photos to avoid at cycle start. */
    val cooldownPercent: Int = 20,
    /** The widget's refresh interval; used to normalise "time unseen". */
    val intervalSeconds: Long = 3600L,
    val now: Long = 0L,
    val random: Random = Random.Default,
)

/** Result of a selection round. [keys] are ordered and unique. */
data class ShuffleOutcome(
    val keys: List<String>,
    val cycleId: Long,
    val startedNewCycle: Boolean = false,
    /** True when the cooldown had to be relaxed so the widget could not stall. */
    val cooldownRelaxed: Boolean = false,
)

/**
 * Smart Shuffle.
 *
 * Goals, in priority order:
 * 1. Never stall, even with 1 or 2 photos or a 100% cooldown.
 * 2. Show every available photo once per cycle before any repeat. New photos
 *    join the current cycle immediately.
 * 3. At a cycle boundary, avoid the most recently shown `cooldownPercent` of
 *    photos; if that would leave no choice the cooldown is relaxed.
 * 4. Among eligible photos, prefer ones unseen for longer and shown less often.
 *
 * All state (last shown time, display count, recent position, cycle membership)
 * is supplied by the caller and persisted per widget; the engine itself is
 * stateless, which keeps widgets independent and crash safe.
 */
class SmartShuffleEngine {

    fun pick(request: ShuffleRequest, count: Int = 1): ShuffleOutcome {
        val available = request.candidates.distinctBy { it.key }
        if (available.isEmpty()) return ShuffleOutcome(emptyList(), request.cycleId)
        if (available.size == 1) {
            return ShuffleOutcome(listOf(available.first().key), request.cycleId)
        }

        val want = count.coerceIn(1, available.size)
        val stats = request.stats
        val requestedWindow = requestedCooldownWindow(request.cooldownPercent, available.size)
        val cooldownWindow = cooldownWindowSize(request.cooldownPercent, available.size)
        val recentKeys = recentKeys(available, stats, cooldownWindow)

        // Photos already shown in the current cycle, according to persisted state.
        val shownThisCycle = HashSet<String>()
        for (candidate in available) {
            if (stats[candidate.key]?.shownInCycle == true) shownThisCycle.add(candidate.key)
        }

        val picked = ArrayList<String>(want)
        val pickedSet = HashSet<String>(want * 2)
        var cycleId = request.cycleId
        var startedNewCycle = false
        // A small collection cannot protect the full requested percentage without
        // stalling, so the window is clamped. Report that as a relaxation.
        var relaxed = requestedWindow > cooldownWindow

        while (picked.size < want) {
            var pool = available.filter { it.key !in pickedSet && it.key !in shownThisCycle }
            if (pool.isEmpty()) {
                // Cycle complete: start a new one. Photos picked earlier in this
                // batch stay excluded so a collage never repeats a photo.
                cycleId += 1
                startedNewCycle = true
                shownThisCycle.clear()
                pool = available.filter { it.key !in pickedSet }
                if (pool.isEmpty()) break
            }

            // Apply the recent-photo cooldown only while alternatives exist.
            val fresh = pool.filter { it.key !in recentKeys }
            val usable = if (fresh.isNotEmpty()) {
                fresh
            } else {
                relaxed = true
                pool
            }

            val chosen = weightedPick(usable, available.size, request, stats)
            picked.add(chosen.key)
            pickedSet.add(chosen.key)
            shownThisCycle.add(chosen.key)
        }

        return ShuffleOutcome(
            keys = picked,
            cycleId = cycleId,
            startedNewCycle = startedNewCycle,
            cooldownRelaxed = relaxed,
        )
    }

    /**
     * Weight of a photo inside the eligible pool.
     *
     * Longer unseen and less frequently displayed photos weigh more. Never-shown
     * photos get the maximum age weight so brand new files appear quickly.
     */
    internal fun weightOf(
        candidate: PhotoRef,
        stats: Map<String, PhotoStat>,
        availableCount: Int,
        intervalSeconds: Long,
        now: Long,
    ): Double {
        val stat = stats[candidate.key]
        val shownCount = stat?.shownCount ?: 0
        val lastShownAt = stat?.lastShownAt ?: 0L
        // How long a full rotation is expected to take.
        val horizonHours = ((availableCount.coerceAtLeast(1) * intervalSeconds.coerceAtLeast(1)) / 3600.0)
            .coerceAtLeast(1.0 / 60.0)
        val ageHours = if (lastShownAt <= 0L) {
            Double.POSITIVE_INFINITY
        } else {
            ((now - lastShownAt).coerceAtLeast(0L) / 3_600_000.0)
        }
        val ageWeight = if (ageHours.isInfinite()) 2.0 else (ageHours / horizonHours).coerceIn(0.0, 2.0)
        val rarityWeight = 2.0 / (1.0 + shownCount)
        return 1.0 + 3.0 * ageWeight + rarityWeight
    }

    private fun weightedPick(
        pool: List<PhotoRef>,
        availableCount: Int,
        request: ShuffleRequest,
        stats: Map<String, PhotoStat>,
    ): PhotoRef {
        if (pool.size == 1) return pool.first()
        var total = 0.0
        val weights = DoubleArray(pool.size)
        for (i in pool.indices) {
            val w = weightOf(pool[i], stats, availableCount, request.intervalSeconds, request.now)
                .coerceAtLeast(0.0001)
            weights[i] = w
            total += w
        }
        if (!total.isFinite() || total <= 0.0) {
            return pool[request.random.nextInt(pool.size)]
        }
        var roll = request.random.nextDouble() * total
        for (i in pool.indices) {
            roll -= weights[i]
            if (roll <= 0.0) return pool[i]
        }
        return pool.last()
    }
}

/** Window the cooldown percentage asks for, before the small-collection clamp. */
internal fun requestedCooldownWindow(cooldownPercent: Int, availableCount: Int): Int {
    if (availableCount <= 1) return 0
    val percent = cooldownPercent.coerceIn(0, 100)
    if (percent == 0) return 0
    return ceil(availableCount * (percent / 100.0)).toInt()
}

/**
 * Number of most recently shown photos protected by the cooldown.
 * Capped at `availableCount - 1` so at least one photo always stays eligible.
 */
internal fun cooldownWindowSize(cooldownPercent: Int, availableCount: Int): Int {
    val requested = requestedCooldownWindow(cooldownPercent, availableCount)
    if (requested == 0) return 0
    return requested.coerceIn(0, availableCount - 1)
}

/** Keys of the most recently shown photos, newest first. */
internal fun recentKeys(
    available: List<PhotoRef>,
    stats: Map<String, PhotoStat>,
    window: Int,
): Set<String> {
    if (window <= 0) return emptySet()
    return available.asSequence()
        .mapNotNull { candidate ->
            val stat = stats[candidate.key] ?: return@mapNotNull null
            if (stat.lastShownAt <= 0L && stat.lastPosition <= 0L) return@mapNotNull null
            candidate.key to stat.lastPosition
        }
        .sortedByDescending { it.second }
        .take(window)
        .map { it.first }
        .toHashSet()
}

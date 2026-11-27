package dev.photowidget.engine

import dev.photowidget.model.PhotoRef
import dev.photowidget.model.PhotoStat
import dev.photowidget.model.WidgetMode
import kotlin.random.Random

enum class StepDirection { FORWARD, BACKWARD }

enum class SelectionReason {
    EMPTY,
    FIRST,
    FORWARD,
    BACKWARD,
    RANDOM,
    SHUFFLE,
    DAILY,
    DAILY_REUSE,
    WEEKLY,
    WEEKLY_REUSE,
    LEAST_SHOWN,
    SINGLE,
}

/** Everything needed to decide what one widget update shows. */
data class ModeRequest(
    val mode: WidgetMode,
    val candidates: List<PhotoRef>,
    val stats: Map<String, PhotoStat> = emptyMap(),
    val cycleId: Long = 1L,
    val cooldownPercent: Int = 20,
    val intervalSeconds: Long = 3600L,
    val collageCount: Int = 4,
    val lastPhotoKey: String? = null,
    val singlePhotoUri: String? = null,
    val now: Long = 0L,
    /** Local epoch day, for DAILY mode. */
    val localEpochDay: Long = 0L,
    val lastDailyEpochDay: Long = 0L,
    val direction: StepDirection = StepDirection.FORWARD,
    /** Tap action "shuffle": ignore sequential order for one pick. */
    val forceShuffle: Boolean = false,
    /** A specific photo to show, e.g. the previous entry from history. */
    val forcedKey: String? = null,
    /** A full previous selection (collage group or history entry). */
    val forcedKeys: List<String> = emptyList(),
    val random: Random = Random.Default,
)

data class ModeSelection(
    val keys: List<String>,
    val cycleId: Long,
    val startedNewCycle: Boolean = false,
    val cooldownRelaxed: Boolean = false,
    /** True when the previously chosen photo is shown again unchanged (DAILY). */
    val reusedCurrent: Boolean = false,
    val reason: SelectionReason = SelectionReason.EMPTY,
) {
    val isEmpty: Boolean get() = keys.isEmpty()
    val primaryKey: String? get() = keys.firstOrNull()
}

/**
 * Maps a [WidgetMode] onto the specialised selectors.
 *
 * Kept separate from Android so the whole mode matrix can be unit tested.
 */
class ModeSelector(private val shuffle: SmartShuffleEngine = SmartShuffleEngine()) {

    fun select(request: ModeRequest): ModeSelection {
        val candidates = request.candidates.distinctBy { it.key }
        if (candidates.isEmpty()) {
            return ModeSelection(emptyList(), request.cycleId, reason = SelectionReason.EMPTY)
        }

        if (request.forcedKeys.isNotEmpty() && request.mode != WidgetMode.SEQUENTIAL) {
            val availableKeys = request.forcedKeys.toSet()
            val grouped = candidates.filter { it.key in availableKeys }
            if (grouped.isNotEmpty()) {
                return ModeSelection(
                    keys = grouped.map { it.key },
                    cycleId = request.cycleId,
                    reason = SelectionReason.BACKWARD,
                )
            }
        }

        val forced = request.forcedKey?.let { key -> candidates.firstOrNull { it.key == key } }
        if (forced != null && request.mode != WidgetMode.COLLAGE) {
            return ModeSelection(
                keys = listOf(forced.key),
                cycleId = request.cycleId,
                reason = SelectionReason.BACKWARD,
            )
        }

        when (request.mode) {
            WidgetMode.SINGLE -> {
                val key = request.singlePhotoUri
                val match = key?.let { uri -> candidates.firstOrNull { it.key == uri || it.uri == uri } }
                val chosen = match ?: candidates.first()
                // Taps must not keep appending the same photo to the history.
                return ModeSelection(
                    keys = listOf(chosen.key),
                    cycleId = request.cycleId,
                    reusedCurrent = request.lastPhotoKey == chosen.key,
                    reason = SelectionReason.SINGLE,
                )
            }

            WidgetMode.SEQUENTIAL -> {
                if (!request.forceShuffle) {
                    val chosen = sequentialPick(candidates, request.lastPhotoKey, request.direction)
                    if (chosen != null) {
                        val reason = if (request.direction == StepDirection.BACKWARD) {
                            SelectionReason.BACKWARD
                        } else {
                            SelectionReason.FORWARD
                        }
                        return ModeSelection(listOf(chosen.key), request.cycleId, reason = reason)
                    }
                }
            }

            WidgetMode.RANDOM -> {
                if (!request.forceShuffle) {
                    val pool = if (candidates.size > 1) {
                        candidates.filter { it.key != request.lastPhotoKey }.ifEmpty { candidates }
                    } else {
                        candidates
                    }
                    val chosen = pool[request.random.nextInt(pool.size)]
                    return ModeSelection(listOf(chosen.key), request.cycleId, reason = SelectionReason.RANDOM)
                }
            }

            WidgetMode.DAILY -> {
                if (!request.forceShuffle) {
                    val lastKey = request.lastPhotoKey
                    val stillValid = lastKey != null && candidates.any { it.key == lastKey }
                    if (request.lastDailyEpochDay == request.localEpochDay && stillValid) {
                        return ModeSelection(
                            keys = listOf(lastKey!!),
                            cycleId = request.cycleId,
                            reusedCurrent = true,
                            reason = SelectionReason.DAILY_REUSE,
                        )
                    }
                    val outcome = shufflePick(request, count = 1)
                    return ModeSelection(
                        keys = outcome.keys,
                        cycleId = outcome.cycleId,
                        startedNewCycle = outcome.startedNewCycle,
                        cooldownRelaxed = outcome.cooldownRelaxed,
                        reason = SelectionReason.DAILY,
                    )
                }
            }

            WidgetMode.WEEKLY -> {
                if (!request.forceShuffle) {
                    val lastKey = request.lastPhotoKey
                    val stillValid = lastKey != null && candidates.any { it.key == lastKey }
                    if (request.lastDailyEpochDay == request.localEpochDay && stillValid) {
                        return ModeSelection(
                            keys = listOf(lastKey!!),
                            cycleId = request.cycleId,
                            reusedCurrent = true,
                            reason = SelectionReason.WEEKLY_REUSE,
                        )
                    }
                    // A stable photo per weekday: the list order is the weekday order and
                    // the pick walks forward by one every midnight.
                    val index = Math.floorMod(request.localEpochDay, candidates.size.toLong()).toInt()
                    return ModeSelection(
                        keys = listOf(candidates[index].key),
                        cycleId = request.cycleId,
                        reason = SelectionReason.WEEKLY,
                    )
                }
            }

            WidgetMode.LEAST_SHOWN -> {
                if (!request.forceShuffle) {
                    val chosen = leastShownPick(candidates, request.lastPhotoKey, request.stats)
                    return ModeSelection(
                        keys = listOf(chosen.key),
                        cycleId = request.cycleId,
                        reason = SelectionReason.LEAST_SHOWN,
                    )
                }
            }

            WidgetMode.COLLAGE, WidgetMode.SMART_SHUFFLE -> Unit
        }

        val count = if (request.mode == WidgetMode.COLLAGE) {
            request.collageCount.coerceAtLeast(2)
        } else {
            1
        }
        val outcome = shufflePick(request, count)
        return ModeSelection(
            keys = outcome.keys,
            cycleId = outcome.cycleId,
            startedNewCycle = outcome.startedNewCycle,
            cooldownRelaxed = outcome.cooldownRelaxed,
            reason = SelectionReason.SHUFFLE,
        )
    }

    private fun shufflePick(request: ModeRequest, count: Int): ShuffleOutcome =
        shuffle.pick(
            ShuffleRequest(
                candidates = request.candidates,
                stats = request.stats,
                cycleId = request.cycleId,
                cooldownPercent = request.cooldownPercent,
                intervalSeconds = request.intervalSeconds,
                now = request.now,
                random = request.random,
            ),
            count = count,
        )

    /**
     * The photo with the fewest displays, then the one unseen the longest.
     * Never-shown photos have no timestamp, so they rank first.
     */
    private fun leastShownPick(
        candidates: List<PhotoRef>,
        lastKey: String?,
        stats: Map<String, PhotoStat>,
    ): PhotoRef {
        val ordered = candidates.sortedWith(
            compareBy(
                { stats[it.key]?.shownCount ?: 0 },
                { stats[it.key]?.lastShownAt ?: 0L },
                { it.key },
            ),
        )
        // Do not repeat the current photo while another equally fair option exists.
        return ordered.firstOrNull { it.key != lastKey } ?: ordered.first()
    }

    private fun sequentialPick(
        candidates: List<PhotoRef>,
        lastKey: String?,
        direction: StepDirection,
    ): PhotoRef? {
        if (candidates.isEmpty()) return null
        if (candidates.size == 1) return candidates.first()
        val step = if (direction == StepDirection.FORWARD) 1 else -1
        val index = candidates.indexOfFirst { it.key == lastKey }
        val nextIndex = if (index < 0) {
            if (step > 0) 0 else candidates.lastIndex
        } else {
            Math.floorMod(index + step, candidates.size)
        }
        return candidates[nextIndex]
    }
}

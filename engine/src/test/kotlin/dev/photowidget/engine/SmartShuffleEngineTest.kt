package dev.photowidget.engine

import dev.photowidget.model.PhotoRef
import dev.photowidget.model.PhotoStat
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SmartShuffleEngineTest {

    private val engine = SmartShuffleEngine()

    private fun candidates(count: Int): List<PhotoRef> = (1..count).map {
        PhotoRef(uri = "content://media/photo/$it", key = "photo-$it")
    }

    /** Mirrors the persistence the widget store performs after every pick. */
    private class Sim(
        val engine: SmartShuffleEngine,
        val size: Int,
        val cooldownPercent: Int = 20,
        val intervalSeconds: Long = 60,
        seed: Int = 7,
    ) {
        var candidates: List<PhotoRef> = (1..size).map {
            PhotoRef(uri = "content://media/photo/$it", key = "photo-$it")
        }
        val stats = mutableMapOf<String, PhotoStat>()
        var cycleId = 1L
        var position = 0L
        var now = 1_700_000_000_000L
        var random = Random(seed)
        val picks = mutableListOf<String>()

        fun addPhoto(key: String) {
            candidates = candidates + PhotoRef(uri = "content://media/photo/$key", key = key)
        }

        fun step(count: Int = 1): ShuffleOutcome {
            now += intervalSeconds * 1000
            val outcome = engine.pick(
                ShuffleRequest(
                    candidates = candidates,
                    stats = stats.toMap(),
                    cycleId = cycleId,
                    cooldownPercent = cooldownPercent,
                    intervalSeconds = intervalSeconds,
                    now = now,
                    random = random,
                ),
                count = count,
            )
            if (outcome.startedNewCycle) {
                stats.keys.toList().forEach { key -> stats[key] = stats.getValue(key).copy(shownInCycle = false) }
            }
            cycleId = outcome.cycleId
            for (key in outcome.keys) {
                val old = stats[key]
                position += 1
                stats[key] = PhotoStat(
                    key = key,
                    lastShownAt = now,
                    shownCount = (old?.shownCount ?: 0) + 1,
                    lastPosition = position,
                    shownInCycle = true,
                    firstSeenAt = old?.firstSeenAt ?: now,
                )
                picks += key
            }
            return outcome
        }
    }

    @Test
    fun `shows every photo once per cycle`() {
        val sim = Sim(engine, size = 25)
        repeat(25 * 6) { sim.step() }
        assertEquals(150, sim.picks.size)
        sim.picks.chunked(25).forEach { cycle ->
            assertEquals("cycle must contain every photo exactly once: $cycle", 25, cycle.toSet().size)
        }
    }

    @Test
    fun `all photos are shown before any repeat in large libraries`() {
        val sim = Sim(engine, size = 300)
        repeat(300) { sim.step() }
        assertEquals(300, sim.picks.toSet().size)
    }

    @Test
    fun `new photos join the current cycle immediately`() {
        val sim = Sim(engine, size = 6)
        repeat(3) { sim.step() }
        sim.addPhoto("photo-99")
        repeat(4) { sim.step() }
        assertEquals(7, sim.picks.size)
        assertTrue("the new photo must be shown in the current rotation", "photo-99" in sim.picks)
        assertEquals("no repeats before every photo has been seen", 7, sim.picks.toSet().size)
    }

    @Test
    fun `cooldown avoids the most recently shown photos at cycle start`() {
        val sim = Sim(engine, size = 10, cooldownPercent = 20)
        repeat(10) { sim.step() }
        val lastTwo = sim.picks.takeLast(2).toSet()
        val firstOfNextCycle = sim.step().keys
        assertTrue(
            "next cycle must not start with a recently shown photo: $firstOfNextCycle vs $lastTwo",
            firstOfNextCycle.none { it in lastTwo },
        )
    }

    @Test
    fun `zero cooldown allows recently shown photos`() {
        assertEquals(0, cooldownWindowSize(0, 10))
        var sawRecentRepeat = false
        repeat(300) { seed ->
            val sim = Sim(engine, size = 5, cooldownPercent = 0, seed = seed)
            repeat(5) { sim.step() }
            val last = sim.picks.last()
            val first = sim.step().keys.first()
            if (first == last) sawRecentRepeat = true
        }
        assertTrue("with cooldown disabled a recent photo must sometimes repeat", sawRecentRepeat)
    }

    @Test
    fun `never stalls with a single photo`() {
        val sim = Sim(engine, size = 1)
        repeat(20) { assertEquals(listOf("photo-1"), sim.step().keys) }
    }

    @Test
    fun `never stalls with two photos and strict cooldown`() {
        val sim = Sim(engine, size = 2, cooldownPercent = 100)
        repeat(20) { sim.step() }
        assertTrue(sim.picks.chunked(2).all { it.toSet().size == 2 })
        for (i in 1 until sim.picks.size) {
            assertNotEquals(sim.picks[i], sim.picks[i - 1])
        }
    }

    @Test
    fun `full cooldown relaxes instead of stalling`() {
        val sim = Sim(engine, size = 3, cooldownPercent = 100)
        val outcomes = (1..6).map { sim.step() }
        assertTrue("every pick must return a photo", outcomes.all { it.keys.size == 1 })
        assertTrue("cooldown should report relaxation", outcomes.any { it.cooldownRelaxed })
        assertTrue("every photo should still appear each cycle", sim.picks.chunked(3).all { it.toSet().size == 3 })
    }

    @Test
    fun `long unseen and rarely shown photos weigh more`() {
        val cand = candidates(2)
        val now = 1_700_000_000_000L
        val often = PhotoStat(cand[0].key, lastShownAt = now, shownCount = 50, lastPosition = 5, shownInCycle = true)
        val rare = PhotoStat(cand[1].key, lastShownAt = now - 30L * 24 * 3600 * 1000, shownCount = 1, lastPosition = 2, shownInCycle = true)
        val stats = mapOf(often.key to often, rare.key to rare)
        val wOften = engine.weightOf(cand[0], stats, 2, 3600, now)
        val wRare = engine.weightOf(cand[1], stats, 2, 3600, now)
        assertTrue("rare/long unseen weight $wRare should beat $wOften", wRare > wOften)
    }

    @Test
    fun `never shown photos get maximum age weight`() {
        val cand = candidates(1)
        val now = 1_700_000_000_000L
        val never = engine.weightOf(cand[0], emptyMap(), 5, 3600, now)
        val recently = engine.weightOf(
            cand[0],
            mapOf(cand[0].key to PhotoStat(cand[0].key, now, 3, 3, true)),
            5,
            3600,
            now,
        )
        assertTrue(never > recently)
    }

    @Test
    fun `cooldown window maths`() {
        assertEquals(0, cooldownWindowSize(20, 1))
        assertEquals(0, cooldownWindowSize(0, 10))
        assertEquals(2, cooldownWindowSize(20, 10))
        assertEquals(4, cooldownWindowSize(100, 5))
        assertEquals(1, cooldownWindowSize(5, 10))
    }

    @Test
    fun `collage picks unique photos`() {
        val out = engine.pick(
            ShuffleRequest(candidates(9), emptyMap(), cycleId = 1, now = 1_000_000),
            count = 4,
        )
        assertEquals(4, out.keys.size)
        assertEquals(4, out.keys.toSet().size)
    }

    @Test
    fun `collage keeps picking every photo before repeating`() {
        val sim = Sim(engine, size = 6, cooldownPercent = 20)
        repeat(3) { sim.step(count = 4) }
        assertEquals(12, sim.picks.size)
        assertEquals(6, sim.picks.toSet().size)
        // No collage may contain the same photo twice.
        sim.picks.chunked(4).forEach { assertEquals(it.size, it.toSet().size) }
    }

    @Test
    fun `cycle id advances when a cycle completes`() {
        val sim = Sim(engine, size = 4)
        repeat(4) { sim.step() }
        val out = sim.step()
        assertTrue(out.startedNewCycle)
        assertEquals(2L, out.cycleId)
    }

    @Test
    fun `handles large libraries quickly`() {
        val big = (1..20_000).map { PhotoRef(uri = "content://media/photo/$it", key = "photo-$it") }
        val started = System.nanoTime()
        val out = engine.pick(
            ShuffleRequest(big, emptyMap(), cycleId = 1, now = 1_700_000_000_000, random = Random(1)),
        )
        val elapsedMs = (System.nanoTime() - started) / 1_000_000.0
        assertEquals(1, out.keys.size)
        assertTrue("selection took ${elapsedMs}ms", elapsedMs < 1500.0)
    }

    @Test
    fun `protects at least one photo when cooldown is very high`() {
        val out = engine.pick(
            ShuffleRequest(
                candidates = candidates(4),
                stats = mapOf(
                    "photo-1" to PhotoStat("photo-1", 1_700_000_000_000, 1, 4, true),
                    "photo-2" to PhotoStat("photo-2", 1_700_000_000_000, 1, 3, true),
                    "photo-3" to PhotoStat("photo-3", 1_700_000_000_000, 1, 2, true),
                    "photo-4" to PhotoStat("photo-4", 1_700_000_000_000, 1, 1, true),
                ),
                cycleId = 2,
                cooldownPercent = 100,
                now = 1_700_000_000_000,
            ),
        )
        assertEquals(1, out.keys.size)
    }
}

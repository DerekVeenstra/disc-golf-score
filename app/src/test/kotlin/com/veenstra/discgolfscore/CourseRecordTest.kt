package com.veenstra.discgolfscore

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** [recordAfterRound] and [formatCourseRecord]. */
class CourseRecordTest {

    private val alex = Player("p1", "Alex")
    private val sam = Player("p2", "Sam")
    private val derek = Player("p3", "Derek")

    private val riverside = Course("c1", "Riverside", holeCount = 1, pars = listOf(4))

    /** A finished, fully-played round on [course] with each player's final strokes as given. */
    private fun finishedRound(course: Course, vararg strokes: Pair<Player, Int>): RoundState {
        val players = strokes.map { it.first }
        val base = newRound(course, players)
        return base.copy(
            holes = listOf(base.holes[0].copy(strokes = strokes.associate { it.first.id to it.second })),
            finished = true,
        )
    }

    // ---- recordAfterRound ------------------------------------------------------------------------
    // riverside's one hole is par 4, so a player's to-par here is just strokes − 4.

    @Test
    fun `a course with no record gets one from its first eligible round`() {
        val round = finishedRound(riverside, alex to 3, sam to 5) // Alex −1, Sam +1
        val updated = recordAfterRound(riverside, round)
        assertEquals(-1, updated?.recordToPar)
        assertEquals(listOf("Alex"), updated?.recordHolderNames)
    }

    @Test
    fun `a round scoring better than the record replaces it outright`() {
        val course = riverside.copy(recordHolderNames = listOf("Derek"), recordToPar = -1)
        val round = finishedRound(course, sam to 1) // −3
        val updated = recordAfterRound(course, round)
        assertEquals(-3, updated?.recordToPar)
        assertEquals(listOf("Sam"), updated?.recordHolderNames) // Derek is displaced, not kept alongside
    }

    @Test
    fun `a round scoring worse than the record changes nothing`() {
        val course = riverside.copy(recordHolderNames = listOf("Derek"), recordToPar = -1)
        val round = finishedRound(course, sam to 6) // +2
        assertNull(recordAfterRound(course, round))
    }

    @Test
    fun `a round tying the record adds its player to the holder list`() {
        val course = riverside.copy(recordHolderNames = listOf("Derek"), recordToPar = -1)
        val round = finishedRound(course, sam to 3) // −1, ties
        val updated = recordAfterRound(course, round)
        assertEquals(-1, updated?.recordToPar)
        assertEquals(listOf("Derek", "Sam"), updated?.recordHolderNames)
    }

    @Test
    fun `a round tying the record with an already-listed holder changes nothing`() {
        val course = riverside.copy(recordHolderNames = listOf("Derek"), recordToPar = -1)
        val round = finishedRound(course, derek to 3) // −1, ties, but Derek's already a holder
        assertNull(recordAfterRound(course, round))
    }

    @Test
    fun `two players tying for the low score in the same round both become holders`() {
        val round = finishedRound(riverside, alex to 3, sam to 3, derek to 6) // Alex/Sam −1, Derek +2
        val updated = recordAfterRound(riverside, round)
        assertEquals(-1, updated?.recordToPar)
        assertEquals(listOf("Alex", "Sam"), updated?.recordHolderNames)
    }

    @Test
    fun `a round on a different course changes nothing`() {
        val other = Course("c2", "Oakdale", holeCount = 1, pars = listOf(3))
        val round = finishedRound(other, alex to 2)
        assertNull(recordAfterRound(riverside, round))
    }

    @Test
    fun `an in-progress (not yet finished) round changes nothing`() {
        val round = finishedRound(riverside, alex to 2).copy(finished = false)
        assertNull(recordAfterRound(riverside, round))
    }

    @Test
    fun `a round finished early is not eligible, even if its partial to-par is lower`() {
        val course = Course("c3", "Long course", holeCount = 3, pars = listOf(4, 4, 4))
        val base = newRound(course, listOf(alex))
        val quitEarly = base.copy(
            holes = base.holes.mapIndexed { i, h -> if (i == 0) h.copy(strokes = mapOf(alex.id to 1)) else h },
            currentHole = 1,
            finished = true,
        )
        assertNull(recordAfterRound(course, quitEarly))
    }

    // ---- formatCourseRecord ----------------------------------------------------------------------

    @Test
    fun `formatCourseRecord reports no record yet when there are no holders`() {
        assertEquals("No record yet", formatCourseRecord(riverside))
    }

    @Test
    fun `formatCourseRecord renders a single holder as name and to-par`() {
        val course = riverside.copy(recordHolderNames = listOf("Alex"), recordToPar = -3)
        assertEquals("Alex — −3", formatCourseRecord(course))
    }

    @Test
    fun `formatCourseRecord renders a tie as every name, comma-separated, with the shared to-par`() {
        val course = riverside.copy(recordHolderNames = listOf("Alex", "Sam"), recordToPar = -3)
        assertEquals("Alex, Sam — −3", formatCourseRecord(course))
    }

    @Test
    fun `formatCourseRecord renders even par as E`() {
        val course = riverside.copy(recordHolderNames = listOf("Alex"), recordToPar = 0)
        assertEquals("Alex — E", formatCourseRecord(course))
    }
}

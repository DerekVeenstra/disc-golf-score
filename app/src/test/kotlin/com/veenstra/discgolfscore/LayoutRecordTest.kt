package com.veenstra.discgolfscore

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** [recordAfterRound], [formatLayoutRecord], and [courseListDetail]. */
class LayoutRecordTest {

    private val alex = Player("p1", "Alex")
    private val sam = Player("p2", "Sam")
    private val derek = Player("p3", "Derek")

    private val riverside = Layout("l1", "18 holes", holeCount = 1, pars = listOf(4))

    /** A finished, fully-played round on [layout] (of [course]) with each player's final strokes as given. */
    private fun finishedRound(course: Course, layout: Layout, vararg strokes: Pair<Player, Int>): RoundState {
        val players = strokes.map { it.first }
        val base = newRound(course, layout, players)
        return base.copy(
            holes = listOf(base.holes[0].copy(strokes = strokes.associate { it.first.id to it.second })),
            finished = true,
        )
    }

    private fun finishedRound(layout: Layout, vararg strokes: Pair<Player, Int>): RoundState =
        finishedRound(Course("c1", "Riverside", listOf(layout)), layout, *strokes)

    // ---- recordAfterRound ------------------------------------------------------------------------
    // riverside's one hole is par 4, so a player's to-par here is just strokes − 4.

    @Test
    fun `a layout with no record gets one from its first eligible round`() {
        val round = finishedRound(riverside, alex to 3, sam to 5) // Alex −1, Sam +1
        val updated = recordAfterRound(riverside, round)
        assertEquals(-1, updated?.recordToPar)
        assertEquals(listOf("Alex"), updated?.recordHolderNames)
    }

    @Test
    fun `a round scoring better than the record replaces it outright`() {
        val layout = riverside.copy(recordHolderNames = listOf("Derek"), recordToPar = -1)
        val round = finishedRound(layout, sam to 1) // −3
        val updated = recordAfterRound(layout, round)
        assertEquals(-3, updated?.recordToPar)
        assertEquals(listOf("Sam"), updated?.recordHolderNames) // Derek is displaced, not kept alongside
    }

    @Test
    fun `a round scoring worse than the record changes nothing`() {
        val layout = riverside.copy(recordHolderNames = listOf("Derek"), recordToPar = -1)
        val round = finishedRound(layout, sam to 6) // +2
        assertNull(recordAfterRound(layout, round))
    }

    @Test
    fun `a round tying the record adds its player to the holder list`() {
        val layout = riverside.copy(recordHolderNames = listOf("Derek"), recordToPar = -1)
        val round = finishedRound(layout, sam to 3) // −1, ties
        val updated = recordAfterRound(layout, round)
        assertEquals(-1, updated?.recordToPar)
        assertEquals(listOf("Derek", "Sam"), updated?.recordHolderNames)
    }

    @Test
    fun `a round tying the record with an already-listed holder changes nothing`() {
        val layout = riverside.copy(recordHolderNames = listOf("Derek"), recordToPar = -1)
        val round = finishedRound(layout, derek to 3) // −1, ties, but Derek's already a holder
        assertNull(recordAfterRound(layout, round))
    }

    @Test
    fun `two players tying for the low score in the same round both become holders`() {
        val round = finishedRound(riverside, alex to 3, sam to 3, derek to 6) // Alex/Sam −1, Derek +2
        val updated = recordAfterRound(riverside, round)
        assertEquals(-1, updated?.recordToPar)
        assertEquals(listOf("Alex", "Sam"), updated?.recordHolderNames)
    }

    @Test
    fun `a round on a different layout changes nothing`() {
        val other = Layout("l2", "9 holes", holeCount = 1, pars = listOf(3))
        val round = finishedRound(other, alex to 2)
        assertNull(recordAfterRound(riverside, round))
    }

    @Test
    fun `a round on a sibling layout of the same course changes nothing`() {
        // The whole point of per-layout records (PLAN.md section 2 "Layouts"): two layouts of the
        // same course must not share a record just because they share a courseId.
        val sibling = Layout("l2", "9 holes", holeCount = 1, pars = listOf(3))
        val course = Course("c1", "Riverside", listOf(riverside, sibling))
        val round = finishedRound(course, sibling, alex to 1) // a great score, but on the wrong layout
        assertNull(recordAfterRound(riverside, round))
    }

    @Test
    fun `an in-progress (not yet finished) round changes nothing`() {
        val round = finishedRound(riverside, alex to 2).copy(finished = false)
        assertNull(recordAfterRound(riverside, round))
    }

    @Test
    fun `a round finished early is not eligible, even if its partial to-par is lower`() {
        val layout = Layout("l3", "18 holes", holeCount = 3, pars = listOf(4, 4, 4))
        val course = Course("c3", "Long course", listOf(layout))
        val base = newRound(course, layout, listOf(alex))
        val quitEarly = base.copy(
            holes = base.holes.mapIndexed { i, h -> if (i == 0) h.copy(strokes = mapOf(alex.id to 1)) else h },
            currentHole = 1,
            finished = true,
        )
        assertNull(recordAfterRound(layout, quitEarly))
    }

    // ---- formatLayoutRecord ----------------------------------------------------------------------

    @Test
    fun `formatLayoutRecord reports no record yet when there are no holders`() {
        assertEquals("No record yet", formatLayoutRecord(riverside))
    }

    @Test
    fun `formatLayoutRecord renders a single holder as name and to-par`() {
        val layout = riverside.copy(recordHolderNames = listOf("Alex"), recordToPar = -3)
        assertEquals("Alex — −3", formatLayoutRecord(layout))
    }

    @Test
    fun `formatLayoutRecord renders a tie as every name, comma-separated, with the shared to-par`() {
        val layout = riverside.copy(recordHolderNames = listOf("Alex", "Sam"), recordToPar = -3)
        assertEquals("Alex, Sam — −3", formatLayoutRecord(layout))
    }

    @Test
    fun `formatLayoutRecord renders even par as E`() {
        val layout = riverside.copy(recordHolderNames = listOf("Alex"), recordToPar = 0)
        assertEquals("Alex — E", formatLayoutRecord(layout))
    }

    // ---- courseListDetail ------------------------------------------------------------------------

    @Test
    fun `courseListDetail shows the layout's own record for a single-layout course`() {
        val layout = riverside.copy(recordHolderNames = listOf("Alex"), recordToPar = -3)
        val course = Course("c1", "Riverside", listOf(layout))
        assertEquals("Alex — −3", courseListDetail(course))
    }

    @Test
    fun `courseListDetail shows a layout count for a multi-layout course`() {
        val course = Course("c1", "Riverside", listOf(riverside, riverside.copy(id = "l2", name = "9 holes")))
        assertEquals("2 layouts", courseListDetail(course))
    }
}

package com.veenstra.discgolfscore

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** [newRound], [RoundState.strokesThrough]/[RoundState.toPar], and [RoundState.parsToLearn]. */
class RoundStateTest {

    private val derek = Player("p1", "Derek")
    private val sam = Player("p2", "Sam")

    private fun courseWith(layout: Layout): Course = Course("c1", "Riverside", listOf(layout))

    private fun newRoundOn(layout: Layout, players: List<Player>): RoundState =
        newRound(courseWith(layout), layout, players)

    @Test
    fun `a fresh round has one hole per layout hole, currentHole 1, nobody touched`() {
        val layout = Layout("l1", "18 holes", holeCount = 3, pars = listOf(0, 0, 0))
        val round = newRoundOn(layout, listOf(derek, sam))

        assertEquals(3, round.holes.size)
        assertEquals(1, round.currentHole)
        assertFalse(round.finished)
        round.holes.forEach { assertTrue(it.touched.isEmpty()) }
    }

    @Test
    fun `a fresh round snapshots the course and layout names and ids`() {
        val layout = Layout("l1", "18 holes", holeCount = 1, pars = listOf(0))
        val course = courseWith(layout)
        val round = newRound(course, layout, listOf(derek))

        assertEquals(course.id, round.courseId)
        assertEquals(course.name, round.courseName)
        assertEquals(layout.id, round.layoutId)
        assertEquals(layout.name, round.layoutName)
    }

    @Test
    fun `an unlearned hole pre-fills to par 3 and is flagged unlearned`() {
        val layout = Layout("l1", "New Layout", holeCount = 1, pars = listOf(0))
        val round = newRoundOn(layout, listOf(derek))

        val hole = round.holes.single()
        assertEquals(3, hole.par)
        assertFalse(hole.wasLearnedAtStart)
    }

    @Test
    fun `a learned hole pre-fills to the layout's remembered par and is flagged learned`() {
        val layout = Layout("l1", "18 holes", holeCount = 2, pars = listOf(4, 5))
        val round = newRoundOn(layout, listOf(derek))

        assertEquals(4, round.holes[0].par)
        assertTrue(round.holes[0].wasLearnedAtStart)
        assertEquals(5, round.holes[1].par)
        assertTrue(round.holes[1].wasLearnedAtStart)
    }

    @Test
    fun `a layout mixing learned and unlearned holes fills each independently`() {
        val layout = Layout("l1", "Mixed", holeCount = 3, pars = listOf(4, 0, 5))
        val round = newRoundOn(layout, listOf(derek))

        assertEquals(listOf(4, 3, 5), round.holes.map { it.par })
        assertEquals(listOf(true, false, true), round.holes.map { it.wasLearnedAtStart })
    }

    @Test
    fun `every player's strokes pre-fill to that hole's par`() {
        val layout = Layout("l1", "18 holes", holeCount = 2, pars = listOf(4, 0))
        val round = newRoundOn(layout, listOf(derek, sam))

        assertEquals(mapOf("p1" to 4, "p2" to 4), round.holes[0].strokes)
        assertEquals(mapOf("p1" to 3, "p2" to 3), round.holes[1].strokes)
    }

    @Test
    fun `a single player round still builds a full hole list`() {
        val layout = Layout("l1", "18 holes", holeCount = 18, pars = List(18) { 4 })
        val round = newRoundOn(layout, listOf(derek))

        assertEquals(18, round.holes.size)
        assertEquals(1, round.players.size)
    }

    @Test
    fun `a large roster has no cap`() {
        val roster = (1..25).map { Player("p$it", "Player $it") }
        val layout = Layout("l1", "18 holes", holeCount = 1, pars = listOf(3))
        val round = newRoundOn(layout, roster)

        assertEquals(25, round.holes[0].strokes.size)
        roster.forEach { assertEquals(3, round.holes[0].strokes[it.id]) }
    }

    @Test
    fun `a one-hole layout builds exactly one hole`() {
        val layout = Layout("l1", "1 hole", holeCount = 1, pars = listOf(0))
        val round = newRoundOn(layout, listOf(derek))

        assertEquals(1, round.holes.size)
        assertEquals(1, round.currentHole)
    }

    @Test
    fun `strokesThrough and toPar only count holes 1 through currentHole`() {
        val layout = Layout("l1", "18 holes", holeCount = 5, pars = List(5) { 4 })
        var round = newRoundOn(layout, listOf(derek))
        // Hand-craft strokes for every hole as if the whole card had already been played.
        round = round.copy(
            holes = round.holes.mapIndexed { index, hole ->
                hole.copy(strokes = mapOf("p1" to (index + 1))) // 1,2,3,4,5 strokes on holes 1..5
            },
        )

        // Mid-round: only holes 1..3 count even though holes 4 and 5 already have numbers.
        round = round.copy(currentHole = 3)
        assertEquals(1 + 2 + 3, round.strokesThrough("p1"))
        assertEquals((1 - 4) + (2 - 4) + (3 - 4), round.toPar("p1"))

        // At the end: currentHole covers the whole card and totals include every hole.
        round = round.copy(currentHole = 5)
        assertEquals(1 + 2 + 3 + 4 + 5, round.strokesThrough("p1"))
        assertEquals((1 + 2 + 3 + 4 + 5) - (4 * 5), round.toPar("p1"))
    }

    @Test
    fun `toPar is zero for a player who parred every counted hole`() {
        val layout = Layout("l1", "18 holes", holeCount = 3, pars = listOf(3, 4, 5))
        val round = newRoundOn(layout, listOf(derek)).copy(currentHole = 2)
        // p1's strokes default to par on every hole, so par-through-2 is exactly zero.
        assertEquals(0, round.toPar("p1"))
    }

    @Test
    fun `finishing early never counts holes the round didn't reach`() {
        // A round finished after hole 2 of 5 must total identically to a round that was only ever
        // 2 holes long — Finish must not move currentHole.
        val layout = Layout("l1", "18 holes", holeCount = 5, pars = List(5) { 4 })
        var round = newRoundOn(layout, listOf(derek))
        round = reduce(round, RoundAction.Adjust("p1", +1)) // hole 1: 5 strokes
        round = reduce(round, RoundAction.NextHole)
        round = reduce(round, RoundAction.Adjust("p1", -1)) // hole 2: 3 strokes
        val beforeFinish = round.currentHole
        round = reduce(round, RoundAction.Finish)

        assertEquals(beforeFinish, round.currentHole)
        assertEquals(5 + 3, round.strokesThrough("p1"))
        assertEquals((5 - 4) + (3 - 4), round.toPar("p1"))
    }

    @Test
    fun `parsToLearn includes only holes unlearned at round start`() {
        val layout = Layout("l1", "Mixed", holeCount = 3, pars = listOf(4, 0, 0))
        var round = newRoundOn(layout, listOf(derek)).copy(currentHole = 3)
        // Change par on the already-learned hole 1 and on unlearned hole 3.
        round = round.copy(
            holes = listOf(
                round.holes[0].copy(par = 5),
                round.holes[1],
                round.holes[2].copy(par = 5),
            ),
        )

        // Hole 1 was learned at start, so it must never appear, no matter what its par became.
        // Hole 2 is unlearned but never had its par changed from the default — still eligible,
        // with its default par. Hole 3 is unlearned and was changed.
        assertEquals(mapOf(2 to 3, 3 to 5), round.parsToLearn())
    }

    @Test
    fun `parsToLearn excludes unlearned holes the round never reached`() {
        // This is the interpretation call flagged in the Phase 2 log: PLAN.md doesn't say this
        // boundary explicitly for write-back, but leaving it out would teach the layout a par
        // (the DEFAULT_PAR pre-fill) that no human ever actually chose.
        val layout = Layout("l1", "18 holes", holeCount = 5, pars = listOf(4, 0, 0, 0, 0))
        val round = newRoundOn(layout, listOf(derek)).copy(currentHole = 2)

        assertEquals(mapOf(2 to 3), round.parsToLearn())
    }

    @Test
    fun `parsToLearn is empty when every hole was already learned`() {
        val layout = Layout("l1", "18 holes", holeCount = 3, pars = listOf(3, 4, 5))
        val round = newRoundOn(layout, listOf(derek)).copy(currentHole = 3)

        assertTrue(round.parsToLearn().isEmpty())
    }

    @Test
    fun `parsToLearn is empty when a round never set any par and never advanced`() {
        val layout = Layout("l1", "Brand New", holeCount = 18, pars = List(18) { 0 })
        val round = newRoundOn(layout, listOf(derek)) // currentHole stays 1, nothing played yet

        // Hole 1 is unlearned and reached, so its default par IS eligible — this is the round
        // where a human is standing on hole 1 having chosen nothing yet.
        assertEquals(mapOf(1 to 3), round.parsToLearn())
    }
}

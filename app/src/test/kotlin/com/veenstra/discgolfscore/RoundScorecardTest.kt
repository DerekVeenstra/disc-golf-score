package com.veenstra.discgolfscore

import org.junit.Assert.assertEquals
import org.junit.Test

/** [scoreResult] coloring and [RoundState.scorecardStrokes]' reached-holes boundary. */
class RoundScorecardTest {

    @Test
    fun `each score relative to par maps to its color class`() {
        assertEquals(ScoreResult.EAGLE_OR_BETTER, scoreResult(strokes = 1, par = 4))
        assertEquals(ScoreResult.EAGLE_OR_BETTER, scoreResult(strokes = 2, par = 4))
        assertEquals(ScoreResult.BIRDIE, scoreResult(strokes = 3, par = 4))
        assertEquals(ScoreResult.PAR, scoreResult(strokes = 4, par = 4))
        assertEquals(ScoreResult.BOGEY, scoreResult(strokes = 5, par = 4))
        assertEquals(ScoreResult.DOUBLE_BOGEY_OR_WORSE, scoreResult(strokes = 6, par = 4))
        assertEquals(ScoreResult.DOUBLE_BOGEY_OR_WORSE, scoreResult(strokes = 12, par = 4))
    }

    @Test
    fun `holes past the current hole are blank, reached holes show their strokes`() {
        val alex = Player("p1", "Alex")
        val layout = Layout("l1", "Main", holeCount = 4, pars = listOf(3, 3, 4, 3))
        val course = Course("c1", "Riverside", listOf(layout))
        val round = newRound(course, layout, listOf(alex)).let { round ->
            round.copy(
                holes = round.holes.mapIndexed { i, hole -> hole.copy(strokes = mapOf(alex.id to i + 2)) },
                currentHole = 2,
            )
        }
        assertEquals(listOf(2, 3, null, null), round.scorecardStrokes(alex.id))
    }

    @Test
    fun `par row hides unreached pars the layout never learned`() {
        val alex = Player("p1", "Alex")
        // Hole 3 unlearned (0), hole 4 learned as 5.
        val layout = Layout("l1", "Main", holeCount = 4, pars = listOf(3, 4, 0, 5))
        val round = newRound(Course("c1", "Riverside", listOf(layout)), layout, listOf(alex))
            .copy(currentHole = 2)
        assertEquals(listOf(3, 4, null, 5), round.scorecardPars())
    }

    @Test
    fun `a 36-hole card splits into four sub-rows of nine`() {
        val alex = Player("p1", "Alex")
        val layout = Layout("l1", "Long", holeCount = 36, pars = List(36) { 3 })
        val round = newRound(Course("c1", "Big", listOf(layout)), layout, listOf(alex))
        val chunks = round.scorecardStrokes(alex.id).chunked(SCORECARD_HOLES_PER_ROW)
        assertEquals(listOf(9, 9, 9, 9), chunks.map { it.size })
    }
}

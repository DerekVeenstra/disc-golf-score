package com.veenstra.discgolfscore

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** [reduce] — every rule in PLAN.md section 4 that's easy to get wrong. */
class RoundReducerTest {

    private val derek = Player("p1", "Derek")
    private val sam = Player("p2", "Sam")

    private fun freshRound(
        holeCount: Int = 3,
        pars: List<Int> = List(holeCount) { 0 },
        players: List<Player> = listOf(derek, sam),
    ): RoundState {
        val layout = Layout("l1", "18 holes", holeCount, pars)
        return newRound(Course("c1", "Riverside", listOf(layout)), layout, players)
    }

    // ---- Adjust: clamping ----------------------------------------------------------------

    @Test
    fun `Adjust moves a player's strokes by delta`() {
        val round = freshRound() // hole 1 default par 3 for both
        val next = reduce(round, RoundAction.Adjust("p1", +2))
        assertEquals(5, next.holes[0].strokes.getValue("p1"))
        // Sam is untouched by an adjustment aimed at Derek.
        assertEquals(3, next.holes[0].strokes.getValue("p2"))
    }

    @Test
    fun `Adjust clamps at the floor of 1`() {
        val round = freshRound()
        val next = reduce(round, RoundAction.Adjust("p1", -10))
        assertEquals(1, next.holes[0].strokes.getValue("p1"))
    }

    @Test
    fun `Adjust clamps at the ceiling of 15`() {
        val round = freshRound()
        val next = reduce(round, RoundAction.Adjust("p1", +100))
        assertEquals(15, next.holes[0].strokes.getValue("p1"))
    }

    @Test
    fun `repeated Adjust calls accumulate rather than each starting from par`() {
        var round = freshRound()
        round = reduce(round, RoundAction.Adjust("p1", +1))
        round = reduce(round, RoundAction.Adjust("p1", +1))
        assertEquals(5, round.holes[0].strokes.getValue("p1"))
    }

    @Test
    fun `a clamped Adjust that produces no numeric change still marks the row touched`() {
        // Already at the ceiling; another +1 clamps back to 15 — the number doesn't move, but the
        // human still expressed an intent about this row, so it must still count as touched.
        var round = freshRound()
        round = reduce(round, RoundAction.Adjust("p1", +100)) // now at 15
        val before = round.holes[0].strokes.getValue("p1")
        round = reduce(round, RoundAction.Adjust("p1", +5)) // clamps again, no numeric change
        assertEquals(before, round.holes[0].strokes.getValue("p1"))
        assertTrue("p1" in round.holes[0].touched)
    }

    @Test
    fun `Adjust for an unknown player id is a no-op`() {
        val round = freshRound()
        val next = reduce(round, RoundAction.Adjust("ghost", +1))
        assertEquals(round, next)
    }

    @Test
    fun `Adjust marks only the adjusted player as touched`() {
        val round = freshRound()
        val next = reduce(round, RoundAction.Adjust("p1", +1))
        assertEquals(setOf("p1"), next.holes[0].touched)
    }

    // ---- SetPar: propagation to untouched rows only ---------------------------------------

    @Test
    fun `SetPar updates the current hole's par`() {
        val round = freshRound()
        val next = reduce(round, RoundAction.SetPar(5))
        assertEquals(5, next.holes[0].par)
    }

    @Test
    fun `SetPar re-defaults every untouched player's strokes to the new par`() {
        val round = freshRound()
        val next = reduce(round, RoundAction.SetPar(5))
        assertEquals(5, next.holes[0].strokes.getValue("p1"))
        assertEquals(5, next.holes[0].strokes.getValue("p2"))
    }

    @Test
    fun `SetPar leaves a touched player's strokes exactly as the human set them`() {
        var round = freshRound()
        round = reduce(round, RoundAction.Adjust("p1", +2)) // Derek: 3 -> 5, touched
        round = reduce(round, RoundAction.SetPar(4)) // par changes to 4

        assertEquals(5, round.holes[0].strokes.getValue("p1")) // untouched by the par change
        assertEquals(4, round.holes[0].strokes.getValue("p2")) // Sam re-defaults to the new par
    }

    @Test
    fun `SetPar after some rows are touched only re-defaults the untouched ones`() {
        var round = freshRound(players = listOf(derek, sam, Player("p3", "Alex")))
        round = reduce(round, RoundAction.Adjust("p1", +1)) // Derek touched at 4
        round = reduce(round, RoundAction.SetPar(5))

        assertEquals(4, round.holes[0].strokes.getValue("p1")) // touched, untouched by SetPar
        assertEquals(5, round.holes[0].strokes.getValue("p2")) // untouched -> follows new par
        assertEquals(5, round.holes[0].strokes.getValue("p3")) // untouched -> follows new par
    }

    @Test
    fun `SetPar does not change the touched set itself`() {
        var round = freshRound()
        round = reduce(round, RoundAction.Adjust("p1", +1))
        round = reduce(round, RoundAction.SetPar(5))
        assertEquals(setOf("p1"), round.holes[0].touched)
    }

    @Test
    fun `SetPar only affects the current hole`() {
        var round = freshRound(holeCount = 2, pars = listOf(0, 0))
        round = reduce(round, RoundAction.SetPar(5))
        assertEquals(5, round.holes[0].par)
        assertEquals(3, round.holes[1].par) // untouched, still default
    }

    @Test
    fun `SetPar clamps a value above the valid range down to 5`() {
        val round = freshRound()
        val next = reduce(round, RoundAction.SetPar(9))
        assertEquals(5, next.holes[0].par)
    }

    @Test
    fun `SetPar clamps a value below the valid range up to 3`() {
        val round = freshRound()
        val next = reduce(round, RoundAction.SetPar(0))
        assertEquals(3, next.holes[0].par)
    }

    @Test
    fun `SetPar accepts each of 3, 4 and 5 unchanged`() {
        listOf(3, 4, 5).forEach { par ->
            val next = reduce(freshRound(), RoundAction.SetPar(par))
            assertEquals(par, next.holes[0].par)
        }
    }

    @Test
    fun `SetPar never changes wasLearnedAtStart`() {
        val round = freshRound(pars = listOf(4, 0, 0))
        val next = reduce(round, RoundAction.SetPar(5))
        assertTrue(next.holes[0].wasLearnedAtStart) // was learned; par changing doesn't un-learn it
    }

    // ---- PrevHole / NextHole boundaries -----------------------------------------------------

    @Test
    fun `PrevHole at hole 1 is a no-op`() {
        val round = freshRound()
        val next = reduce(round, RoundAction.PrevHole)
        assertEquals(round, next)
    }

    @Test
    fun `NextHole on the last hole is a no-op, not an implicit finish`() {
        var round = freshRound(holeCount = 2)
        round = reduce(round, RoundAction.NextHole) // now on hole 2, the last hole
        val onLastHole = round
        round = reduce(round, RoundAction.NextHole) // would-be overflow

        assertEquals(onLastHole, round)
        assertFalse(round.finished)
    }

    @Test
    fun `NextHole then PrevHole returns to the same hole`() {
        var round = freshRound(holeCount = 3)
        round = reduce(round, RoundAction.NextHole)
        round = reduce(round, RoundAction.PrevHole)
        assertEquals(1, round.currentHole)
    }

    @Test
    fun `a one-hole course has both PrevHole and NextHole as no-ops`() {
        val round = freshRound(holeCount = 1, pars = listOf(0))
        assertEquals(round, reduce(round, RoundAction.PrevHole))
        assertEquals(round, reduce(round, RoundAction.NextHole))
    }

    @Test
    fun `NextHole and PrevHole never touch any hole's strokes or par`() {
        var round = freshRound(holeCount = 3)
        round = reduce(round, RoundAction.Adjust("p1", +1))
        val holesBefore = round.holes
        round = reduce(round, RoundAction.NextHole)
        round = reduce(round, RoundAction.PrevHole)
        assertEquals(holesBefore, round.holes)
    }

    // ---- Finish and the frozen state --------------------------------------------------------

    @Test
    fun `Finish marks the round finished without moving currentHole`() {
        var round = freshRound(holeCount = 5)
        round = reduce(round, RoundAction.NextHole)
        round = reduce(round, RoundAction.NextHole) // hole 3
        val holeBeforeFinish = round.currentHole
        round = reduce(round, RoundAction.Finish)

        assertTrue(round.finished)
        assertEquals(holeBeforeFinish, round.currentHole)
    }

    @Test
    fun `every action is a no-op once a round is finished`() {
        var round = freshRound()
        round = reduce(round, RoundAction.Finish)
        val finished = round

        assertEquals(finished, reduce(finished, RoundAction.SetPar(5)))
        assertEquals(finished, reduce(finished, RoundAction.Adjust("p1", +1)))
        assertEquals(finished, reduce(finished, RoundAction.NextHole))
        assertEquals(finished, reduce(finished, RoundAction.PrevHole))
        assertEquals(finished, reduce(finished, RoundAction.Finish))
    }
}

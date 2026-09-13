package com.veenstra.discgolfscore

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** [RoundState.scoreboard] — sorting and shared-rank ties (PLAN.md section 2 "Ties on the scoreboard"). */
class ScoreboardTest {

    private val alex = Player("p1", "Alex")
    private val sam = Player("p2", "Sam")
    private val derek = Player("p3", "Derek")

    /** Builds a one-hole (par 4) round where each player's final strokes are exactly as given. */
    private fun roundWithStrokes(vararg strokes: Pair<Player, Int>): RoundState {
        val players = strokes.map { it.first }
        val layout = Layout("l1", "18 holes", holeCount = 1, pars = listOf(4))
        val course = Course("c1", "Riverside", listOf(layout))
        val round = newRound(course, layout, players)
        return round.copy(
            holes = listOf(round.holes[0].copy(strokes = strokes.associate { it.first.id to it.second })),
        )
    }

    @Test
    fun `sorted by strokes ascending, fewest strokes first`() {
        val round = roundWithStrokes(derek to 56, alex to 53, sam to 54)
        val board = round.scoreboard()
        assertEquals(listOf(alex, sam, derek), board.map { it.player })
    }

    @Test
    fun `no ties produces consecutive ranks`() {
        val round = roundWithStrokes(alex to 53, sam to 54, derek to 56)
        val board = round.scoreboard()
        assertEquals(listOf(1, 2, 3), board.map { it.rank })
    }

    @Test
    fun `a two-way tie shares a rank and the next rank skips`() {
        // PLAN.md's own example: Alex and Sam tie at 53, Derek trails at 56 -> ranks 1, 1, 3.
        val round = roundWithStrokes(alex to 53, sam to 53, derek to 56)
        val board = round.scoreboard()
        assertEquals(listOf(1, 1, 3), board.map { it.rank })
    }

    @Test
    fun `a three-way tie shares one rank and the next player is rank 4`() {
        val chris = Player("p4", "Chris")
        val round = roundWithStrokes(alex to 50, sam to 50, derek to 50, chris to 60)
        val board = round.scoreboard()
        assertEquals(listOf(1, 1, 1, 4), board.map { it.rank })
    }

    @Test
    fun `a tie for last still shares a rank`() {
        val round = roundWithStrokes(alex to 50, sam to 55, derek to 55)
        val board = round.scoreboard()
        assertEquals(listOf(1, 2, 2), board.map { it.rank })
    }

    @Test
    fun `two separate ties in the same field both skip correctly`() {
        val chris = Player("p4", "Chris")
        // 50, 50, 55, 55 -> ranks 1, 1, 3, 3
        val round = roundWithStrokes(alex to 50, sam to 50, derek to 55, chris to 55)
        val board = round.scoreboard()
        assertEquals(listOf(1, 1, 3, 3), board.map { it.rank })
    }

    @Test
    fun `every row carries the raw stroke total and the to-par total`() {
        val round = roundWithStrokes(alex to 3, sam to 5) // par 4: -1 and +1
        val board = round.scoreboard()
        val alexRow = board.single { it.player == alex }
        val samRow = board.single { it.player == sam }
        assertEquals(3, alexRow.strokes)
        assertEquals(-1, alexRow.toPar)
        assertEquals(5, samRow.strokes)
        assertEquals(1, samRow.toPar)
    }

    @Test
    fun `a single player is rank 1 alone`() {
        val round = roundWithStrokes(alex to 50)
        val board = round.scoreboard()
        assertEquals(listOf(1), board.map { it.rank })
    }

    @Test
    fun `an all-tied field shares rank 1 across everyone`() {
        val round = roundWithStrokes(alex to 50, sam to 50, derek to 50)
        val board = round.scoreboard()
        assertTrue(board.all { it.rank == 1 })
    }

    @Test
    fun `scoreboard respects the same currentHole boundary as toPar`() {
        val layout = Layout("l1", "18 holes", holeCount = 3, pars = listOf(4, 4, 4))
        val course = Course("c1", "Riverside", listOf(layout))
        var round = newRound(course, layout, listOf(alex, sam))
        round = round.copy(
            holes = listOf(
                round.holes[0].copy(strokes = mapOf("p1" to 3, "p2" to 5)), // Alex ahead
                round.holes[1].copy(strokes = mapOf("p1" to 10, "p2" to 4)), // Alex blows up
                round.holes[2].copy(strokes = mapOf("p1" to 3, "p2" to 4)),
            ),
            currentHole = 1, // only hole 1 counts so far
        )
        val board = round.scoreboard()
        assertEquals(alex, board.first().player) // Alex still ahead through hole 1 only
    }
}

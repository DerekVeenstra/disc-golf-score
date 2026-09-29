package com.veenstra.discgolfscore

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** [HoleScore.isStarframe]. */
class HoleScoreTest {

    private fun hole(par: Int, vararg strokes: Int) = HoleScore(
        par = par,
        wasLearnedAtStart = true,
        strokes = strokes.withIndex().associate { (i, s) -> "p$i" to s },
    )

    @Test
    fun `every player under par is a starframe`() {
        assertTrue(hole(3, 2, 2, 2).isStarframe)
        assertTrue(hole(4, 3, 2).isStarframe)
    }

    @Test
    fun `a solo birdie is a starframe`() {
        assertTrue(hole(3, 2).isStarframe)
    }

    @Test
    fun `one par on the card breaks the starframe`() {
        assertFalse(hole(3, 2, 3, 2).isStarframe)
    }

    @Test
    fun `untouched pre-filled pars are not a starframe`() {
        assertFalse(hole(3, 3, 3).isStarframe)
    }

    @Test
    fun `an empty card is not a starframe`() {
        assertFalse(hole(3).isStarframe)
    }
}

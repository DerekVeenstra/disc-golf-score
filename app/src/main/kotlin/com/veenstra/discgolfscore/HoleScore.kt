package com.veenstra.discgolfscore

/**
 * One hole's state within a round in progress.
 *
 * [wasLearnedAtStart] freezes, at the moment the hole was created (see [newRound]), whether the
 * course already knew this hole's par. It never changes afterward even if [RoundAction.SetPar]
 * changes [par] mid-round — that's what makes "learn once" (PLAN.md section 2) a property of the
 * data itself rather than something the write-back logic has to reconstruct after the fact.
 *
 * [strokes] is always populated with every player in the round, defaulted to [par] by [newRound].
 * [touched] records which of those players a human has explicitly adjusted; it drives one rule
 * ([RoundAction.SetPar] re-defaulting only untouched rows) and nothing visual (PLAN.md section 2
 * "Untouched rows").
 */
data class HoleScore(
    val par: Int,
    val wasLearnedAtStart: Boolean,
    val strokes: Map<String, Int>,
    val touched: Set<String> = emptySet(),
) {
    /**
     * A "starframe" — JomezPro's name for a hole where every player on the card birdied or better.
     * [strokes] always holds every player in the round (see this class's doc), so checking its
     * values is checking the whole card. An empty card is never a starframe.
     */
    val isStarframe: Boolean
        get() = strokes.isNotEmpty() && strokes.values.all { it < par }
}

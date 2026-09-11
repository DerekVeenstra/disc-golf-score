package com.veenstra.discgolfscore

/** Every way a [RoundState] in progress can change. [SetPar] and [Adjust] apply to [RoundState.currentHole]. */
sealed interface RoundAction {
    /** Sets the current hole's par, clamped to 3..5 — see [reduce] for the untouched-rows rule. */
    data class SetPar(val par: Int) : RoundAction

    /** Moves [playerId]'s strokes on the current hole by [delta], clamped to 1..15. */
    data class Adjust(val playerId: String, val delta: Int) : RoundAction

    data object NextHole : RoundAction
    data object PrevHole : RoundAction
    data object Finish : RoundAction
}

/**
 * Pure state transition — no Android dependencies, so this is exercised directly by JVM unit
 * tests (see RoundReducerTest). Every rule that's easy to get wrong lives here and only here: par
 * change propagating to untouched rows only, stroke/par clamping, PREV/NEXT boundary no-ops, and
 * the finished-round freeze.
 */
fun reduce(state: RoundState, action: RoundAction): RoundState {
    // A finished round is frozen (PLAN.md section 4 "A finished round is frozen"). What clears it
    // is DONE, a repository/ViewModel concern outside this sealed action set entirely.
    if (state.finished) return state

    val holeIndex = state.currentHole - 1
    val hole = state.holes[holeIndex]

    return when (action) {
        is RoundAction.SetPar -> {
            val par = action.par.coerceIn(MIN_PAR, MAX_PAR)
            val repricedHole = hole.copy(
                par = par,
                // Only rows nobody has touched follow the new par — PLAN.md section 2 "The
                // touched set stays in the model...". A row a human already adjusted keeps the
                // number the human put there, even though the par under it just changed.
                strokes = hole.strokes.mapValues { (playerId, strokes) ->
                    if (playerId in hole.touched) strokes else par
                },
            )
            state.withHole(holeIndex, repricedHole)
        }

        is RoundAction.Adjust -> {
            val current = hole.strokes[action.playerId]
            if (current == null) {
                // Not a player in this round — nothing sensible to adjust or clamp.
                state
            } else {
                val adjusted = (current + action.delta).coerceIn(MIN_STROKES, MAX_STROKES)
                state.withHole(
                    holeIndex,
                    hole.copy(
                        strokes = hole.strokes + (action.playerId to adjusted),
                        // Added to `touched` even when clamping meant the number didn't move — the
                        // human still expressed an intent about this row (PLAN.md "Adjust" rule).
                        touched = hole.touched + action.playerId,
                    ),
                )
            }
        }

        RoundAction.NextHole ->
            // Explicitly NOT an implicit finish on the last hole — PLAN.md section 4 "NextHole on
            // the last hole is a no-op". Only an explicit, confirmed Finish ends a round.
            if (state.currentHole < state.holes.size) {
                state.copy(currentHole = state.currentHole + 1)
            } else {
                state
            }

        RoundAction.PrevHole ->
            // A no-op at hole 1, never an implicit wraparound or finish.
            if (state.currentHole > 1) state.copy(currentHole = state.currentHole - 1) else state

        RoundAction.Finish -> state.copy(finished = true)
    }
}

/** Returns [this] with the hole at [index] replaced by [hole]. */
private fun RoundState.withHole(index: Int, hole: HoleScore): RoundState =
    copy(holes = holes.mapIndexed { i, h -> if (i == index) hole else h })

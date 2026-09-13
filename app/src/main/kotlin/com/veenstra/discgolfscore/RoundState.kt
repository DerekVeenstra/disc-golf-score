package com.veenstra.discgolfscore

/** Strokes clamp to this range on every [RoundAction.Adjust] — PLAN.md section 3 "Hole". */
internal const val MIN_STROKES = 1
internal const val MAX_STROKES = 15

/** The only pars the selector offers, and the only ones [RoundAction.SetPar] will accept. */
internal const val MIN_PAR = 3
internal const val MAX_PAR = 5

/** What an unlearned hole pre-fills to — by far the most common disc golf hole (PLAN.md section 2). */
internal const val DEFAULT_PAR = 3

/**
 * The entire state of one round in progress (or just finished).
 *
 * [players], [courseName], and [layoutName] are snapshotted at round creation rather than
 * referenced live into the roster/course/layout lists, so renaming or deleting a player, course, or
 * layout mid-round can't reshuffle or corrupt a card in progress (PLAN.md section 4 "Model").
 * [courseId] and [layoutId] are kept only so a finished round's par/record write-back (see
 * [parsToLearn], [recordAfterRound]) knows which layout to teach; both no-op if that course or
 * layout has since been deleted.
 *
 * A round is played against a [Layout], not a [Course] directly (PLAN.md section 2 "Layouts"), so
 * this snapshots both display names separately rather than one combined string — [CourseEditorScreen]
 * and [FinalScoreboardScreen] want to render them at different sizes, and keeping [courseName] as
 * its own field (rather than folding the layout name into it) is also what lets an old, pre-layouts
 * saved round decode with a blank [layoutName] instead of breaking (PLAN.md section 2 "Migration").
 * [layoutId] is nullable and [layoutName] defaults to `""` for the exact same reason: a round
 * restored from before this feature existed has no layout to name, and the UI treats a blank
 * [layoutName] as "nothing to show" rather than an error.
 *
 * [currentHole] is 1-based. It is the *only* notion of how much of the round counts — see
 * [strokesThrough]/[toPar] and the note on [parsToLearn] below. There is deliberately no second
 * "final totals" accessor: PLAN.md section 4 calls a second way to total a card "a second way to
 * get it wrong".
 */
data class RoundState(
    val courseId: String?,
    val courseName: String,
    val players: List<Player>,
    val holes: List<HoleScore>,
    val currentHole: Int,
    val finished: Boolean = false,
    val layoutId: String? = null,
    val layoutName: String = "",
) {
    /** Total strokes for [playerId] across holes 1..[currentHole] — never the whole card. */
    fun strokesThrough(playerId: String): Int =
        holes.take(currentHole).sumOf { it.strokes[playerId] ?: 0 }

    /** [strokesThrough] relative to par, across the same holes 1..[currentHole]. */
    fun toPar(playerId: String): Int =
        holes.take(currentHole).sumOf { (it.strokes[playerId] ?: 0) - it.par }

    /**
     * Which holes should teach their par back to [courseId] once this round is saved, and what
     * par to teach — "learn once" (PLAN.md section 2): a hole is eligible only if the course
     * didn't already know its par at the start of *this* round ([HoleScore.wasLearnedAtStart]
     * false), so a par changed on an already-learned hole never escapes the round it was changed
     * in.
     *
     * Restricted to holes 1..[currentHole], the same boundary [strokesThrough]/[toPar] use. This
     * restriction is an interpretation, not something PLAN.md section 4 states outright for
     * write-back specifically — it only says "the write-back fires only for holes where
     * [HoleScore.wasLearnedAtStart] is false". But a hole the round never reached still carries
     * its unconfirmed [DEFAULT_PAR] pre-fill rather than a par a human actually chose, and
     * teaching a guess back to the course as learned would be exactly the silent corruption "learn
     * once" exists to prevent. This applies the same principle PLAN.md section 4 states for
     * totals — "a round finished early simply never counts holes it didn't reach" — to the one
     * other place per-hole data escapes the round. Flagged in the Phase 2 log.
     *
     * Keyed by 1-based hole number, matching [Course.pars]' 0-based index plus one.
     */
    fun parsToLearn(): Map<Int, Int> =
        holes.take(currentHole).withIndex()
            .filter { (_, hole) -> !hole.wasLearnedAtStart }
            .associate { (index, hole) -> (index + 1) to hole.par }
}

/**
 * Builds the initial state for a fresh round on [course], played on [layout], with [players].
 * Each hole's par comes from the layout's learned value at that index, or [DEFAULT_PAR] when the
 * layout hasn't learned that hole yet (a `0` in [Layout.pars]) — see PLAN.md section 2 "Why par is
 * learned instead of entered up front". Every player's strokes pre-fill to that hole's par (PLAN.md
 * section 2 "Default strokes on a new hole"); nobody starts touched, and [RoundState.currentHole]
 * starts at hole 1.
 *
 * [course] and [layout] are snapshotted into [RoundState.courseName]/[RoundState.layoutName]
 * immediately — see [RoundState]'s own doc comment for why both names, not one combined string.
 */
fun newRound(course: Course, layout: Layout, players: List<Player>): RoundState {
    val holes = List(layout.holeCount) { index ->
        val learnedPar = layout.pars.getOrElse(index) { 0 }
        val wasLearned = learnedPar != 0
        val par = if (wasLearned) learnedPar else DEFAULT_PAR
        HoleScore(
            par = par,
            wasLearnedAtStart = wasLearned,
            strokes = players.associate { it.id to par },
        )
    }
    return RoundState(
        courseId = course.id,
        courseName = course.name,
        layoutId = layout.id,
        layoutName = layout.name,
        players = players,
        holes = holes,
        currentHole = 1,
    )
}

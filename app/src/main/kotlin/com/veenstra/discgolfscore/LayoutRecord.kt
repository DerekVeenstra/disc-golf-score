package com.veenstra.discgolfscore

/**
 * What [layout]'s record becomes once [round] finishes, or `null` if nothing about it changes.
 * The automatic half of "Layout record" (PLAN.md section 2, née "Course record" — the record now
 * targets the layout a round was actually played on, not its parent course, since sibling layouts
 * of the same course learn independently): [RoundViewModel] calls this once, at the moment a round
 * finishes, and applies whatever it returns — the same write-back shape as
 * [RoundState.parsToLearn]/`applyParWriteBack`, just for the record instead of a par.
 *
 * The record is kept relative to par, not as a raw stroke total (PLAN.md section 2 "Course record
 * relative to par") — [RoundState.toPar] rather than [RoundState.strokesThrough], so a record set
 * on one set of learned pars still means the same thing after a hole's par is later corrected.
 *
 * Only an eligible round can advance the record — one that's actually on this layout
 * ([RoundState.layoutId], not [RoundState.courseId] — a round on a sibling layout of the same
 * course must not touch this one) and reached every hole (`round.currentHole == round.holes.size`).
 * PLAN.md section 4 already settles that "a round finished early simply never counts holes it
 * didn't reach" for [RoundState.strokesThrough] (and, the same way, [RoundState.toPar]); a 9-hole
 * score from an early finish would otherwise look like it beat a real 18-hole round here.
 *
 * A strictly better (lower) to-par replaces the record outright — the new best score, and everyone
 * in [round] who carded it. A score that only *matches* the existing [Layout.recordToPar] adds
 * whoever in [round] carded it to the holder list, deduplicated by name, so a tie is recorded as a
 * tie rather than silently dropped. A score worse than the record, or one that ties it with no new
 * name to add, changes nothing — `null`, so [RoundViewModel] has nothing to persist.
 */
internal fun recordAfterRound(layout: Layout, round: RoundState): Layout? {
    if (round.layoutId != layout.id || !round.finished || round.currentHole != round.holes.size) return null
    val roundBest = round.players.minOfOrNull { round.toPar(it.id) } ?: return null
    val currentRecord = layout.recordToPar

    return if (currentRecord == null || roundBest < currentRecord) {
        val holders = round.players.filter { round.toPar(it.id) == roundBest }.map { it.name }.distinct()
        layout.copy(recordToPar = roundBest, recordHolderNames = holders)
    } else if (roundBest == currentRecord) {
        val tied = round.players.filter { round.toPar(it.id) == roundBest }.map { it.name }
        val merged = (layout.recordHolderNames + tied).distinct()
        if (merged == layout.recordHolderNames) null else layout.copy(recordHolderNames = merged)
    } else {
        null
    }
}

/**
 * "Derek — −5" (or "Derek, Sam — −5" for a tie), or "No record yet" when [Layout.recordToPar] is
 * `null`. Shared by [ManageCoursesScreen]/[CourseEditorScreen]'s lists and [LayoutEditorScreen] so
 * none of them can drift on formatting. Reuses [formatToPar] — the same "E"/"+3"/"−5" shape shown
 * everywhere else a to-par appears, so a record reads consistently with the hole screen and final
 * scoreboard.
 */
internal fun formatLayoutRecord(layout: Layout): String {
    val toPar = layout.recordToPar
    return if (layout.recordHolderNames.isEmpty() || toPar == null) {
        "No record yet"
    } else {
        "${layout.recordHolderNames.joinToString(", ")} — ${formatToPar(toPar)}"
    }
}

/**
 * [ManageCoursesScreen]'s per-course row detail: always a layout count, never a record — a record
 * belongs to a layout, not to the course that contains it, so Derek asked for it to stop appearing
 * on the courses list even for the common single-layout case ("no record yet" reading as if the
 * *course* had none was the tell). [CourseEditorScreen]'s own layout list and [LayoutEditorScreen]
 * are where [formatLayoutRecord] still shows.
 */
internal fun courseListDetail(course: Course): String = countWord(course.layouts.size, "layout")

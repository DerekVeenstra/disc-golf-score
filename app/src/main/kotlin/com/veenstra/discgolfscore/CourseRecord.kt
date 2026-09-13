package com.veenstra.discgolfscore

/**
 * What [course]'s record becomes once [round] finishes, or `null` if nothing about it changes.
 * The automatic half of "Course record" (PLAN.md section 2): [RoundViewModel] calls this once, at
 * the moment a round finishes, and applies whatever it returns — the same write-back shape as
 * [RoundState.parsToLearn]/`applyParWriteBack`, just for the record instead of a par.
 *
 * The record is kept relative to par, not as a raw stroke total (PLAN.md section 2 "Course record
 * relative to par") — [RoundState.toPar] rather than [RoundState.strokesThrough], so a record set
 * on one set of learned pars still means the same thing after a hole's par is later corrected.
 *
 * Only an eligible round can advance the record — one that's actually on this course and reached
 * every hole (`round.currentHole == round.holes.size`). PLAN.md section 4 already settles that "a
 * round finished early simply never counts holes it didn't reach" for [RoundState.strokesThrough]
 * (and, the same way, [RoundState.toPar]); a 9-hole score from an early finish would otherwise
 * look like it beat a real 18-hole round here.
 *
 * A strictly better (lower) to-par replaces the record outright — the new best score, and everyone
 * in [round] who carded it. A score that only *matches* the existing [Course.recordToPar] adds
 * whoever in [round] carded it to the holder list, deduplicated by name, so a tie is recorded as a
 * tie rather than silently dropped. A score worse than the record, or one that ties it with no new
 * name to add, changes nothing — `null`, so [RoundViewModel] has nothing to persist.
 */
internal fun recordAfterRound(course: Course, round: RoundState): Course? {
    if (round.courseId != course.id || !round.finished || round.currentHole != round.holes.size) return null
    val roundBest = round.players.minOfOrNull { round.toPar(it.id) } ?: return null
    val currentRecord = course.recordToPar

    return if (currentRecord == null || roundBest < currentRecord) {
        val holders = round.players.filter { round.toPar(it.id) == roundBest }.map { it.name }.distinct()
        course.copy(recordToPar = roundBest, recordHolderNames = holders)
    } else if (roundBest == currentRecord) {
        val tied = round.players.filter { round.toPar(it.id) == roundBest }.map { it.name }
        val merged = (course.recordHolderNames + tied).distinct()
        if (merged == course.recordHolderNames) null else course.copy(recordHolderNames = merged)
    } else {
        null
    }
}

/**
 * "Derek — −5" (or "Derek, Sam — −5" for a tie), or "No record yet" when [Course.recordToPar] is
 * `null`. Shared by [ManageCoursesScreen]'s list and [CourseEditorScreen] so the two can't drift on
 * formatting. Reuses [formatToPar] — the same "E"/"+3"/"−5" shape shown everywhere else a to-par
 * appears, so a record reads consistently with the hole screen and final scoreboard.
 */
internal fun formatCourseRecord(course: Course): String {
    val toPar = course.recordToPar
    return if (course.recordHolderNames.isEmpty() || toPar == null) {
        "No record yet"
    } else {
        "${course.recordHolderNames.joinToString(", ")} — ${formatToPar(toPar)}"
    }
}

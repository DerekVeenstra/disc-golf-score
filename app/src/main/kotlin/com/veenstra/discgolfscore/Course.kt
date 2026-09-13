package com.veenstra.discgolfscore

/**
 * A saved, watch-local course: a name, a fixed hole count (immutable after creation — PLAN.md
 * section 2 "Course editing"), the par this course has *learned* for each hole so far, and its
 * course record.
 *
 * [pars] is expected to have size [holeCount]. A `0` at any index means that hole hasn't been
 * played under this course yet — see "Why par is learned instead of entered up front" in PLAN.md
 * section 2 — and [newRound] treats it as unlearned rather than as a real par of zero.
 *
 * [recordHolderNames] and [recordToPar] are this course's record — every name tied for the best
 * score, and that score relative to par (negative is under par). `recordHolderNames.isEmpty()`
 * (with [recordToPar] `null`) means no eligible round has been played here yet. Kept on the course
 * itself, not derived from round history, specifically so [CourseEditorScreen] can correct it by
 * hand the same way it corrects a wrong learned par (PLAN.md section 2 "Course record") — see
 * [recordAfterRound] for how play still advances it automatically.
 */
data class Course(
    val id: String,
    val name: String,
    val holeCount: Int,
    val pars: List<Int>,
    val recordHolderNames: List<String> = emptyList(),
    val recordToPar: Int? = null,
)

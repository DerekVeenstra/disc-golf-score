package com.veenstra.discgolfscore

/**
 * A saved, watch-local course: a name, a fixed hole count (immutable after creation — PLAN.md
 * section 2 "Course editing"), and the par this course has *learned* for each hole so far.
 *
 * [pars] is expected to have size [holeCount]. A `0` at any index means that hole hasn't been
 * played under this course yet — see "Why par is learned instead of entered up front" in PLAN.md
 * section 2 — and [newRound] treats it as unlearned rather than as a real par of zero.
 */
data class Course(
    val id: String,
    val name: String,
    val holeCount: Int,
    val pars: List<Int>,
)

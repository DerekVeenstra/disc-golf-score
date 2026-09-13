package com.veenstra.discgolfscore

/**
 * A saved, watch-local course: a name and the [Layout]s it's played from. PLAN.md section 2
 * "Layouts": a course like "Columbia Lake" is a container — "9 short red tees" and "18 long blues"
 * are two layouts of it — and a round is played against one of [layouts], never against the course
 * directly (see [newRound]). Hole count, learned pars, and the course record all moved off this
 * class onto [Layout] itself; [Course] now holds nothing but identity and grouping.
 *
 * [layouts] is never empty in practice: course creation always creates it with exactly one
 * (PLAN.md section 2 "Course creation"), the pre-layouts migration wraps every existing course's
 * data into exactly one (PLAN.md section 2 "Migration"), and deleting a course's last layout is
 * blocked rather than allowed to leave a layout-less course behind (PLAN.md section 2 "Deleting a
 * layout").
 */
data class Course(
    val id: String,
    val name: String,
    val layouts: List<Layout>,
)

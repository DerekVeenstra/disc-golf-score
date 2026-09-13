package com.veenstra.discgolfscore

/**
 * One playable configuration of a [Course] — tees/pins different enough to be worth telling apart
 * ("9 short red tees" vs. "18 long blues") while still sharing the same course. PLAN.md section 2
 * "Layouts": **rounds are played against a layout, not a course.** Hole count, learned per-hole
 * pars, and the record all live here rather than on [Course] — each layout learns its own pars and
 * holds its own record independently of its siblings, the same "learn once, correct by hand"
 * treatment [Course] used to give these fields directly (PLAN.md section 2 "Why par is learned",
 * "Course record").
 *
 * Exactly the five fields the layout decision names — no starting-hole offset and no explicit
 * typed par total; tee/pin detail belongs in [name] instead, same as it always implicitly did in a
 * course's own name before layouts existed.
 *
 * [holeCount] is **immutable after creation**, for the exact reason a course's used to be (PLAN.md
 * section 2 "Course editing"): it fixes [pars]' size for good, so there is no truncate-or-pad
 * question. Now that hole count is a property of the layout rather than the course, creating a
 * *new layout* is the supported way to get a different hole count at the same course.
 *
 * [pars] is expected to have size [holeCount]; a `0` at any index means that hole hasn't been
 * learned under this layout yet — see [newRound]. [recordHolderNames]/[recordToPar] are this
 * layout's record, exactly the shape [Course] used to carry (see [recordAfterRound]).
 */
data class Layout(
    val id: String,
    val name: String,
    val holeCount: Int,
    val pars: List<Int>,
    val recordHolderNames: List<String> = emptyList(),
    val recordToPar: Int? = null,
)

/**
 * `"18 holes"` / `"9 holes"` / `"1 hole"` — the name a layout gets when nobody typed one. Used in
 * two places: the pre-layouts-to-layouts migration's generated single layout (PLAN.md section 2
 * "Migration" — nothing typed a name for data that predates layouts existing at all) and course
 * creation's auto-named first layout (PLAN.md section 2 "Course creation" — the short path that
 * skips asking for a first layout name, since a brand new course usually only has one layout worth
 * of tees anyway; a real name can always be given later by renaming it from the course editor).
 */
internal fun defaultLayoutName(holeCount: Int): String = if (holeCount == 1) "1 hole" else "$holeCount holes"

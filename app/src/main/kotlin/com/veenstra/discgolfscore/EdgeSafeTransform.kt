package com.veenstra.discgolfscore

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * PLAN.md section 3's "Row width is inset horizontally... so the `−`/`＋` targets stay inside the
 * inscribed circle even when a row sits at the top or bottom of the viewport" — and PLAN.md
 * section 2 "Round-edge row safety", which replaced the original approach with this one.
 *
 * **What used to be here, and why it's gone.** Phase 4 measured each row's own on-screen position
 * via `onGloballyPositioned` → `boundsInRoot()`, computed the widest safe chord for that position
 * with the round display's inscribed-circle trig, and then *set that row's width* from the
 * measurement — a measure→resize feedback loop: the row's width for frame N+1 depended on where it
 * rendered on frame N, which depends on its width. That loop is the prime suspect for a confirmed
 * bug (PLAN.md's Phase 4 log, point 5): the very last row of a list, reachable only by scrolling to
 * the end, sometimes settled at 96×96px — a value matching neither its actual rendered position nor
 * anything else obvious, consistent with the width having been computed from a stale, one-frame-old
 * `dy` mid-fling and then never correcting. A `0.55` width floor papered over the *symptom* (the row
 * being too narrow to read or hit) without fixing the *cause* (the feedback loop itself).
 *
 * **What replaced it: nothing but [fillMaxWidth] with a static fraction.** PLAN.md section 3 only
 * ever required that a row's tap targets stay inside the round face's inscribed circle *at any
 * scroll position* — it never required each row to claim the *widest* safe width at its current
 * position, which is the optimization the deleted approach was actually chasing and nobody asked
 * for. A single fixed fraction, chosen conservatively enough to stay inside the circle even for a
 * row near the very top or bottom of the viewport, satisfies the actual requirement **by
 * construction**: there is nothing to measure, nothing to converge, and nothing for a stale frame
 * to get wrong. The fraction is deliberately picked assuming rows can appear anywhere in the visible
 * list, not just near the vertical center — screens that also want rows to *never actually reach*
 * the extreme top/bottom band (PLAN.md section 3's hole screen in particular) add extra top/bottom
 * content padding on top of this on their own `TransformingLazyColumn`, rather than this function
 * trying to know about scroll position at all — see `EdgeButtonPadding.kt`'s
 * `withRoundEdgeInset()`.
 *
 * **Phase 6 re-examined this constant and widened it, from evidence rather than taste (Task A).**
 * Phase 5 landed on 0.64 by iterating 0.78 → 0.68 → 0.64 chasing a *visual* wedge-clip on the
 * `+`/`−` glyph near the round face's top/bottom edge — but Phase 5's own `uiautomator dump` work
 * (its log, "One thing this phase's uiautomator dump work found") already showed that clip was
 * cosmetic only: the drawn circle was sliced, but the accessible/clickable bounds stayed the full
 * 48dp square regardless. Narrowing *every row in the app* to chase a glyph that was never actually
 * failing the ≥48dp requirement cost every row — including course/player names on the setup and
 * manager screens — real width for no tap-target benefit.
 *
 * Phase 6 re-ran the same measurement (`uiautomator dump` at multiple scroll rest positions,
 * including a row settled just 10px from the true top edge — more extreme than anything Phase 5's
 * own log cites) against [StepperRow]'s *existing, shipped* 0.92 fraction — which had silently
 * diverged from this constant since Phase 5 (see [StepperRow]'s own doc comment) — and found the
 * same result Phase 5 found at 0.64: full 96×96px (48dp) clickable bounds at every position tested,
 * cosmetic wedge-clip only at the extreme edge, same as before. That is direct evidence the width
 * fraction was never the variable controlling tap-target size — [ROUND_EDGE_INSET] and
 * `withEdgeButtonReserve()` are (they keep a row from resting close enough to the mask for the
 * *glyph* clip to even become visible) — so narrowing width bought nothing. Settled on **0.88**:
 * enough margin below StepperRow's already-proven 0.92 to be conservative, while still leaving
 * course/player names on `PickableRow` (previously stuck at 0.64) dramatically more room before
 * ellipsis truncation. Re-verified this phase on a 12-player roster with 15+ character names, and
 * on the segmented par selector's own screen, at multiple scroll positions.
 */
private const val ROW_WIDTH_FRACTION = 0.88f

@Composable
fun Modifier.roundSafeWidth(fraction: Float = ROW_WIDTH_FRACTION): Modifier = this.fillMaxWidth(fraction)

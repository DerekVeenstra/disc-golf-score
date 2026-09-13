package com.veenstra.discgolfscore

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text

/** The row's own height — kept at the app's usual 48dp row rhythm even though the buttons inside it are smaller (see [STEP_BUTTON_SIZE]). */
private val MIN_TARGET = 48.dp

/**
 * `−`/`＋` target size — a **deliberate exception** to PLAN.md section 3's usual "every target
 * stays ≥ 48dp" (which [MIN_TARGET] still names, unchanged, for everything else in the app):
 * Derek's own call that on the hole screen specifically, a long player name had "no room" once a
 * full 48dp circle was reserved on each side of the value, and asked for the buttons shrunk and
 * pushed toward the row's right edge instead. Still a real, if smaller, circular tap target — not
 * shrunk to icon-only size.
 */
private val STEP_BUTTON_SIZE = 36.dp

/**
 * One-line row: a label, then `−  value  ＋`. This is the exact shape PLAN.md section 3 draws for
 * both the course editor's par rows (`H1   ─ 3 ＋`) and the hole screen's player rows
 * (`Derek  ─ 4 ＋`) — "it should be the same composable," so it lives here once rather than in
 * either screen, ready for Phase 5 to reuse unchanged.
 *
 * The row itself is inset to [rowWidthFraction] of the available width (not edge-to-edge) so the
 * `−`/`＋` targets stay inside the round face's inscribed circle even when this row is scrolled to
 * the top or bottom of the viewport (PLAN.md section 3's "Row width is inset horizontally"). Both
 * targets are [MIN_TARGET] square regardless of that inset, so shrinking the row to fit the circle
 * never shrinks a tap target below 48dp.
 *
 * **This default matches [roundSafeWidth]'s own — kept as an explicit literal, not a shared
 * constant reference, so a future change to one is a deliberate edit to both.** Phase 5 introduced
 * an unexplained split here (this stayed 0.92 while [roundSafeWidth]'s own default moved to 0.64),
 * which meant every `StepperRow` in the app — the hole screen's player rows and PAR-as-stepper
 * control, the course editor's per-hole rows — was already rendering at 0.92, not 0.64, the whole
 * time Phase 5's log describes verifying "every list screen" at 0.64. Phase 6 (Task A) found this
 * by fresh `uiautomator dump` evidence contradicting the Phase 5 narrative, not by reading the diff
 * — see [roundSafeWidth]'s doc comment for the re-measurement — and resolved it by widening
 * [roundSafeWidth]'s default to meet this one at a shared, evidence-backed 0.88, rather than
 * narrowing this one to match the stale 0.64. Flagged for Derek: this file's rows never actually
 * shrank to 0.64 at any point Phase 5 shipped, so if narrower rows were specifically wanted here,
 * that never happened and would need a new, deliberate value.
 *
 * [tint], when supplied, fills the row with that color at full opacity — the hole
 * screen's per-player rows pass their [Player.color] here so a player's score entry sits on the
 * same colored background shown for them on the players screen; the course editor's `H1`/`H2`…
 * par rows pass nothing and stay plain.
 *
 * The row carries a 14dp inset on the left (matching [PickableRow]'s own) so [label] doesn't sit
 * flush against the row's edge — Derek's own read of the un-inset version was "the player names
 * are too far to the left." Applied to every caller, not just the tinted hole-screen rows, since
 * the course editor's par rows had the exact same flush-left look and no reason not to get the
 * same fix. The right inset is deliberately smaller (6dp, not 14dp) — the asymmetry is what moves
 * the `−`/`＋` cluster over toward the right edge, freeing more of the row's width for [label].
 */
@Composable
fun StepperRow(
    label: String,
    value: String,
    onDecrement: () -> Unit,
    onIncrement: () -> Unit,
    decrementEnabled: Boolean = true,
    incrementEnabled: Boolean = true,
    rowWidthFraction: Float = 0.88f,
    tint: Color? = null,
) {
    Row(
        modifier = Modifier
            .roundSafeWidth(rowWidthFraction)
            .height(MIN_TARGET)
            // Only clipped+tinted when `tint` is actually supplied, so an untinted caller (the
            // course editor's par rows) still renders with no background at all, not a transparent
            // rounded-rect it doesn't need.
            .then(
                if (tint != null) {
                    Modifier.clip(RoundedCornerShape(24.dp)).background(tint)
                } else {
                    Modifier
                },
            )
            .padding(start = 14.dp, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = label,
            fontSize = 14.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(end = 4.dp),
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            StepButton(symbol = "−", enabled = decrementEnabled, onClick = onDecrement)
            // A bare Text with a `.size()` modifier draws from the top of its box, not the middle —
            // Derek's "the par number isn't vertically centered." Wrapping it in a Box with a
            // centered `contentAlignment` (like every other numeral in this file, e.g. [ParOption])
            // actually centers it.
            Box(
                modifier = Modifier.padding(horizontal = 2.dp).size(width = 18.dp, height = MIN_TARGET),
                contentAlignment = Alignment.Center,
            ) {
                Text(text = value, fontSize = 18.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
            }
            StepButton(symbol = "＋", enabled = incrementEnabled, onClick = onIncrement)
        }
    }
}

@Composable
private fun StepButton(symbol: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(STEP_BUTTON_SIZE)
            .clip(CircleShape)
            .background(Color.White.copy(alpha = if (enabled) 0.10f else 0.04f))
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = symbol,
            fontSize = 14.sp,
            color = if (enabled) {
                MaterialTheme.colorScheme.onBackground
            } else {
                MaterialTheme.colorScheme.onBackground.copy(alpha = 0.3f)
            },
        )
    }
}

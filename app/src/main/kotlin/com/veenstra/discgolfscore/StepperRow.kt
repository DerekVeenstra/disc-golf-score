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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text

/** Every `−`/`＋` target is at least this large on a side (PLAN.md section 3: "Every target stays ≥ 48dp"). */
private val MIN_TARGET = 48.dp

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
 * [tint], when supplied, washes the row in a translucent version of that color — the hole
 * screen's per-player rows pass their [Player.color] here so a player's score entry sits on the
 * same colored background shown for them on the players screen; the course editor's `H1`/`H2`…
 * par rows pass nothing and stay plain.
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
            // Only clipped+tinted when `tint` is actually supplied, so every other caller (the
            // course editor's par rows) keeps rendering exactly as before — same width, same
            // edge-to-edge layout, no incidental inset.
            .then(
                if (tint != null) {
                    Modifier.clip(RoundedCornerShape(24.dp)).background(tint.copy(alpha = 0.28f))
                } else {
                    Modifier
                },
            ),
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
            Text(
                text = value,
                fontSize = 14.sp,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                modifier = Modifier.padding(horizontal = 6.dp).size(width = 22.dp, height = MIN_TARGET),
            )
            StepButton(symbol = "＋", enabled = incrementEnabled, onClick = onIncrement)
        }
    }
}

@Composable
private fun StepButton(symbol: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(MIN_TARGET)
            .clip(CircleShape)
            .background(Color.White.copy(alpha = if (enabled) 0.10f else 0.04f))
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = symbol,
            fontSize = 16.sp,
            color = if (enabled) {
                MaterialTheme.colorScheme.onBackground
            } else {
                MaterialTheme.colorScheme.onBackground.copy(alpha = 0.3f)
            },
        )
    }
}

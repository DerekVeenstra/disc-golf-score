package com.veenstra.discgolfscore

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text

/**
 * The main event (PLAN.md section 3 "Hole"). Renders [round]'s current hole and dispatches
 * [RoundAction]s through the callbacks — it does not compute a single score, clamp, propagate a
 * par change, or total anything itself; every rule that governs those already lives in `reduce`
 * and [RoundState] (PLAN.md section 4), and this screen only ever reads their output.
 *
 * `onFinish` is called only after the full-screen confirmation this screen itself owns, from
 * either finish path (PLAN.md section 2 "Finishing" — "Both paths confirm"): the `Finish round` row
 * on any hole but the last, or the primary [PrimaryActionRow] on the last hole. Neither path ever
 * calls `onNextHole` on the last hole — that action is a no-op in the reducer by design (PLAN.md
 * section 4 "NextHole on the last hole is a no-op"), so the last hole's primary row dispatches
 * `onFinish` directly instead of relying on that no-op.
 *
 * The primary next/finish action lives in the scrollable list, right under the standings, rather
 * than a persistent `EdgeButton` docked to the bottom of the screen — Derek's own call: a big
 * always-visible button was taking up screen real estate on every hole even when you're mid-way
 * through entering scores and nowhere near ready to advance.
 */
@Composable
fun HoleScreen(
    round: RoundState,
    onSetPar: (par: Int) -> Unit,
    onAdjust: (playerId: String, delta: Int) -> Unit,
    onNextHole: () -> Unit,
    onPrevHole: () -> Unit,
    onFinish: () -> Unit,
) {
    val holeIndex = round.currentHole - 1
    val hole = round.holes[holeIndex]
    val isLastHole = round.currentHole == round.holes.size
    val isFirstHole = round.currentHole == 1

    var showFinishConfirm by remember { mutableStateOf(false) }

    // A hole's par control starts expanded only when the course didn't already know this hole's
    // par (PLAN.md section 3: "On an unlearned hole the selector is expanded from the start, since
    // that's the hole where a choice is actually being asked for"). Keyed on currentHole so walking
    // to a different hole re-derives this from that hole's own wasLearnedAtStart rather than
    // carrying over whatever the previous hole's toggle state was.
    var parExpanded by remember(round.currentHole) { mutableStateOf(!hole.wasLearnedAtStart) }

    // Haptics on score changes (PLAN.md section 6 Phase 6). A light tick on every stroke +/- and
    // every par pick — the two actions tapped dozens of times a round with the watch often not
    // being looked at directly (walking between throws) — rather than `LongPress`'s heavier buzz,
    // which ultimate-score reserves for a completed hold-to-score gesture, not a plain tap.
    val haptics = LocalHapticFeedback.current
    val hapticAdjust: (playerId: String, delta: Int) -> Unit = { playerId, delta ->
        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        onAdjust(playerId, delta)
    }
    val hapticSetPar: (Int) -> Unit = { par ->
        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        onSetPar(par)
    }

    if (showFinishConfirm) {
        ConfirmScreen(
            title = "Finish round?",
            detail = "${round.courseName} · Hole ${round.currentHole}/${round.holes.size}",
            confirmLabel = "Yes, finish",
            cancelLabel = "No, keep playing",
            onConfirm = {
                showFinishConfirm = false
                onFinish()
            },
            onCancel = { showFinishConfirm = false },
        )
        return
    }

    val listState = rememberTransformingLazyColumnState()

    // Scroll resets to the top on every hole change, forward and back (PLAN.md section 3).
    LaunchedEffect(round.currentHole) {
        listState.scrollToItem(0)
    }

    ScreenScaffold(scrollState = listState) { contentPadding ->
        TransformingLazyColumn(
            state = listState,
            // No `.withEdgeButtonReserve()` — this screen no longer docks an EdgeButton at the
            // bottom, so there's no reserved space to leave for one.
            contentPadding = contentPadding.withRoundEdgeInset(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            item {
                // Small and right at the top (Derek: the old "HOLE x/y"/"PAR z" pair "takes up a
                // ton of real estate") — a status line, not a heading, so it reads at a glance
                // without competing with the player rows below for space. Folded onto the same
                // line as the par label when the par isn't being set right now (Derek: "put the
                // par on the same line as 'hole x of y' if it is not being set") — while it's
                // expanded into [ParSelector] below, "HOLE x/y" stays on its own line since the
                // selector itself already shows the par.
                if (!parExpanded) {
                    CompactHoleParHeader(
                        currentHole = round.currentHole,
                        holeCount = round.holes.size,
                        par = hole.par,
                        onClick = { parExpanded = true },
                    )
                } else {
                    Text(
                        text = "HOLE ${round.currentHole} / ${round.holes.size}",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(top = 2.dp, bottom = 2.dp),
                    )
                }
            }
            item {
                if (parExpanded) {
                    ParSelector(
                        currentPar = hole.par,
                        onPick = { par ->
                            hapticSetPar(par)
                            // Restores the auto-collapse-on-pick behavior of the original 3-button
                            // mockup (PLAN.md section 3), which the Phase 5 stepper redesign dropped
                            // (its own log, item 3: "a stepper has no equivalent 'I'm done' tap").
                            // Collapsing here is a UI-only state change — it doesn't touch
                            // `wasLearnedAtStart` or the write-back rule, both of which still key off
                            // whether the hole's par was ever explicitly set, not this label's
                            // expand/collapse state.
                            parExpanded = false
                        },
                    )
                }
            }
            items(round.players.size) { index ->
                val player = round.players[index]
                val strokes = hole.strokes[player.id] ?: 0
                StepperRow(
                    label = player.name,
                    value = strokes.toString(),
                    decrementEnabled = strokes > MIN_STROKES,
                    incrementEnabled = strokes < MAX_STROKES,
                    onDecrement = { hapticAdjust(player.id, -1) },
                    onIncrement = { hapticAdjust(player.id, 1) },
                    tint = player.rowTint(),
                )
            }
            item {
                Text(
                    text = "── TOTAL ──",
                    fontSize = 11.sp,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 2.dp),
                )
            }
            items(round.players.size) { index ->
                val player = round.players[index]
                StandingRow(name = player.name, toPar = round.toPar(player.id))
            }
            item {
                // The primary next/finish action, right under the scores rather than pinned to the
                // bottom of the screen (Derek: "a next hole button under the scores rather than a
                // big next that is always visible").
                PrimaryActionRow(
                    label = if (isLastHole) "Finish round ▸" else "Next hole ▸",
                    onClick = { if (isLastHole) showFinishConfirm = true else onNextHole() },
                )
            }
            if (!isFirstHole) {
                item {
                    DimTextRow(label = "◂ prev hole", onClick = onPrevHole)
                }
            }
            if (!isLastHole) {
                item {
                    DimTextRow(label = "Finish round", onClick = { showFinishConfirm = true })
                }
            }
        }
    }
}

/**
 * The collapsed hole-progress + par affordance on a hole the course already learned — tap to
 * expand (PLAN.md section 3). One combined line ("HOLE 3/9 · PAR 4"), not two stacked elements —
 * Derek: "put the par on the same line as 'hole x of y' if it is not being set" — and a small,
 * centered pill rather than a full [PickableRow] (Derek, previously: the old "PAR z" row "takes
 * up a ton of real estate") — a **deliberate exception** to this file's usual ≥48dp row height,
 * matched here by a wider-than-visible tap target (the full [roundSafeWidth] row, not just the
 * text) so the control stays comfortably tappable despite reading small.
 */
@Composable
private fun CompactHoleParHeader(currentHole: Int, holeCount: Int, par: Int, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .roundSafeWidth()
            .height(32.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(Color.White.copy(alpha = 0.06f))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = "HOLE $currentHole/$holeCount  ·  PAR $par", fontSize = 12.sp, fontWeight = FontWeight.Bold)
    }
}

/**
 * The expanded par control (PLAN.md section 3: `3 [4] 5`) — restored to Derek's originally
 * specified three-visible-options shape (Task A), replacing Phase 5's `StepperRow`-based redesign.
 * Phase 5 dropped this because three 48dp circles plus visible gaps need more width than the
 * shared row inset gave at the time (`ROW_WIDTH_FRACTION` was 0.64 then). PLAN.md section 3 now
 * records why that constraint doesn't actually apply here: this row sits near the top of the
 * content, not at a viewport extreme the way a scrolled-to list row can, and it's the one control
 * Derek specified as three visible options rather than a stepper. So it's a **deliberate width
 * exception** at [PAR_SELECTOR_WIDTH_FRACTION] (~85% of screen width) rather than the shared
 * [roundSafeWidth] inset every other row uses — verified by screenshot and `uiautomator dump` that
 * all three targets clear 48dp with visible gaps between them at this width, the same measurement
 * discipline `EdgeSafeTransform.kt`'s doc comment describes for the shared inset.
 *
 * Tapping a value calls [onPick] with that exact par (not a relative nudge, unlike every other
 * `StepperRow` in the app) and the caller collapses the control back to [CompactHoleParHeader] — the
 * auto-collapse-on-pick behavior PLAN.md section 3 implies ("tapping it expands") and Phase 5's log
 * flagged as lost when the stepper replaced this control.
 */
private const val PAR_SELECTOR_WIDTH_FRACTION = 0.85f
private val PAR_OPTION_SIZE = 52.dp

@Composable
private fun ParSelector(currentPar: Int, onPick: (Int) -> Unit) {
    Row(
        modifier = Modifier.roundSafeWidth(PAR_SELECTOR_WIDTH_FRACTION).height(64.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for (par in MIN_PAR..MAX_PAR) {
            ParOption(par = par, selected = par == currentPar, onClick = { onPick(par) })
        }
    }
}

@Composable
private fun ParOption(par: Int, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(PAR_OPTION_SIZE)
            .clip(CircleShape)
            .background(
                if (selected) {
                    MaterialTheme.colorScheme.primary
                } else {
                    Color.White.copy(alpha = 0.10f)
                },
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = par.toString(),
            fontSize = 18.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onBackground,
        )
    }
}

/** One line of the standings block: a player's name and their live to-par across holes 1..currentHole. */
@Composable
private fun StandingRow(name: String, toPar: Int) {
    Row(
        modifier = Modifier.roundSafeWidth().height(28.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = name,
            fontSize = 13.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(end = 4.dp),
        )
        Text(text = formatToPar(toPar), fontSize = 13.sp)
    }
}

/**
 * `◂ prev hole` / `Finish round` — dim text rows at the end of the list (PLAN.md section 3), not
 * pill-shaped like [PickableRow]: both are rare, low-priority actions this screen deliberately
 * doesn't want reading as prominently as the player rows above them.
 */
@Composable
private fun DimTextRow(label: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .roundSafeWidth()
            .height(40.dp)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.45f),
        )
    }
}

package com.veenstra.discgolfscore

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text

/**
 * The only place a wrong learned par gets fixed (PLAN.md section 2, section 3 "Course editor"),
 * now scoped to one [Layout] rather than a whole course (PLAN.md section 2 "Layouts" — sibling
 * layouts of the same course learn independently): rename, the (display-only) record row, then a
 * scrolling hole-by-hole par list. Hole count is shown but not editable (PLAN.md section 2 "Course
 * editing", carried onto [Layout] — "Hole count is immutable after creation"). Reached from
 * [CourseEditorScreen] by tapping or long-pressing a layout row.
 *
 * Owns its own rename text-input launcher internally (unlike the old course editor, which left
 * that to its caller) — this screen and [CourseEditorScreen] are reached through two call sites
 * each (the new-round setup screen and the courses manager), and folding every launcher this
 * screen needs into itself, rather than duplicating it at both call sites, is what keeps that
 * plumbing from doubling as the model grew a second level of editable entity.
 *
 * An unlearned hole (`0` in [Layout.pars]) displays [DEFAULT_PAR]; nudging it with `−`/`＋` writes
 * a real value back through [onSetPar] immediately, which is what "editing one marks it learned"
 * (PLAN.md section 3) means in practice — there's nothing to batch, so `SAVE` is really just
 * "done," matching every other roster edit in this app already being applied live.
 *
 * The record row is display-only (PLAN.md section 2 "Layout record editing removed") — correcting
 * it by hand now happens from the Google Sheet cloud saves write to, not from the watch.
 *
 * [canDelete] is `false` when [layout] is its course's only layout — PLAN.md section 2 "Deleting a
 * layout": deleting the last one is blocked rather than allowed, so the row is hidden entirely
 * rather than shown disabled, matching how this app has no other precedent for a visibly-disabled
 * list row.
 */
@Composable
fun LayoutEditorScreen(
    layout: Layout,
    onRename: (name: String) -> Unit,
    onSetPar: (holeIndex: Int, newPar: Int) -> Unit,
    onDelete: () -> Unit,
    canDelete: Boolean,
    onDone: () -> Unit,
) {
    val listState = rememberTransformingLazyColumnState()

    val renameLauncher = rememberTextInputLauncher(label = "Layout name") { typed ->
        val trimmed = typed?.trim()
        if (!trimmed.isNullOrEmpty()) onRename(trimmed)
    }

    ScreenScaffold(scrollState = listState) { contentPadding ->
        TransformingLazyColumn(
            state = listState,
            contentPadding = contentPadding.withRoundEdgeInset(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            item {
                PickableRow(
                    label = layout.name,
                    trailing = "✏️",
                    selected = false,
                    onClick = { renameLauncher(layout.name) },
                )
            }
            item {
                Text(
                    text = "Record: ${formatLayoutRecord(layout)}",
                    fontSize = 14.sp,
                    textAlign = TextAlign.Center,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }
            item {
                Text(
                    // "1 hole" not "1 holes" -- caught by Phase 6's 1-hole-course edge case check.
                    text = if (layout.holeCount == 1) "1 hole" else "${layout.holeCount} holes",
                    fontSize = 11.sp,
                    textAlign = TextAlign.Center,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
                )
            }
            items(layout.holeCount) { index ->
                val par = layout.pars.getOrElse(index) { 0 }.let { if (it == 0) DEFAULT_PAR else it }
                StepperRow(
                    label = "H${index + 1}",
                    value = par.toString(),
                    decrementEnabled = par > MIN_PAR,
                    incrementEnabled = par < MAX_PAR,
                    onDecrement = { onSetPar(index, (par - 1).coerceAtLeast(MIN_PAR)) },
                    onIncrement = { onSetPar(index, (par + 1).coerceAtMost(MAX_PAR)) },
                )
            }
            if (canDelete) {
                item {
                    DeleteActionRow(onClick = onDelete)
                }
            }
            item {
                PrimaryActionRow(label = "SAVE", onClick = onDone)
            }
        }
    }
}

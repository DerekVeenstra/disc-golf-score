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
import androidx.wear.compose.material3.EdgeButton
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text

/**
 * The only place a wrong learned par gets fixed (PLAN.md section 2, section 3 "Course editor"),
 * now scoped to one [Layout] rather than a whole course (PLAN.md section 2 "Layouts" — sibling
 * layouts of the same course learn independently): rename, the record row, then a scrolling
 * hole-by-hole par list. Hole count is shown but not editable (PLAN.md section 2 "Course editing",
 * carried onto [Layout] — "Hole count is immutable after creation"). Reached from
 * [CourseEditorScreen] by tapping or long-pressing a layout row.
 *
 * Owns its own rename/record text-input launchers internally (unlike the old course editor, which
 * left those to its caller) — this screen and [CourseEditorScreen] are reached through two call
 * sites each (the new-round setup screen and the courses manager), and folding every launcher this
 * screen needs into itself, rather than duplicating them at both call sites, is what keeps that
 * plumbing from doubling as the model grew a second level of editable entity.
 *
 * An unlearned hole (`0` in [Layout.pars]) displays [DEFAULT_PAR]; nudging it with `−`/`＋` writes
 * a real value back through [onSetPar] immediately, which is what "editing one marks it learned"
 * (PLAN.md section 3) means in practice — there's nothing to batch, so `SAVE` is really just
 * "done," matching every other roster edit in this app already being applied live.
 *
 * The record row (PLAN.md section 2 "Course record") mirrors the name row right above it: tapping
 * it opens the same text-input launcher [onRename] uses, prefilled with [Layout.recordHolderNames].
 * Once a record has holders, a `To par` stepper appears under it for [onSetRecordToPar] to nudge,
 * the same `−`/`＋` shape as every par row below it — there's no stepper before then because
 * there's nothing yet for it to adjust.
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
    onSetRecordHolders: (rawNames: String) -> Unit,
    onSetRecordToPar: (Int) -> Unit,
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
    // Unlike a rename, a blank result here is meaningful (it clears the record) rather than
    // ignored — see RoundViewModel.setLayoutRecordHolders — so every non-cancelled result is
    // passed through.
    val recordHoldersLauncher = rememberTextInputLauncher(label = "Record holder(s)") { typed ->
        if (typed != null) onSetRecordHolders(typed)
    }

    ScreenScaffold(scrollState = listState) { contentPadding ->
        TransformingLazyColumn(
            state = listState,
            contentPadding = contentPadding.withRoundEdgeInset().withEdgeButtonReserve(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            item {
                PickableRow(label = layout.name, selected = false, onClick = { renameLauncher(layout.name) })
            }
            item {
                PickableRow(
                    label = "Record: ${formatLayoutRecord(layout)}",
                    selected = false,
                    onClick = { recordHoldersLauncher(layout.recordHolderNames.joinToString(", ")) },
                )
            }
            if (layout.recordHolderNames.isNotEmpty()) {
                item {
                    val toPar = requireNotNull(layout.recordToPar) // holders and a to-par are set together
                    val min = layout.holeCount * (MIN_STROKES - MAX_PAR)
                    val max = layout.holeCount * (MAX_STROKES - MIN_PAR)
                    StepperRow(
                        label = "To par",
                        value = formatToPar(toPar),
                        decrementEnabled = toPar > min,
                        incrementEnabled = toPar < max,
                        onDecrement = { onSetRecordToPar((toPar - 1).coerceAtLeast(min)) },
                        onIncrement = { onSetRecordToPar((toPar + 1).coerceAtMost(max)) },
                    )
                }
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
                    PickableRow(label = "Delete layout", selected = false, onClick = onDelete)
                }
            }
        }
        EdgeButton(onClick = onDone, modifier = Modifier.align(Alignment.BottomCenter)) {
            Text(text = "SAVE")
        }
    }
}

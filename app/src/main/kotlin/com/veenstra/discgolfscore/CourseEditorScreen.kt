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
 * The only place a wrong learned par gets fixed (PLAN.md section 2, section 3 "Course editor"):
 * rename, then a scrolling hole-by-hole par list. Hole count is shown but not editable (PLAN.md
 * section 2 "Course editing" — "Hole count is immutable after creation"). Reused unchanged from
 * two entry points — long-pressing a course on the new-round setup screen, and tapping/long-
 * pressing one on the courses manager — rather than being duplicated, since it's the exact same
 * screen PLAN.md section 3 draws either way.
 *
 * An unlearned hole (`0` in [Course.pars]) displays [DEFAULT_PAR]; nudging it with `−`/`＋` writes
 * a real value back through [onSetPar] immediately, which is what "editing one marks it learned"
 * (PLAN.md section 3) means in practice — there's nothing to batch, so `SAVE` is really just
 * "done," matching every other roster edit in this app already being applied live.
 *
 * The record row (PLAN.md section 2 "Course record") mirrors the name row right above it: tapping
 * it triggers [onEditRecord], which — like [onRename] — is the caller's own text-input launcher,
 * prefilled with [Course.recordHolderNames] and free to hold whatever names/ties were typed,
 * comma-separated. Once a record has holders, a `To par` stepper appears under it for
 * [onSetRecordToPar] to nudge, the same `−`/`＋` shape as every par row below it — there's no
 * stepper before then because there's nothing yet for it to adjust.
 */
@Composable
fun CourseEditorScreen(
    course: Course,
    onRename: () -> Unit,
    onEditRecord: () -> Unit,
    onSetRecordToPar: (Int) -> Unit,
    onSetPar: (holeIndex: Int, newPar: Int) -> Unit,
    onDelete: () -> Unit,
    onDone: () -> Unit,
) {
    val listState = rememberTransformingLazyColumnState()

    ScreenScaffold(scrollState = listState) { contentPadding ->
        TransformingLazyColumn(
            state = listState,
            contentPadding = contentPadding.withRoundEdgeInset().withEdgeButtonReserve(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            item {
                PickableRow(label = course.name, selected = false, onClick = onRename)
            }
            item {
                PickableRow(label = "Record: ${formatCourseRecord(course)}", selected = false, onClick = onEditRecord)
            }
            if (course.recordHolderNames.isNotEmpty()) {
                item {
                    val toPar = requireNotNull(course.recordToPar) // holders and a to-par are set together
                    val min = course.holeCount * (MIN_STROKES - MAX_PAR)
                    val max = course.holeCount * (MAX_STROKES - MIN_PAR)
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
                    text = if (course.holeCount == 1) "1 hole" else "${course.holeCount} holes",
                    fontSize = 11.sp,
                    textAlign = TextAlign.Center,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
                )
            }
            items(course.holeCount) { index ->
                val par = course.pars.getOrElse(index) { 0 }.let { if (it == 0) DEFAULT_PAR else it }
                StepperRow(
                    label = "H${index + 1}",
                    value = par.toString(),
                    decrementEnabled = par > MIN_PAR,
                    incrementEnabled = par < MAX_PAR,
                    onDecrement = { onSetPar(index, (par - 1).coerceAtLeast(MIN_PAR)) },
                    onIncrement = { onSetPar(index, (par + 1).coerceAtMost(MAX_PAR)) },
                )
            }
            item {
                PickableRow(label = "Delete course", selected = false, onClick = onDelete)
            }
        }
        EdgeButton(onClick = onDone, modifier = Modifier.align(Alignment.BottomCenter)) {
            Text(text = "SAVE")
        }
    }
}

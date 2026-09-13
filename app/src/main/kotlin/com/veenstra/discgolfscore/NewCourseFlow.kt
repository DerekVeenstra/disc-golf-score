package com.veenstra.discgolfscore

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.material3.EdgeButton
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text

private const val MIN_HOLE_COUNT = 1
private const val MAX_HOLE_COUNT = 36

/**
 * Step two of course *or layout* creation (PLAN.md section 3 "New round setup" / section 2 "Course
 * creation", "Layouts"): the name is already typed (step one is just [rememberTextInputLauncher]),
 * now pick a hole count — `9`, `18`, or custom via `±`. Shared across every place something gets a
 * hole count — the new-round setup screen's "+ New course…", the courses manager's, and a course
 * editor's "+ New layout…" — the same inline-create-then-select shape as ultimate-score's
 * colour-picking step (PLAN.md section 13). **Hole count is set here and never again** for
 * whichever layout it ends up on (PLAN.md section 2 "Course editing", carried onto [Layout]) —
 * there is deliberately no way to reach this screen for a layout that already exists; a different
 * hole count means creating a new one.
 *
 * [subjectName] is only ever shown back to the person as "Holes on “X”" — it names whatever [name]
 * was just typed for (a new course or a new layout), not necessarily a course.
 */
@Composable
fun HoleCountPickerScreen(subjectName: String, onCreate: (holeCount: Int) -> Unit, onCancel: () -> Unit) {
    var holeCount by remember { mutableIntStateOf(18) }
    val listState = rememberTransformingLazyColumnState()

    ScreenScaffold(scrollState = listState) { contentPadding ->
        TransformingLazyColumn(
            state = listState,
            contentPadding = contentPadding.withRoundEdgeInset().withEdgeButtonReserve(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            item {
                Text(
                    text = "Holes on “$subjectName”",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }
            item {
                PickableRow(label = "9 holes", selected = holeCount == 9, onClick = { holeCount = 9 })
            }
            item {
                PickableRow(label = "18 holes", selected = holeCount == 18, onClick = { holeCount = 18 })
            }
            item {
                StepperRow(
                    label = "Custom",
                    value = holeCount.toString(),
                    decrementEnabled = holeCount > MIN_HOLE_COUNT,
                    incrementEnabled = holeCount < MAX_HOLE_COUNT,
                    onDecrement = { holeCount = (holeCount - 1).coerceAtLeast(MIN_HOLE_COUNT) },
                    onIncrement = { holeCount = (holeCount + 1).coerceAtMost(MAX_HOLE_COUNT) },
                )
            }
            item {
                PickableRow(label = "Cancel", selected = false, onClick = onCancel)
            }
        }
        EdgeButton(
            onClick = { onCreate(holeCount) },
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            Text(text = "CREATE")
        }
    }
}

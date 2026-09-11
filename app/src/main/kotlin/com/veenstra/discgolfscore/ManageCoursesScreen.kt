package com.veenstra.discgolfscore

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.material3.EdgeButton
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text

private sealed interface ManageCoursesMode {
    data object Listing : ManageCoursesMode
    data class ChoosingHoleCount(val courseName: String) : ManageCoursesMode
    data class Editing(val courseId: String) : ManageCoursesMode
}

/**
 * Housekeeping outside a round (PLAN.md section 3 "Players / Courses managers"): the saved course
 * list, `+ New course…` (name then hole count, same two-step creation as the setup screen), and
 * tap-to-edit into [CourseEditorScreen] — the only way to fix a wrong learned par (PLAN.md
 * section 2), so this screen exists in Phase 4 even with no round-starting flow behind it yet.
 */
@Composable
fun ManageCoursesScreen(
    courses: List<Course>,
    onAddCourse: (name: String, holeCount: Int) -> Course?,
    onRenameCourse: (id: String, name: String) -> Unit,
    onSetCoursePar: (id: String, holeIndex: Int, newPar: Int) -> Unit,
    onDeleteCourse: (id: String) -> Unit,
    onDone: () -> Unit,
) {
    var mode by remember { mutableStateOf<ManageCoursesMode>(ManageCoursesMode.Listing) }

    val newCourseNameLauncher = rememberTextInputLauncher(label = "Course name") { typed ->
        val trimmed = typed?.trim()
        if (!trimmed.isNullOrEmpty()) mode = ManageCoursesMode.ChoosingHoleCount(trimmed)
    }
    val renameLauncher = rememberTextInputLauncher(label = "Course name") { typed ->
        val editing = mode as? ManageCoursesMode.Editing ?: return@rememberTextInputLauncher
        val trimmed = typed?.trim()
        if (!trimmed.isNullOrEmpty()) onRenameCourse(editing.courseId, trimmed)
    }

    when (val current = mode) {
        is ManageCoursesMode.Listing -> {
            val listState = rememberTransformingLazyColumnState()
            ScreenScaffold(scrollState = listState) { contentPadding ->
                TransformingLazyColumn(
                    state = listState,
                    contentPadding = contentPadding.withRoundEdgeInset().withEdgeButtonReserve(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    items(courses.size) { index ->
                        val course = courses[index]
                        PickableRow(
                            label = course.name,
                            selected = false,
                            onClick = { mode = ManageCoursesMode.Editing(course.id) },
                            onLongClick = { mode = ManageCoursesMode.Editing(course.id) },
                        )
                    }
                    item {
                        PickableRow(label = "+ New course…", selected = false, onClick = { newCourseNameLauncher(null) })
                    }
                }
                EdgeButton(onClick = onDone, modifier = Modifier.align(Alignment.BottomCenter)) {
                    Text(text = "DONE")
                }
            }
        }

        is ManageCoursesMode.ChoosingHoleCount -> HoleCountPickerScreen(
            courseName = current.courseName,
            onCreate = { holeCount ->
                onAddCourse(current.courseName, holeCount)
                mode = ManageCoursesMode.Listing
            },
            onCancel = { mode = ManageCoursesMode.Listing },
        )

        is ManageCoursesMode.Editing -> {
            val live = courses.find { it.id == current.courseId }
            if (live != null) {
                CourseEditorScreen(
                    course = live,
                    onRename = { renameLauncher(live.name) },
                    onSetPar = { holeIndex, newPar -> onSetCoursePar(live.id, holeIndex, newPar) },
                    onDelete = {
                        onDeleteCourse(live.id)
                        mode = ManageCoursesMode.Listing
                    },
                    onDone = { mode = ManageCoursesMode.Listing },
                )
            } else {
                LaunchedEffect(Unit) { mode = ManageCoursesMode.Listing }
            }
        }
    }
}

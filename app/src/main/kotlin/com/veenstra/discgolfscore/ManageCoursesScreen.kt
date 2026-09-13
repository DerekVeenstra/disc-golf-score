package com.veenstra.discgolfscore

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.material3.ScreenScaffold

private sealed interface ManageCoursesMode {
    data object Listing : ManageCoursesMode
    data class ChoosingHoleCount(val courseName: String) : ManageCoursesMode
    data class Editing(val courseId: String) : ManageCoursesMode
}

/**
 * Housekeeping outside a round (PLAN.md section 3 "Players / Courses managers"): the saved course
 * list, `+ New course…` (name then hole count — creates the course with one auto-named layout,
 * PLAN.md section 2 "Course creation"), and tap-to-edit into [CourseEditorScreen] — the gateway to
 * fixing a wrong learned par or record, now by way of a layout (PLAN.md section 2 "Layouts"), so
 * this screen exists in Phase 4 even with no round-starting flow behind it yet.
 *
 * Each row's [detail] line is [courseListDetail]: a layout count, never a record — a record now
 * only shows where it's actually set, on the layout itself ([CourseEditorScreen]'s own layout
 * list, and [LayoutEditorScreen]).
 */
@Composable
fun ManageCoursesScreen(
    courses: List<Course>,
    onAddCourse: (name: String, holeCount: Int) -> Course?,
    onRenameCourse: (id: String, name: String) -> Unit,
    onAddLayout: (courseId: String, name: String, holeCount: Int) -> Layout?,
    onRenameLayout: (courseId: String, layoutId: String, name: String) -> Unit,
    onDeleteLayout: (courseId: String, layoutId: String) -> Unit,
    onSetLayoutPar: (courseId: String, layoutId: String, holeIndex: Int, newPar: Int) -> Unit,
    onSetLayoutRecordHolders: (courseId: String, layoutId: String, rawNames: String) -> Unit,
    onSetLayoutRecordToPar: (courseId: String, layoutId: String, toPar: Int) -> Unit,
    onDeleteCourse: (id: String) -> Unit,
    onDone: () -> Unit,
) {
    var mode by remember { mutableStateOf<ManageCoursesMode>(ManageCoursesMode.Listing) }

    val newCourseNameLauncher = rememberTextInputLauncher(label = "Course name") { typed ->
        val trimmed = typed?.trim()
        if (!trimmed.isNullOrEmpty()) mode = ManageCoursesMode.ChoosingHoleCount(trimmed)
    }

    when (val current = mode) {
        is ManageCoursesMode.Listing -> {
            val listState = rememberTransformingLazyColumnState()
            ScreenScaffold(scrollState = listState) { contentPadding ->
                TransformingLazyColumn(
                    state = listState,
                    contentPadding = contentPadding.withRoundEdgeInset(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    items(courses.size) { index ->
                        val course = courses[index]
                        PickableRow(
                            label = course.name,
                            detail = courseListDetail(course),
                            selected = false,
                            onClick = { mode = ManageCoursesMode.Editing(course.id) },
                            onLongClick = { mode = ManageCoursesMode.Editing(course.id) },
                        )
                    }
                    item {
                        PickableRow(label = "+ New course…", selected = false, onClick = { newCourseNameLauncher(null) })
                    }
                    item {
                        PrimaryActionRow(label = "DONE", onClick = onDone)
                    }
                }
            }
        }

        is ManageCoursesMode.ChoosingHoleCount -> HoleCountPickerScreen(
            subjectName = current.courseName,
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
                    onRenameCourse = onRenameCourse,
                    onAddLayout = onAddLayout,
                    onRenameLayout = onRenameLayout,
                    onDeleteLayout = onDeleteLayout,
                    onSetLayoutPar = onSetLayoutPar,
                    onSetLayoutRecordHolders = onSetLayoutRecordHolders,
                    onSetLayoutRecordToPar = onSetLayoutRecordToPar,
                    onDeleteCourse = {
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

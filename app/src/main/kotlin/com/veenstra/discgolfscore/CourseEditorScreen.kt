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

private sealed interface CourseEditorMode {
    data object Listing : CourseEditorMode
    data class AddingLayoutHoleCount(val name: String) : CourseEditorMode
    data class EditingLayout(val layoutId: String) : CourseEditorMode
}

/**
 * A course's own editor (PLAN.md section 2 "Layouts", section 3 "Course editor"): rename the
 * course, then the list of its [Course.layouts] — add one, and tap/long-press one to reach
 * [LayoutEditorScreen], where its pars and record actually live now. Reused unchanged from two
 * entry points — long-pressing a course on the new-round setup screen, and tapping/long-pressing
 * one on the courses manager — rather than being duplicated, the same call PLAN.md section 3 made
 * before layouts existed.
 *
 * Owns every text-input launcher this screen and [LayoutEditorScreen] need internally (rename,
 * "+ New layout…"'s name step, [LayoutEditorScreen]'s own rename/record launchers) rather than
 * leaving them to its two call sites — the same "fold it into the screen that needs it" call
 * [LayoutEditorScreen]'s own doc comment explains, now doubly worth it since this screen has two
 * call sites instead of one.
 */
@Composable
fun CourseEditorScreen(
    course: Course,
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
    var mode by remember { mutableStateOf<CourseEditorMode>(CourseEditorMode.Listing) }

    val renameCourseLauncher = rememberTextInputLauncher(label = "Course name") { typed ->
        val trimmed = typed?.trim()
        if (!trimmed.isNullOrEmpty()) onRenameCourse(course.id, trimmed)
    }
    val newLayoutNameLauncher = rememberTextInputLauncher(label = "Layout name") { typed ->
        val trimmed = typed?.trim()
        if (!trimmed.isNullOrEmpty()) mode = CourseEditorMode.AddingLayoutHoleCount(trimmed)
    }

    when (val current = mode) {
        is CourseEditorMode.Listing -> {
            val listState = rememberTransformingLazyColumnState()
            ScreenScaffold(scrollState = listState) { contentPadding ->
                TransformingLazyColumn(
                    state = listState,
                    contentPadding = contentPadding.withRoundEdgeInset(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    item {
                        PickableRow(
                            label = course.name,
                            trailing = "✏️",
                            selected = false,
                            onClick = { renameCourseLauncher(course.name) },
                        )
                    }
                    items(course.layouts.size) { index ->
                        val layout = course.layouts[index]
                        PickableRow(
                            label = layout.name,
                            detail = formatLayoutRecord(layout),
                            selected = false,
                            onClick = { mode = CourseEditorMode.EditingLayout(layout.id) },
                            onLongClick = { mode = CourseEditorMode.EditingLayout(layout.id) },
                        )
                    }
                    item {
                        PickableRow(label = "+ New layout…", selected = false, onClick = { newLayoutNameLauncher(null) })
                    }
                    item {
                        PickableRow(label = "Delete course", selected = false, onClick = { onDeleteCourse(course.id) })
                    }
                    item {
                        PrimaryActionRow(label = "SAVE", onClick = onDone)
                    }
                }
            }
        }

        is CourseEditorMode.AddingLayoutHoleCount -> HoleCountPickerScreen(
            subjectName = current.name,
            onCreate = { holeCount ->
                onAddLayout(course.id, current.name, holeCount)
                mode = CourseEditorMode.Listing
            },
            onCancel = { mode = CourseEditorMode.Listing },
        )

        is CourseEditorMode.EditingLayout -> {
            val liveLayout = course.layouts.find { it.id == current.layoutId }
            if (liveLayout != null) {
                LayoutEditorScreen(
                    layout = liveLayout,
                    onRename = { name -> onRenameLayout(course.id, liveLayout.id, name) },
                    onSetRecordHolders = { rawNames -> onSetLayoutRecordHolders(course.id, liveLayout.id, rawNames) },
                    onSetRecordToPar = { toPar -> onSetLayoutRecordToPar(course.id, liveLayout.id, toPar) },
                    onSetPar = { holeIndex, newPar -> onSetLayoutPar(course.id, liveLayout.id, holeIndex, newPar) },
                    onDelete = {
                        onDeleteLayout(course.id, liveLayout.id)
                        mode = CourseEditorMode.Listing
                    },
                    canDelete = course.layouts.size > 1,
                    onDone = { mode = CourseEditorMode.Listing },
                )
            } else {
                LaunchedEffect(Unit) { mode = CourseEditorMode.Listing }
            }
        }
    }
}

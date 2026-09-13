package com.veenstra.discgolfscore

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.material3.EdgeButton
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text

/**
 * The setup flow is several screens modeled as one piece of local Compose state within this file
 * (mirrors ultimate-score's private `SetupMode`, PLAN.md section 13) rather than a second
 * `AppScreen` case — creating/editing a course, layout, or player here is a detour from this
 * screen, not a new place in the app.
 */
private sealed interface SetupMode {
    data object Picking : SetupMode
    data class ChoosingHoleCount(val courseName: String) : SetupMode
    data class PickingLayout(val courseId: String) : SetupMode
    data class EditingCourse(val courseId: String) : SetupMode
    data class EditingPlayer(val playerId: String) : SetupMode
}

/**
 * Pick a course (single-select), resolve which layout to play (PLAN.md section 2 "Round setup is
 * course → layout, with a skip"), tick players (multi-select), `START`.
 *
 * Picking a course whose [Course.layouts] has exactly one member resolves [selectedLayoutId]
 * immediately, with no extra screen — single-layout courses feel exactly as they did before
 * layouts existed. Two or more layouts routes to [SetupMode.PickingLayout] ([LayoutPickerScreen])
 * first; only once that screen calls back with a chosen layout do both [selectedCourseId] and
 * [selectedLayoutId] update together, so a course is never shown "picked" while its layout is
 * still unresolved.
 */
@Composable
fun NewRoundSetupScreen(
    courses: List<Course>,
    players: List<Player>,
    onAddCourse: (name: String, holeCount: Int) -> Course?,
    onRenameCourse: (id: String, name: String) -> Unit,
    onAddLayout: (courseId: String, name: String, holeCount: Int) -> Layout?,
    onRenameLayout: (courseId: String, layoutId: String, name: String) -> Unit,
    onDeleteLayout: (courseId: String, layoutId: String) -> Unit,
    onSetLayoutPar: (courseId: String, layoutId: String, holeIndex: Int, newPar: Int) -> Unit,
    onDeleteCourse: (id: String) -> Unit,
    onAddPlayer: (name: String) -> Player?,
    onRenamePlayer: (id: String, name: String) -> Unit,
    onDeletePlayer: (id: String) -> Unit,
    onStart: (courseId: String, layoutId: String, playerIds: Set<String>) -> Unit,
) {
    var mode by remember { mutableStateOf<SetupMode>(SetupMode.Picking) }
    var selectedCourseId by remember { mutableStateOf<String?>(null) }
    var selectedLayoutId by remember { mutableStateOf<String?>(null) }
    var selectedPlayerIds by remember { mutableStateOf<Set<String>>(emptySet()) }

    // Delete-clears-selection (PLAN.md section 3), extended to a layout deleted out from under an
    // already-resolved selection (e.g. via ManageCoursesScreen elsewhere in the same session) — the
    // course's own deletion is still handled inline where it's deleted, below; this only guards the
    // one-level-deeper case a plain `find` can't catch by itself.
    LaunchedEffect(courses, selectedCourseId, selectedLayoutId) {
        val course = selectedCourseId?.let { id -> courses.find { it.id == id } }
        if (selectedLayoutId != null && course != null && course.layouts.none { it.id == selectedLayoutId }) {
            selectedLayoutId = null
        }
    }

    val newCourseNameLauncher = rememberTextInputLauncher(label = "Course name") { typed ->
        val trimmed = typed?.trim()
        if (!trimmed.isNullOrEmpty()) mode = SetupMode.ChoosingHoleCount(trimmed)
    }
    val newPlayerNameLauncher = rememberTextInputLauncher(label = "Player name") { typed ->
        val trimmed = typed?.trim()
        if (!trimmed.isNullOrEmpty()) {
            val player = onAddPlayer(trimmed)
            if (player != null) selectedPlayerIds = selectedPlayerIds + player.id
        }
    }
    val renamePlayerLauncher = rememberTextInputLauncher(label = "Player name") { typed ->
        val editing = mode as? SetupMode.EditingPlayer ?: return@rememberTextInputLauncher
        val trimmed = typed?.trim()
        if (!trimmed.isNullOrEmpty()) onRenamePlayer(editing.playerId, trimmed)
    }

    when (val current = mode) {
        is SetupMode.Picking -> PickingScreen(
            courses = courses,
            players = players,
            selectedCourseId = selectedCourseId,
            selectedPlayerIds = selectedPlayerIds,
            onSelectCourse = { course ->
                val singleLayout = course.layouts.singleOrNull()
                if (singleLayout != null) {
                    selectedCourseId = course.id
                    selectedLayoutId = singleLayout.id
                } else {
                    mode = SetupMode.PickingLayout(course.id)
                }
            },
            onTogglePlayer = { id ->
                selectedPlayerIds = if (id in selectedPlayerIds) selectedPlayerIds - id else selectedPlayerIds + id
            },
            onLongPressCourse = { course -> mode = SetupMode.EditingCourse(course.id) },
            onLongPressPlayer = { player -> mode = SetupMode.EditingPlayer(player.id) },
            onAddCourse = { newCourseNameLauncher(null) },
            onAddPlayer = { newPlayerNameLauncher(null) },
            onStart = {
                val courseId = selectedCourseId
                val layoutId = selectedLayoutId
                if (courseId != null && layoutId != null && selectedPlayerIds.isNotEmpty()) {
                    onStart(courseId, layoutId, selectedPlayerIds)
                }
            },
            canStart = selectedCourseId != null && selectedLayoutId != null && selectedPlayerIds.isNotEmpty(),
        )

        is SetupMode.ChoosingHoleCount -> HoleCountPickerScreen(
            subjectName = current.courseName,
            onCreate = { holeCount ->
                val course = onAddCourse(current.courseName, holeCount)
                if (course != null) {
                    selectedCourseId = course.id
                    selectedLayoutId = course.layouts.single().id // just-created courses always have exactly one
                }
                mode = SetupMode.Picking
            },
            onCancel = { mode = SetupMode.Picking },
        )

        is SetupMode.PickingLayout -> {
            val liveCourse = courses.find { it.id == current.courseId }
            if (liveCourse != null) {
                LayoutPickerScreen(
                    course = liveCourse,
                    selectedLayoutId = selectedLayoutId,
                    onSelectLayout = { layoutId ->
                        selectedCourseId = liveCourse.id
                        selectedLayoutId = layoutId
                        mode = SetupMode.Picking
                    },
                    onAddLayout = { name, holeCount -> onAddLayout(liveCourse.id, name, holeCount) },
                    onCancel = { mode = SetupMode.Picking },
                )
            } else {
                LaunchedEffect(Unit) { mode = SetupMode.Picking }
            }
        }

        is SetupMode.EditingCourse -> {
            // Re-read the live course every recomposition (not the snapshot captured when this
            // mode was entered) so a layout/par edit shows up immediately in this same screen.
            val live = courses.find { it.id == current.courseId }
            if (live != null) {
                CourseEditorScreen(
                    course = live,
                    onRenameCourse = onRenameCourse,
                    onAddLayout = onAddLayout,
                    onRenameLayout = onRenameLayout,
                    onDeleteLayout = onDeleteLayout,
                    onSetLayoutPar = onSetLayoutPar,
                    onDeleteCourse = {
                        onDeleteCourse(live.id)
                        // Delete-clears-selection (PLAN.md section 3): deleting the selected
                        // course must leave no course selected, re-disabling START. This is the
                        // exact bug ultimate-score section 13 fixed.
                        if (selectedCourseId == live.id) {
                            selectedCourseId = null
                            selectedLayoutId = null
                        }
                        mode = SetupMode.Picking
                    },
                    onDone = { mode = SetupMode.Picking },
                )
            } else {
                // Only reachable if the course vanished out from under this screen (e.g. deleted
                // from the courses manager in a scenario this screen can't itself observe mid-
                // edit). LaunchedEffect rather than assigning `mode` directly in the composable
                // body, which would be a state mutation during composition.
                LaunchedEffect(Unit) { mode = SetupMode.Picking }
            }
        }

        is SetupMode.EditingPlayer -> {
            val live = players.find { it.id == current.playerId }
            if (live != null) {
                PlayerEditorScreen(
                    player = live,
                    onRename = { renamePlayerLauncher(live.name) },
                    onDelete = {
                        onDeletePlayer(live.id)
                        // Deleting a ticked player unticks them (PLAN.md section 3) — the same
                        // rule as the course above, doubled here by the multi-select model.
                        selectedPlayerIds = selectedPlayerIds - live.id
                        mode = SetupMode.Picking
                    },
                    onDone = { mode = SetupMode.Picking },
                )
            } else {
                LaunchedEffect(Unit) { mode = SetupMode.Picking }
            }
        }
    }
}

@Composable
private fun PickingScreen(
    courses: List<Course>,
    players: List<Player>,
    selectedCourseId: String?,
    selectedPlayerIds: Set<String>,
    onSelectCourse: (Course) -> Unit,
    onTogglePlayer: (String) -> Unit,
    onLongPressCourse: (Course) -> Unit,
    onLongPressPlayer: (Player) -> Unit,
    onAddCourse: () -> Unit,
    onAddPlayer: () -> Unit,
    onStart: () -> Unit,
    canStart: Boolean,
) {
    val listState = rememberTransformingLazyColumnState()

    ScreenScaffold(scrollState = listState) { contentPadding ->
        TransformingLazyColumn(
            state = listState,
            contentPadding = contentPadding.withRoundEdgeInset().withEdgeButtonReserve(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            item { SectionHeader("Course") }
            items(courses.size) { index ->
                val course = courses[index]
                PickableRow(
                    label = course.name,
                    selected = selectedCourseId == course.id,
                    leading = if (selectedCourseId == course.id) "●" else "○",
                    onClick = { onSelectCourse(course) },
                    onLongClick = { onLongPressCourse(course) },
                )
            }
            item {
                PickableRow(label = "+ New course…", selected = false, onClick = onAddCourse)
            }
            item { SectionHeader("Players") }
            items(players.size) { index ->
                val player = players[index]
                val ticked = player.id in selectedPlayerIds
                PickableRow(
                    label = player.name,
                    selected = ticked,
                    leading = if (ticked) "☑" else "☐",
                    tint = player.rowTint(),
                    onClick = { onTogglePlayer(player.id) },
                    onLongClick = { onLongPressPlayer(player) },
                )
            }
            item {
                PickableRow(label = "+ New player…", selected = false, onClick = onAddPlayer)
            }
        }
        EdgeButton(
            onClick = onStart,
            enabled = canStart,
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            Text(text = "START")
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text.uppercase(),
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 2.dp),
    )
}

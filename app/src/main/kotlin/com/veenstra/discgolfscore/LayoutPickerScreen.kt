package com.veenstra.discgolfscore

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
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
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text

private sealed interface LayoutPickerMode {
    data object Listing : LayoutPickerMode
    data class ChoosingHoleCount(val name: String) : LayoutPickerMode
}

/**
 * Step two of round setup's course → layout pick (PLAN.md section 2 "Round setup is course →
 * layout, with a skip"): reached only when the just-picked course has **two or more**
 * [Course.layouts] — a single-layout course resolves its one layout automatically and never shows
 * this screen, so single-layout courses feel exactly as they always have. Picking a row here calls
 * [onSelectLayout] and returns to the picking screen with both the course and that layout selected;
 * [onCancel] returns without changing any selection, the same "leave things as they were" contract
 * [HoleCountPickerScreen]'s own `onCancel` already has.
 *
 * "+ New layout…" mirrors the setup screen's own "+ New course…" (PLAN.md section 2 "Round setup"
 * — "mirroring how '+ New course…' already works inline in new-round setup"): a name, then
 * [HoleCountPickerScreen], then the new layout is created and immediately selected via
 * [onSelectLayout] — so you can add the layout you actually meant to play without leaving setup.
 */
@Composable
fun LayoutPickerScreen(
    course: Course,
    selectedLayoutId: String?,
    onSelectLayout: (layoutId: String) -> Unit,
    onAddLayout: (name: String, holeCount: Int) -> Layout?,
    onCancel: () -> Unit,
) {
    var mode by remember { mutableStateOf<LayoutPickerMode>(LayoutPickerMode.Listing) }

    val newLayoutNameLauncher = rememberTextInputLauncher(label = "Layout name") { typed ->
        val trimmed = typed?.trim()
        if (!trimmed.isNullOrEmpty()) mode = LayoutPickerMode.ChoosingHoleCount(trimmed)
    }

    when (val current = mode) {
        is LayoutPickerMode.Listing -> {
            val listState = rememberTransformingLazyColumnState()
            ScreenScaffold(scrollState = listState) { contentPadding ->
                TransformingLazyColumn(
                    state = listState,
                    contentPadding = contentPadding.withRoundEdgeInset(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    item {
                        Text(
                            text = course.name,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                        )
                    }
                    items(course.layouts.size) { index ->
                        val layout = course.layouts[index]
                        PickableRow(
                            label = layout.name,
                            selected = layout.id == selectedLayoutId,
                            leading = if (layout.id == selectedLayoutId) "●" else "○",
                            onClick = { onSelectLayout(layout.id) },
                        )
                    }
                    item {
                        PickableRow(label = "+ New layout…", selected = false, onClick = { newLayoutNameLauncher(null) })
                    }
                    item {
                        PickableRow(label = "Cancel", selected = false, onClick = onCancel)
                    }
                }
            }
        }

        is LayoutPickerMode.ChoosingHoleCount -> HoleCountPickerScreen(
            subjectName = current.name,
            onCreate = { holeCount ->
                val layout = onAddLayout(current.name, holeCount)
                if (layout != null) onSelectLayout(layout.id)
                mode = LayoutPickerMode.Listing
            },
            onCancel = { mode = LayoutPickerMode.Listing },
        )
    }
}

package com.veenstra.discgolfscore

import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.material3.ScreenScaffold

/**
 * Reached by long-pressing a saved player, on either the new-round setup screen or the players
 * manager (PLAN.md section 3: "Long-press any course or player row → rename... or delete"):
 * rename via the shared text-input launcher, or delete. There is no par list here — that's what
 * makes this screen so much smaller than [CourseEditorScreen] for the same gesture.
 */
@Composable
fun PlayerEditorScreen(
    player: Player,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onDone: () -> Unit,
) {
    val listState = rememberTransformingLazyColumnState()

    ScreenScaffold(scrollState = listState) { contentPadding ->
        TransformingLazyColumn(
            state = listState,
            contentPadding = contentPadding.withRoundEdgeInset(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            item {
                PickableRow(label = player.name, selected = false, tint = player.rowTint(), onClick = onRename)
            }
            item {
                PickableRow(label = "Delete player", selected = false, onClick = onDelete)
            }
            item {
                PrimaryActionRow(label = "DONE", onClick = onDone)
            }
        }
    }
}

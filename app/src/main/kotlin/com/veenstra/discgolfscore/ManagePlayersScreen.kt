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

private sealed interface ManagePlayersMode {
    data object Listing : ManagePlayersMode
    data class Editing(val playerId: String) : ManagePlayersMode
}

/**
 * Housekeeping outside a round (PLAN.md section 3 "Players / Courses managers"): the saved
 * player list, `+ New player…`, and tap-to-edit/rename/delete — same list + create + edit pattern
 * as the new-round setup screen's player section, minus the ticking.
 */
@Composable
fun ManagePlayersScreen(
    players: List<Player>,
    onAddPlayer: (name: String) -> Player?,
    onRenamePlayer: (id: String, name: String) -> Unit,
    onDeletePlayer: (id: String) -> Unit,
    onDone: () -> Unit,
) {
    var mode by remember { mutableStateOf<ManagePlayersMode>(ManagePlayersMode.Listing) }

    val newPlayerLauncher = rememberTextInputLauncher(label = "Player name") { typed ->
        val trimmed = typed?.trim()
        if (!trimmed.isNullOrEmpty()) onAddPlayer(trimmed)
    }
    val renameLauncher = rememberTextInputLauncher(label = "Player name") { typed ->
        val editing = mode as? ManagePlayersMode.Editing ?: return@rememberTextInputLauncher
        val trimmed = typed?.trim()
        if (!trimmed.isNullOrEmpty()) onRenamePlayer(editing.playerId, trimmed)
    }

    when (val current = mode) {
        is ManagePlayersMode.Listing -> {
            val listState = rememberTransformingLazyColumnState()
            ScreenScaffold(scrollState = listState) { contentPadding ->
                TransformingLazyColumn(
                    state = listState,
                    contentPadding = contentPadding.withRoundEdgeInset().withEdgeButtonReserve(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    items(players.size) { index ->
                        val player = players[index]
                        PickableRow(
                            label = player.name,
                            selected = false,
                            tint = player.rowTint(),
                            onClick = { mode = ManagePlayersMode.Editing(player.id) },
                            onLongClick = { mode = ManagePlayersMode.Editing(player.id) },
                        )
                    }
                    item {
                        PickableRow(label = "+ New player…", selected = false, onClick = { newPlayerLauncher(null) })
                    }
                }
                EdgeButton(onClick = onDone, modifier = Modifier.align(Alignment.BottomCenter)) {
                    Text(text = "DONE")
                }
            }
        }

        is ManagePlayersMode.Editing -> {
            val live = players.find { it.id == current.playerId }
            if (live != null) {
                PlayerEditorScreen(
                    player = live,
                    onRename = { renameLauncher(live.name) },
                    onDelete = {
                        onDeletePlayer(live.id)
                        mode = ManagePlayersMode.Listing
                    },
                    onDone = { mode = ManagePlayersMode.Listing },
                )
            } else {
                LaunchedEffect(Unit) { mode = ManagePlayersMode.Listing }
            }
        }
    }
}

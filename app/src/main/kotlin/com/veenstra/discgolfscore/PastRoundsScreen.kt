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
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text

private sealed interface PastRoundsMode {
    data object Listing : PastRoundsMode
    data class Viewing(val id: String) : PastRoundsMode
    data class ConfirmingDelete(val id: String) : PastRoundsMode
}

/**
 * Every finished round, newest first (PLAN.md section 3 "Past rounds"): course name with the date
 * under it. Tapping one re-opens the same [FinalScoreboardScreen] the round ended on, plus its date
 * and a delete row. Deleting asks first — unlike a player or a course, a round can't be recreated.
 */
@Composable
fun PastRoundsScreen(
    history: List<SavedRound>,
    onDeleteRound: (id: String) -> Unit,
    onDone: () -> Unit,
) {
    var mode by remember { mutableStateOf<PastRoundsMode>(PastRoundsMode.Listing) }

    when (val current = mode) {
        is PastRoundsMode.Listing -> {
            val listState = rememberTransformingLazyColumnState()
            ScreenScaffold(scrollState = listState) { contentPadding ->
                TransformingLazyColumn(
                    state = listState,
                    contentPadding = contentPadding.withRoundEdgeInset(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    item {
                        Text(
                            text = "PAST ROUNDS",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 8.dp),
                        )
                    }
                    if (history.isEmpty()) {
                        item {
                            Text(
                                text = "No rounds yet",
                                fontSize = 13.sp,
                                textAlign = TextAlign.Center,
                                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.75f),
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                    items(history.size) { index ->
                        val saved = history[index]
                        PickableRow(
                            label = saved.round.courseName,
                            detail = formatPastRoundDetail(saved),
                            selected = false,
                            onClick = { mode = PastRoundsMode.Viewing(saved.id) },
                        )
                    }
                    item {
                        PrimaryActionRow(label = "DONE", onClick = onDone)
                    }
                }
            }
        }

        is PastRoundsMode.Viewing -> {
            val saved = history.find { it.id == current.id }
            if (saved != null) {
                FinalScoreboardScreen(
                    round = saved.round,
                    caption = formatRoundDate(saved.finishedAt),
                    onDelete = { mode = PastRoundsMode.ConfirmingDelete(saved.id) },
                    onDone = { mode = PastRoundsMode.Listing },
                )
            } else {
                LaunchedEffect(Unit) { mode = PastRoundsMode.Listing }
            }
        }

        is PastRoundsMode.ConfirmingDelete -> {
            val saved = history.find { it.id == current.id }
            if (saved != null) {
                ConfirmScreen(
                    title = "Delete round?",
                    detail = "${saved.round.courseName} · ${formatRoundDate(saved.finishedAt)}",
                    confirmLabel = "Yes, delete",
                    cancelLabel = "No, keep it",
                    onConfirm = {
                        onDeleteRound(saved.id)
                        mode = PastRoundsMode.Listing
                    },
                    onCancel = { mode = PastRoundsMode.Viewing(saved.id) },
                )
            } else {
                LaunchedEffect(Unit) { mode = PastRoundsMode.Listing }
            }
        }
    }
}

/** A listing row's detail line: the layout name and finish date, or just the date for a round saved before layouts existed ([RoundState.layoutName] blank — PLAN.md section 2 "RoundState snapshots the layout"). */
private fun formatPastRoundDetail(saved: SavedRound): String {
    val date = formatRoundDate(saved.finishedAt)
    return if (saved.round.layoutName.isNotBlank()) "${saved.round.layoutName} · $date" else date
}

package com.veenstra.discgolfscore

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.material3.EdgeButton
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text

/** The "NEW COURSE RECORD!" banner's color — a bright gold that reads clearly against the app's black background, distinct from any [PLAYER_PALETTE] tint a scoreboard row might carry. */
private val RECORD_GOLD = Color(0xFFFFC107)

/**
 * PLAN.md section 3's "Final scoreboard" — the real screen, built out this phase from Phase 5's
 * explicitly-minimal `FinishedRoundPlaceholderScreen` stand-in (this file's Phase 5 name; the
 * composable itself is renamed [FinalScoreboardScreen] to match, and every call site updated).
 *
 * Sorted by total strokes, showing the raw total *and* the to-par — PLAN.md section 3: "This is the
 * only place the stroke total appears; during play, to-par is what's shown." Ranking, including
 * shared ranks on ties (`1, 1, 3`), comes entirely from [RoundState.scoreboard] — a pure, tested
 * function in `Scoreboard.kt` — so this composable never recomputes or re-sorts anything itself; it
 * only renders whatever `scoreboard()` already decided.
 *
 * **The round is still in storage while this screen is showing** (PLAN.md section 2 "Finished round
 * persistence"): [onDone] is the *only* control that clears it (via `RoundViewModel.done()`), so an
 * accidental right-swipe — which Wear reserves for back/dismiss and this app can't rebind — force-
 * closing the app here does not lose the card. A relaunch while this screen was up comes straight
 * back to it, because [RoundState.finished] is itself persisted and [AppScreen.Hole] routes here
 * whenever it's true (see `WearApp.kt`), not just right after `Finish` is dispatched.
 *
 * If the round's course was deleted mid-round, this screen is unaffected (PLAN.md section 3): it
 * reads only [RoundState.courseName] (a snapshot taken at round start) and [RoundState.scoreboard],
 * neither of which ever looks the course back up. Par write-back having already silently no-op'd
 * happened earlier, in `RoundViewModel`, not here.
 *
 * Also how a past round is re-shown from [PastRoundsScreen]: [caption] adds its date under the
 * course name and [onDelete] adds a delete row below the standings. The live finish passes neither.
 *
 * [newRecord] is the fanfare PLAN.md section 2 "Course record" asks for: a banner above the
 * standings when this round is the one that just advanced its course's record ([RoundViewModel]'s
 * `justSetRecord`, computed once at the moment [RoundViewModel.finishRound] ran the write-back).
 * [WearApp] only ever passes `true` on the live finish path, never when [PastRoundsScreen] reopens
 * an old round — revisiting a record-holding round later isn't the moment it was *set*.
 */
@Composable
fun FinalScoreboardScreen(
    round: RoundState,
    onDone: () -> Unit,
    caption: String? = null,
    onDelete: (() -> Unit)? = null,
    newRecord: Boolean = false,
) {
    val listState = rememberTransformingLazyColumnState()
    val rows = round.scoreboard()

    ScreenScaffold(scrollState = listState) { contentPadding ->
        TransformingLazyColumn(
            state = listState,
            contentPadding = contentPadding.withRoundEdgeInset().withEdgeButtonReserve(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            item {
                Text(
                    text = "FINAL",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 2.dp),
                )
            }
            if (newRecord) {
                item {
                    Text(
                        text = "🏆 NEW COURSE RECORD!",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = RECORD_GOLD,
                        modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
                    )
                }
            }
            item {
                Text(
                    text = round.courseName,
                    fontSize = 13.sp,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.75f),
                    modifier = Modifier.fillMaxWidth().padding(bottom = if (caption == null) 10.dp else 2.dp),
                )
            }
            if (caption != null) {
                item {
                    Text(
                        text = caption,
                        fontSize = 11.sp,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.75f),
                        modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp),
                    )
                }
            }
            items(rows.size) { index ->
                ScoreboardRowView(rows[index])
            }
            if (onDelete != null) {
                item {
                    PickableRow(label = "Delete round", selected = false, onClick = onDelete)
                }
            }
        }
        EdgeButton(onClick = onDone, modifier = Modifier.align(Alignment.BottomCenter)) {
            Text(text = "DONE")
        }
    }
}

/**
 * One line of the final standings: rank, name (ellipsized — PLAN.md section 6 Phase 6 wants long
 * names verified here explicitly), raw stroke total, then to-par — the exact `1 Alex  53 −1` shape
 * PLAN.md section 3's mockup draws. [ScoreboardRow.rank] already accounts for shared ranks on ties;
 * this view never compares [row] against any other row to decide what to print.
 *
 * Tinted with [Player.rowTint] the same as every other row that names a player (players screen,
 * hole screen) — Derek: "the final scoreboard screen should also have the player colours."
 */
@Composable
private fun ScoreboardRowView(row: ScoreboardRow) {
    val tint = row.player.rowTint()
    Row(
        modifier = Modifier
            .roundSafeWidth()
            .height(40.dp)
            .then(
                if (tint != null) {
                    Modifier.clip(RoundedCornerShape(20.dp)).background(tint)
                } else {
                    Modifier
                },
            )
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "${row.rank}",
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(end = 8.dp).size(width = 18.dp, height = 20.dp),
        )
        Text(
            text = row.player.name,
            fontSize = 14.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(end = 6.dp),
        )
        Text(
            text = "${row.strokes}",
            fontSize = 13.sp,
            textAlign = TextAlign.End,
            modifier = Modifier.padding(end = 6.dp).size(width = 26.dp, height = 18.dp),
        )
        Text(
            text = formatToPar(row.toPar),
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.size(width = 30.dp, height = 18.dp),
        )
    }
}

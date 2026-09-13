package com.veenstra.discgolfscore

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text

/**
 * The app's front door (PLAN.md section 3 "Home"). `RESUME` shows only when [hasActiveRound] is
 * true, above `NEW ROUND` — the same order PLAN.md section 3's mockup draws it in. See `WearApp.kt`
 * for why this row is currently unreachable through this app's own navigation (a round in progress
 * always auto-routes a cold launch straight to [AppScreen.Hole], and there's no in-app path off
 * that screen back to [Home] except `DONE`, which clears the round first) — built regardless,
 * since PLAN.md specifies it unconditionally rather than only for paths this phase happens to add.
 *
 * `CLOUD` (`CLOUD_SAVES.md` section 6 Phase D) routes to [AppScreen.CloudSync] — status line,
 * `BACK UP NOW`, `RESTORE`, `CLEAR CONFIG`. Placed last: the newest row, least disruptive to
 * everyone's existing muscle memory for the rows above it.
 */
@Composable
fun HomeScreen(
    hasActiveRound: Boolean,
    onResume: () -> Unit,
    onNewRound: () -> Unit,
    onPastRounds: () -> Unit,
    onPlayers: () -> Unit,
    onCourses: () -> Unit,
    onCloud: () -> Unit,
) {
    val listState = rememberTransformingLazyColumnState()
    ScreenScaffold(scrollState = listState) { contentPadding ->
        TransformingLazyColumn(
            state = listState,
            contentPadding = contentPadding.withRoundEdgeInset(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            item {
                Text(
                    text = "🥏",
                    fontSize = 28.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                )
            }
            item {
                Text(
                    text = "DISC GOLF",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(top = 2.dp, bottom = 8.dp),
                )
            }
            if (hasActiveRound) {
                item {
                    PickableRow(label = "RESUME", selected = false, leading = "▶️", onClick = onResume)
                }
            }
            item {
                PickableRow(label = "NEW ROUND", selected = false, leading = "🥏", onClick = onNewRound)
            }
            item {
                PickableRow(label = "PLAYERS", selected = false, leading = "👥", onClick = onPlayers)
            }
            item {
                PickableRow(label = "COURSES", selected = false, leading = "⛳", onClick = onCourses)
            }
            item {
                PickableRow(label = "PAST ROUNDS", selected = false, leading = "📋", onClick = onPastRounds)
            }
            item {
                PickableRow(label = "CLOUD", selected = false, leading = "☁️", onClick = onCloud)
            }
        }
    }
}

package com.veenstra.discgolfscore

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text

/** How many holes one sub-row of the scorecard shows: holes 1–9, then 10–18, then 19–27, … */
internal const val SCORECARD_HOLES_PER_ROW = 9

/**
 * One hole's result relative to par, as the scorecard colors it — JomezPro-style: par gray,
 * birdie green, eagle (or better) blue, bogey red, anything worse than bogey purple.
 */
internal enum class ScoreResult(val color: Color) {
    EAGLE_OR_BETTER(Color(0xFF1E6FD9)),
    BIRDIE(Color(0xFF2E7D32)),
    PAR(Color(0xFF616161)),
    BOGEY(Color(0xFFD32F2F)),
    DOUBLE_BOGEY_OR_WORSE(Color(0xFF7B1FA2)),
}

internal fun scoreResult(strokes: Int, par: Int): ScoreResult = when {
    strokes <= par - 2 -> ScoreResult.EAGLE_OR_BETTER
    strokes == par - 1 -> ScoreResult.BIRDIE
    strokes == par -> ScoreResult.PAR
    strokes == par + 1 -> ScoreResult.BOGEY
    else -> ScoreResult.DOUBLE_BOGEY_OR_WORSE
}

/**
 * The strokes [playerId] recorded on each hole of [RoundState.holes], or `null` for a hole the
 * round hasn't reached yet — the same 1..[RoundState.currentHole] boundary [RoundState.toPar]
 * totals across, so the card never colors a hole the standings don't count (an unreached hole
 * still carries its untouched par pre-fill, not a real score).
 */
internal fun RoundState.scorecardStrokes(playerId: String): List<Int?> =
    holes.mapIndexed { index, hole -> if (index < currentHole) hole.strokes[playerId] else null }

/**
 * Each hole's par for the scorecard's `PAR` row, or `null` where it isn't known yet: a hole the
 * round hasn't reached whose par the layout never learned is still just its [DEFAULT_PAR]
 * pre-fill, not a par anyone chose.
 */
internal fun RoundState.scorecardPars(): List<Int?> =
    holes.mapIndexed { index, hole -> if (index < currentHole || hole.wasLearnedAtStart) hole.par else null }

/**
 * A read-only, JomezPro-style card of a round, opened by a [ScorecardButton] — mid-round from the
 * hole screen, or afterwards from a reopened past round. Every player gets a name line (with their
 * to-par) and one sub-row of colored squares per [SCORECARD_HOLES_PER_ROW] holes, so an 18-hole
 * layout is two sub-rows and a 36-hole layout four, all scrolling vertically rather than sideways.
 * A `PAR` block in the same shape sits on top as the key. Every hole on the layout gets a square,
 * numbered above it; holes not reached yet (or never reached, in a round finished early) stay blank
 * (and so does their par, unless the layout already knew it).
 */
@Composable
fun RoundScorecardScreen(round: RoundState, onClose: () -> Unit) {
    BackHandler(onBack = onClose)

    val listState = rememberTransformingLazyColumnState()
    val parChunks = round.scorecardPars().chunked(SCORECARD_HOLES_PER_ROW)
    val trueParChunks = round.holes.map { it.par }.chunked(SCORECARD_HOLES_PER_ROW)

    ScreenScaffold(scrollState = listState) { contentPadding ->
        TransformingLazyColumn(
            state = listState,
            contentPadding = contentPadding.withRoundEdgeInset(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            item {
                Text(
                    text = "SCORECARD",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(top = 2.dp, bottom = 2.dp),
                )
            }
            item { ScorecardNameLine(name = "PAR", trailing = null) }
            items(parChunks.size) { index ->
                ScorecardSubRow(
                    firstHole = index * SCORECARD_HOLES_PER_ROW + 1,
                    values = parChunks[index],
                    cellColor = { _, _ -> Color.White.copy(alpha = 0.10f) },
                )
            }
            round.players.forEach { player ->
                val strokeChunks = round.scorecardStrokes(player.id).chunked(SCORECARD_HOLES_PER_ROW)
                item { ScorecardNameLine(name = player.name, trailing = formatToPar(round.toPar(player.id))) }
                items(strokeChunks.size) { chunkIndex ->
                    val pars = trueParChunks[chunkIndex]
                    ScorecardSubRow(
                        firstHole = chunkIndex * SCORECARD_HOLES_PER_ROW + 1,
                        values = strokeChunks[chunkIndex],
                        cellColor = { holeInChunk, strokes -> scoreResult(strokes, pars[holeInChunk]).color },
                    )
                }
            }
            item {
                Box(
                    modifier = Modifier
                        .roundSafeWidth()
                        .height(40.dp)
                        .padding(top = 4.dp)
                        .clickable(onClick = onClose),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "◂ back",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.45f),
                    )
                }
            }
        }
    }
}

@Composable
private fun ScorecardNameLine(name: String, trailing: String?) {
    Row(
        modifier = Modifier.roundSafeWidth().padding(top = 6.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = name,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(end = 4.dp),
        )
        if (trailing != null) Text(text = trailing, fontSize = 12.sp, fontWeight = FontWeight.Bold)
    }
}

/**
 * One sub-row of up to [SCORECARD_HOLES_PER_ROW] square cells, each with its hole number (counting
 * from [firstHole]) printed small above it. A `null` value (a hole not reached
 * yet) is a faint empty square; a short last chunk (a 21-hole layout's holes 19–21) is padded out
 * with invisible cells so every sub-row's columns line up with the ones above it.
 */
@Composable
private fun ScorecardSubRow(firstHole: Int, values: List<Int?>, cellColor: (holeInChunk: Int, value: Int) -> Color) {
    Row(
        modifier = Modifier.roundSafeWidth().padding(vertical = 1.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        for (i in 0 until SCORECARD_HOLES_PER_ROW) {
            if (i >= values.size) {
                Spacer(modifier = Modifier.weight(1f))
                continue
            }
            val value = values[i]
            Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = (firstHole + i).toString(),
                    fontSize = 8.sp,
                    lineHeight = 9.sp,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.55f),
                    maxLines = 1,
                )
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(1f)
                        .clip(RoundedCornerShape(3.dp))
                        .background(if (value == null) Color.White.copy(alpha = 0.06f) else cellColor(i, value)),
                    contentAlignment = Alignment.Center,
                ) {
                    if (value != null) {
                        Text(text = value.toString(), fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color.White)
                    }
                }
            }
        }
    }
}

/**
 * Opens the hole-by-hole [RoundScorecardScreen] — a secondary pill, the same 48dp height as
 * [PrimaryActionRow] but a faint fill so it doesn't compete with it. Sits under "Next hole" on the
 * hole screen (Derek asked for a button there instead of tapping the TOTAL standings) and under
 * the standings when a past round is reopened.
 */
@Composable
internal fun ScorecardButton(onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .padding(top = 4.dp)
            .roundSafeWidth()
            .height(48.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(Color.White.copy(alpha = 0.10f))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = "Scorecard", fontSize = 13.sp, fontWeight = FontWeight.Bold)
    }
}

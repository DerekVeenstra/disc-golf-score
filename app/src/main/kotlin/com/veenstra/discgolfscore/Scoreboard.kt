package com.veenstra.discgolfscore

/**
 * One player's row on the final scoreboard (PLAN.md section 3 "Final scoreboard"): a rank, the
 * raw stroke total, and the same total relative to par shown during play.
 */
data class ScoreboardRow(
    val player: Player,
    val rank: Int,
    val strokes: Int,
    val toPar: Int,
)

/**
 * [RoundState.players] ranked by [RoundState.strokesThrough] ascending (fewest strokes wins).
 * Ties share a rank and the rank after a tie skips accordingly (`1, 1, 3`) — PLAN.md section 2
 * "Ties on the scoreboard" — standard "competition ranking" (as opposed to a dense ranking, which
 * would number the row after a tie `2` instead of `3`).
 */
fun RoundState.scoreboard(): List<ScoreboardRow> {
    val totals = players
        .map { player -> Triple(player, strokesThrough(player.id), toPar(player.id)) }
        .sortedBy { it.second }

    var rank = 0
    var lastStrokes: Int? = null
    return totals.mapIndexed { index, (player, strokes, toPar) ->
        if (strokes != lastStrokes) {
            rank = index + 1
            lastStrokes = strokes
        }
        ScoreboardRow(player, rank, strokes, toPar)
    }
}

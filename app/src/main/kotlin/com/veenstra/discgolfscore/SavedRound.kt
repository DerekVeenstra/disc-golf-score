package com.veenstra.discgolfscore

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/**
 * A finished round kept on the watch after `DONE`, listed under Home's `PAST ROUNDS`. [round] is
 * the exact finished [RoundState] the final scoreboard showed — same snapshot players, course name,
 * and holes — so a past round is re-shown by the same `scoreboard()` it ended on, with nothing
 * recomputed differently. [finishedAt] is epoch milliseconds, set when the round was finished.
 */
data class SavedRound(val id: String, val finishedAt: Long, val round: RoundState)

/** [SavedRound.finishedAt] as a localized medium date (`Sep 11, 2026` in en-US), in the watch's own time zone. */
internal fun formatRoundDate(
    epochMillis: Long,
    zone: ZoneId = ZoneId.systemDefault(),
    locale: Locale = Locale.getDefault(),
): String =
    DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
        .withLocale(locale)
        .format(Instant.ofEpochMilli(epochMillis).atZone(zone))

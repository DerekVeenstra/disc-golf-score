package com.veenstra.discgolfscore

/**
 * The outcome of the most recent `BACK UP NOW`/`RESTORE`, as [RoundViewModel] exposes it to the
 * Cloud screen (`CLOUD_SAVES.md` section 6 Phase D: "status line... Result text naming counts and
 * warnings"). [RoundViewModel.backUpNow]/[RoundViewModel.restore] set this to [InProgress] the
 * instant they're called and never leave it there — every path out (config missing, a
 * [BackupClient] failure, or a genuine success) resolves to [Success] or [Failure], so the screen
 * is never stuck showing a spinner.
 */
sealed interface SyncStatus {
    data object Idle : SyncStatus
    data object InProgress : SyncStatus
    data class Success(val message: String) : SyncStatus
    data class Failure(val message: String) : SyncStatus
}

/** `"Not configured"` — [RoundViewModel.backUpNow]/[RoundViewModel.restore] resolve straight to this [SyncStatus.Failure] without ever constructing a [BackupClient] when [SyncConfigStore] has nothing saved (`CLOUD_SAVES.md` section 6 Phase B: the "not-configured" path). */
internal const val NOT_CONFIGURED_MESSAGE = "Not configured"

/** `"Backed up 4 players · 3 courses · 27 rounds"`, with `"· 2 rows skipped"` appended when the script's own validation on push (`CLOUD_SAVES.md`'s "tradeoff on hand-editing") reported any [PushResult.Success.warnings]. Counts come from the script's response, not from what was sent — see [PushResult.Success.counts]'s own doc for why those can differ. Each count singularizes independently ("1 player · 2 courses · 1 round"), since a push commonly has exactly one of some counts and not others. */
internal fun formatPushSummary(result: PushResult.Success): String {
    val base = "Backed up ${countWord(result.counts.players, "player")} · " +
        "${countWord(result.counts.courses, "course")} · ${countWord(result.counts.rounds, "round")}"
    return appendSkippedRows(base, result.warnings)
}

/** `"Successfully synced"`, with `"· 2 rows skipped"` appended the same way [formatPushSummary] does. Deliberately doesn't name counts the way [formatPushSummary] does: a restore merges players/courses/rounds together (`CLOUD_SAVES.md` section 2's merge-by-id rules), so any single count — rounds included — would describe only part of what changed and imply the rest didn't, which is worse than saying nothing. */
internal fun formatPullSummary(result: PullResult.Success): String {
    return appendSkippedRows("Successfully synced", result.warnings)
}

/** `"$count $noun"`, pluralized (`"$noun" + "s"`) unless [count] is exactly `1` — `0` stays plural ("0 rounds"), matching ordinary English. Every noun this app hands in ("player", "course", "round", "row", "layout") pluralizes with a plain trailing `s`, so there's no need for anything fancier. Package-visible so [courseListDetail] can reuse it for "N layouts" instead of duplicating the pluralization rule. */
internal fun countWord(count: Int, noun: String): String =
    if (count == 1) "1 $noun" else "$count ${noun}s"

private fun appendSkippedRows(base: String, warnings: List<String>): String =
    if (warnings.isEmpty()) base else "$base · ${countWord(warnings.size, "row")} skipped"

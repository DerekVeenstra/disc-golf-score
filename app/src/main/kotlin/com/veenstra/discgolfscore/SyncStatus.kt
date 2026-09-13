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

/** `"Restored 12 rounds"` (or `"Restored 1 round"`), with `"· 2 rows skipped"` appended the same way [formatPushSummary] does — `CLOUD_SAVES.md`'s own illustrative example for this exact message. The round count is the sheet's own [BackupData.rounds] size (what came down and was merged in), not the watch's resulting history size, which could differ from either input. */
internal fun formatPullSummary(result: PullResult.Success): String {
    val base = "Restored ${countWord(result.data.rounds.size, "round")}"
    return appendSkippedRows(base, result.warnings)
}

/** `"$count $noun"`, pluralized (`"$noun" + "s"`) unless [count] is exactly `1` — `0` stays plural ("0 rounds"), matching ordinary English. Every noun this app hands in ("player", "course", "round", "row") pluralizes with a plain trailing `s`, so there's no need for anything fancier. */
private fun countWord(count: Int, noun: String): String =
    if (count == 1) "1 $noun" else "$count ${noun}s"

private fun appendSkippedRows(base: String, warnings: List<String>): String =
    if (warnings.isEmpty()) base else "$base · ${countWord(warnings.size, "row")} skipped"

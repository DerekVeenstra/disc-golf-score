package com.veenstra.discgolfscore

/**
 * Which screen [WearApp] is showing right now. One activity, no nav graph (PLAN.md section 2
 * "Navigation") — this sealed interface, held in `remember { mutableStateOf(...) }` at the
 * `WearApp()` level, is the entire router. Screens that have their own multi-step flow (new-round
 * setup's inline course/player creation, a manager's create/edit sub-modes) keep that as *local*
 * Compose state inside their own composable instead of adding cases here, the same split
 * ultimate-score's `NewGameSetupScreen` makes with its private `SetupMode` (PLAN.md section 13) —
 * only the handful of screens reachable from [Home] live at this level.
 *
 * [Hole] is the screen a round actually plays on, both in progress and (via
 * [FinalScoreboardScreen], PLAN.md section 3 "Final scoreboard") just finished. There is
 * deliberately no dedicated `FinalScoreboard` case — [Hole] routes to the scoreboard screen itself
 * based on `RoundState.finished`, the same way it always has.
 */
sealed interface AppScreen {
    /** `RESUME` (only when a round is active) / `NEW ROUND` / `PLAYERS` / `COURSES` / `PAST ROUNDS` (PLAN.md section 3 "Home"). */
    data object Home : AppScreen

    /** Every finished round, newest first, each re-opening its final scoreboard (PLAN.md section 3 "Past rounds"). */
    data object PastRounds : AppScreen

    /** Course single-select + player multi-select + `START` (PLAN.md section 3 "New round setup"). */
    data object NewRoundSetup : AppScreen

    /**
     * The active round — [HoleScreen] while it's in progress, [FinalScoreboardScreen] once
     * `RoundState.finished` is true. [WearApp] resolves *which* is the correct app-launch screen
     * once (see its own doc comment) so a force-stop mid-round (or mid-scoreboard) reopens here
     * directly rather than at [Home] with no way back in. [Home]'s `RESUME` row is a second,
     * explicit way into the same screen for whenever [Home] is showing with a round still active.
     */
    data object Hole : AppScreen

    /** Reached from Home for housekeeping outside a round (PLAN.md section 3 "Players / Courses managers"). */
    data object ManagePlayers : AppScreen

    data object ManageCourses : AppScreen

    /** The `CLOUD` row's destination (`CLOUD_SAVES.md` section 6 Phase D): status line, `BACK UP NOW`, `RESTORE` (behind [ConfirmScreen]), `CLEAR CONFIG`. */
    data object CloudSync : AppScreen
}

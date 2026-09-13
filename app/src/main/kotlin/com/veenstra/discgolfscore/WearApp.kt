package com.veenstra.discgolfscore

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.wear.compose.material3.MaterialTheme

@Composable
private fun rememberRoundViewModel(): RoundViewModel {
    val appContext = LocalContext.current.applicationContext
    return viewModel(
        factory = viewModelFactory {
            initializer {
                // One DataStoreDiscGolfRepository instance implements every store (PLAN.md section 4).
                val repository = DataStoreDiscGolfRepository(appContext)
                RoundViewModel(
                    playerStore = repository,
                    courseStore = repository,
                    roundStore = repository,
                    historyStore = repository,
                    // Its own small DataStore file, entirely separate from `repository` above --
                    // see SyncConfigStore.kt's own doc for why (CLOUD_SAVES.md section 6 Phase B).
                    syncConfigStore = DataStoreSyncConfigStore(appContext),
                )
            }
        },
    )
}

/**
 * One activity, a sealed [AppScreen] held here in Compose state, no nav graph (PLAN.md section 2
 * "Navigation"). `START` on the setup screen starts a round and navigates to [AppScreen.Hole].
 *
 * **Landing on the right screen after a relaunch.** [screen]'s *initial* value is resolved once,
 * right after [isReady] first flips true, from whether [RoundViewModel.round] is non-null rather
 * than always defaulting to [AppScreen.Home] the way Phase 4 did — otherwise a `force-stop` mid-
 * round would reopen the app at Home with no control anywhere that could reach the round still
 * sitting in [RoundViewModel]/DataStore (PLAN.md section 6 Phase 5's own done-when: "a force-stop
 * mid-round reopens on the same hole with the same numbers"). Phase 5 flagged this as an
 * interpretation of an apparent tension against Phase 6's own "resume-on-launch from Home" scope
 * item and asked Derek to confirm; **this task confirms it explicitly** — keep auto-landing on
 * [AppScreen.Hole] (which itself routes to [FinalScoreboardScreen] instead of [HoleScreen] once
 * `RoundState.finished` is true, so a finished round resumes to the scoreboard, not the hole
 * screen) on a cold launch, and *separately* add the `RESUME` row [HomeScreen] now takes
 * (PLAN.md section 3 "Home": "only when a round is in progress") for the case where [Home] is
 * reached some other way while a round is still active. As of this phase there is no in-app
 * navigation that reaches [Home] while [RoundViewModel.round] is non-null — every path off
 * [HoleScreen]/[FinalScoreboardScreen] either advances the round or, via `DONE`, clears it first —
 * so `RESUME` is currently unreachable through this app's own screens rather than dead code by
 * omission. Built anyway because PLAN.md section 3 specifies it unconditionally, not only for
 * paths this phase happens to add elsewhere. Flagged for Derek in the Phase 6 log.
 */
@Composable
fun WearApp(viewModel: RoundViewModel = rememberRoundViewModel()) {
    MaterialTheme {
        val isReady by viewModel.isReady.collectAsState()

        if (!isReady) {
            // Avoid flashing an empty roster over one that's actually on disk while it's still
            // loading — PLAN.md section 4 "Persistence": "the watch never flashes 'no round' over
            // a live one." Same guard applies to the roster lists for the same reason.
            Box(modifier = Modifier.fillMaxSize().background(Color.Black))
            return@MaterialTheme
        }

        var screen by remember {
            val activeRound = viewModel.round.value
            mutableStateOf<AppScreen>(if (activeRound != null) AppScreen.Hole else AppScreen.Home)
        }
        val players by viewModel.players.collectAsState()
        val courses by viewModel.courses.collectAsState()
        val round by viewModel.round.collectAsState()
        val history by viewModel.history.collectAsState()
        val justSetRecord by viewModel.justSetRecord.collectAsState()
        val syncConfig by viewModel.syncConfig.collectAsState()
        val syncStatus by viewModel.syncStatus.collectAsState()

        when (screen) {
            is AppScreen.Home -> HomeScreen(
                hasActiveRound = round != null,
                onResume = { screen = AppScreen.Hole },
                onNewRound = { screen = AppScreen.NewRoundSetup },
                onPastRounds = { screen = AppScreen.PastRounds },
                onPlayers = { screen = AppScreen.ManagePlayers },
                onCourses = { screen = AppScreen.ManageCourses },
                onCloud = { screen = AppScreen.CloudSync },
            )

            is AppScreen.PastRounds -> PastRoundsScreen(
                history = history,
                onDeleteRound = viewModel::deleteSavedRound,
                onDone = { screen = AppScreen.Home },
            )

            is AppScreen.NewRoundSetup -> NewRoundSetupScreen(
                courses = courses,
                players = players,
                onAddCourse = viewModel::addCourse,
                onRenameCourse = viewModel::renameCourse,
                onAddLayout = viewModel::addLayout,
                onRenameLayout = viewModel::renameLayout,
                onDeleteLayout = viewModel::deleteLayout,
                onSetLayoutPar = viewModel::setLayoutPar,
                onDeleteCourse = viewModel::deleteCourse,
                onAddPlayer = viewModel::addPlayer,
                onRenamePlayer = viewModel::renamePlayer,
                onDeletePlayer = viewModel::deletePlayer,
                onStart = { courseId, layoutId, playerIds ->
                    val course = courses.find { it.id == courseId }
                    val layout = course?.layouts?.find { it.id == layoutId }
                    val chosenPlayers = players.filter { it.id in playerIds }
                    if (course != null && layout != null && chosenPlayers.isNotEmpty()) {
                        viewModel.startRound(course, layout, chosenPlayers)
                        screen = AppScreen.Hole
                    }
                },
            )

            is AppScreen.Hole -> {
                val activeRound = round
                when {
                    activeRound == null -> {
                        // Only reachable if the round vanished out from under this screen (e.g.
                        // DONE was pressed from a stale composition) — not a normal path.
                        LaunchedEffect(Unit) { screen = AppScreen.Home }
                    }
                    activeRound.finished -> FinalScoreboardScreen(
                        round = activeRound,
                        newRecord = justSetRecord,
                        onDone = {
                            viewModel.done()
                            screen = AppScreen.Home
                        },
                    )
                    else -> HoleScreen(
                        round = activeRound,
                        onSetPar = viewModel::setPar,
                        onAdjust = viewModel::adjust,
                        onNextHole = viewModel::nextHole,
                        onPrevHole = viewModel::prevHole,
                        onFinish = viewModel::finishRound,
                    )
                }
            }

            is AppScreen.ManagePlayers -> ManagePlayersScreen(
                players = players,
                onAddPlayer = viewModel::addPlayer,
                onRenamePlayer = viewModel::renamePlayer,
                onDeletePlayer = viewModel::deletePlayer,
                onDone = { screen = AppScreen.Home },
            )

            is AppScreen.ManageCourses -> ManageCoursesScreen(
                courses = courses,
                onAddCourse = viewModel::addCourse,
                onRenameCourse = viewModel::renameCourse,
                onAddLayout = viewModel::addLayout,
                onRenameLayout = viewModel::renameLayout,
                onDeleteLayout = viewModel::deleteLayout,
                onSetLayoutPar = viewModel::setLayoutPar,
                onDeleteCourse = viewModel::deleteCourse,
                onDone = { screen = AppScreen.Home },
            )

            is AppScreen.CloudSync -> CloudSyncScreen(
                syncConfig = syncConfig,
                syncStatus = syncStatus,
                onBackUpNow = viewModel::backUpNow,
                onRestore = viewModel::restore,
                onClearConfig = viewModel::clearSyncConfig,
                onDone = { screen = AppScreen.Home },
            )
        }
    }
}

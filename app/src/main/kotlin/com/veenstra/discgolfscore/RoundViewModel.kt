package com.veenstra.discgolfscore

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Holds the saved roster (players, courses) and the single active round, and is the only place
 * [RoundAction]s get dispatched through [reduce]. Every store is optional/nullable, exactly like
 * ultimate-score's `ScoreViewModel`, so this class is constructible with zero Android framework
 * dependency in plain JVM unit tests (see RoundViewModelPersistenceTest/RoundViewModelRosterTest).
 * Production code supplies a real [DataStoreDiscGolfRepository] for every store parameter.
 *
 * @param idGenerator generates each new [Player]/[Course]/[SavedRound]'s stable id. Defaults to the
 *   wall-clock millisecond it was created (same call ultimate-score's preset id generator made —
 *   good enough for something a person creates by hand at most a few dozen times), but is a
 *   parameter so tests can supply a deterministic sequence instead.
 * @param colorGenerator picks each new [Player]'s [Player.color], given the colors of the players
 *   already in the roster. Defaults to [nextPlayerColor] (the least-used palette color); a
 *   parameter so tests can supply a fixed color instead.
 * @param clock the current time in epoch milliseconds, stamped onto a round as it's saved to
 *   history. A parameter for the same reason as [idGenerator].
 * @param syncConfigStore where the cloud-saves endpoint (`CLOUD_SAVES.md` section 6 Phase B) is
 *   loaded from, `null` in every test that isn't exercising sync itself — same optionality as
 *   every other store parameter here.
 * @param backupClientFactory builds a [BackupClient] bound to one [SyncConfig]'s `url`/`secret`,
 *   called fresh by [backUpNow]/[restore] each time rather than held for the ViewModel's lifetime
 *   (see [BackupClient]'s own doc for why). Defaults to a real [HttpBackupClient]; tests supply a
 *   fake instead, the same seam every other store interface in this class gets.
 */
class RoundViewModel(
    private val playerStore: PlayerStore? = null,
    private val courseStore: CourseStore? = null,
    private val roundStore: RoundStore? = null,
    private val historyStore: RoundHistoryStore? = null,
    private val syncConfigStore: SyncConfigStore? = null,
    private val idGenerator: () -> String = { System.currentTimeMillis().toString() },
    private val colorGenerator: (taken: List<Long>) -> Long = ::nextPlayerColor,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val backupClientFactory: (url: String, secret: String) -> BackupClient = { url, secret -> HttpBackupClient(url, secret) },
) : ViewModel() {

    private val _players = MutableStateFlow<List<Player>>(emptyList())
    val players: StateFlow<List<Player>> = _players.asStateFlow()

    private val _courses = MutableStateFlow<List<Course>>(emptyList())
    val courses: StateFlow<List<Course>> = _courses.asStateFlow()

    private val _round = MutableStateFlow<RoundState?>(null)

    /** The active round, or `null` if none is in progress/finished-but-undismissed. */
    val round: StateFlow<RoundState?> = _round.asStateFlow()

    private val _history = MutableStateFlow<List<SavedRound>>(emptyList())

    /** Every finished round, newest first. */
    val history: StateFlow<List<SavedRound>> = _history.asStateFlow()

    private val _justSetRecord = MutableStateFlow(false)

    /**
     * True from the moment [finishRound] advances the round's course record, until the next
     * [startRound] or [done]. [WearApp] reads this only on the live finish path (not when
     * re-opening a round from [PastRoundsScreen]) to show [FinalScoreboardScreen]'s fanfare exactly
     * once, right when it happens — deliberately in-memory only, not persisted, since losing it to
     * a force-stop right after finishing costs nothing but the celebration.
     */
    val justSetRecord: StateFlow<Boolean> = _justSetRecord.asStateFlow()

    private val _isReady = MutableStateFlow(roundStore == null)

    /**
     * False until the persisted round has finished loading. The UI should render nothing (not an
     * implicit "no round" state) while this is false, so a round in progress on disk is never
     * briefly shown as absent — PLAN.md section 4 "Persistence": "read once at startup before
     * first composition, so the watch never flashes 'no round' over a live one."
     */
    val isReady: StateFlow<Boolean> = _isReady.asStateFlow()

    private val _syncConfig = MutableStateFlow<SyncConfig?>(null)

    /** `null` means not configured — the Cloud screen's whole reason for existing (`CLOUD_SAVES.md` section 6 Phase D). */
    val syncConfig: StateFlow<SyncConfig?> = _syncConfig.asStateFlow()

    private val _syncStatus = MutableStateFlow<SyncStatus>(SyncStatus.Idle)

    /** The result of the most recent [backUpNow]/[restore] — see [SyncStatus]'s own doc. */
    val syncStatus: StateFlow<SyncStatus> = _syncStatus.asStateFlow()

    init {
        playerStore?.let { store ->
            viewModelScope.launch { _players.value = store.loadPlayers() }
        }
        courseStore?.let { store ->
            viewModelScope.launch { _courses.value = store.loadCourses() }
        }
        historyStore?.let { store ->
            viewModelScope.launch { _history.value = store.loadHistory() }
        }
        roundStore?.let { store ->
            viewModelScope.launch {
                _round.value = store.loadRound()
                _isReady.value = true
            }
        }
        syncConfigStore?.let { store ->
            viewModelScope.launch { _syncConfig.value = store.loadConfig() }
        }
    }

    // ---- Roster: players --------------------------------------------------------------------

    /** Sanitizes [name] (PLAN.md section 4 "sanitizeName"); returns `null` and does nothing on a blank result rather than saving an unlabeled row. Its [Player.color] comes from [colorGenerator], given the current roster's colors. */
    fun addPlayer(name: String): Player? {
        val sanitized = sanitizeName(name)
        if (sanitized.isEmpty()) return null
        val player = Player(id = idGenerator(), name = sanitized, color = colorGenerator(_players.value.map { it.color }))
        _players.update { it + player }
        persistPlayers()
        return player
    }

    /** No-op if [id] isn't in the roster, or if [name] sanitizes to blank. */
    fun renamePlayer(id: String, name: String) {
        val sanitized = sanitizeName(name)
        if (sanitized.isEmpty()) return
        _players.update { list -> list.map { if (it.id == id) it.copy(name = sanitized) else it } }
        persistPlayers()
    }

    fun deletePlayer(id: String) {
        _players.update { list -> list.filterNot { it.id == id } }
        persistPlayers()
    }

    private fun persistPlayers() {
        playerStore?.let { store ->
            val snapshot = _players.value
            viewModelScope.launch { store.savePlayers(snapshot) }
        }
    }

    // ---- Roster: courses ---------------------------------------------------------------------

    /**
     * Creates a course with one auto-named layout (PLAN.md section 2 "Course creation" — the short
     * path that skips asking for a first layout name). That layout has [holeCount] holes, all pars
     * unlearned ("pars are learned by playing"). Returns `null` and does nothing if [name]
     * sanitizes to blank or [holeCount] isn't positive.
     */
    fun addCourse(name: String, holeCount: Int): Course? {
        val sanitized = sanitizeName(name)
        if (sanitized.isEmpty() || holeCount <= 0) return null
        val layout = Layout(id = idGenerator(), name = defaultLayoutName(holeCount), holeCount = holeCount, pars = List(holeCount) { 0 })
        val course = Course(id = idGenerator(), name = sanitized, layouts = listOf(layout))
        _courses.update { it + course }
        persistCourses()
        return course
    }

    /** No-op if [id] isn't in the saved list, or if [name] sanitizes to blank. Leaves every one of its layouts alone. */
    fun renameCourse(id: String, name: String) {
        val sanitized = sanitizeName(name)
        if (sanitized.isEmpty()) return
        _courses.update { list -> list.map { if (it.id == id) it.copy(name = sanitized) else it } }
        persistCourses()
    }

    fun deleteCourse(id: String) {
        _courses.update { list -> list.filterNot { it.id == id } }
        persistCourses()
    }

    /**
     * Adds a new [Layout] to [courseId] — "+ New layout…" (PLAN.md section 2 "Layouts"), the way
     * to give a course a second tee/pin configuration, or a different hole count, after it already
     * exists. All pars start unlearned, exactly like [addCourse]'s auto-named first layout. Returns
     * `null` and does nothing if [name] sanitizes to blank, [holeCount] isn't positive, or
     * [courseId] isn't a saved course.
     */
    fun addLayout(courseId: String, name: String, holeCount: Int): Layout? {
        val sanitized = sanitizeName(name)
        if (sanitized.isEmpty() || holeCount <= 0) return null
        if (_courses.value.none { it.id == courseId }) return null
        val layout = Layout(id = idGenerator(), name = sanitized, holeCount = holeCount, pars = List(holeCount) { 0 })
        _courses.update { list ->
            list.map { course -> if (course.id == courseId) course.copy(layouts = course.layouts + layout) else course }
        }
        persistCourses()
        return layout
    }

    /** No-op if [courseId]/[layoutId] don't resolve, or if [name] sanitizes to blank. Leaves the layout's pars/record alone. */
    fun renameLayout(courseId: String, layoutId: String, name: String) {
        val sanitized = sanitizeName(name)
        if (sanitized.isEmpty()) return
        _courses.update { list ->
            list.map { course ->
                if (course.id != courseId) {
                    course
                } else {
                    course.copy(layouts = course.layouts.map { if (it.id == layoutId) it.copy(name = sanitized) else it })
                }
            }
        }
        persistCourses()
    }

    /**
     * Removes [layoutId] from [courseId]'s layout list — a no-op if that would leave the course
     * with zero layouts (PLAN.md section 2 "Deleting a layout"): blocking is simpler and safer
     * than deleting the whole course along with its last layout, and it can never destroy a
     * course's saved rounds out from under someone who only meant to remove one layout of several.
     */
    fun deleteLayout(courseId: String, layoutId: String) {
        _courses.update { list ->
            list.map { course ->
                if (course.id == courseId && course.layouts.size > 1) {
                    course.copy(layouts = course.layouts.filterNot { it.id == layoutId })
                } else {
                    course
                }
            }
        }
        persistCourses()
    }

    /**
     * Directly overwrites one hole's learned par on [layoutId] (PLAN.md section 3 "Course editor"
     * — the only way to fix a wrong learned par outside of playing a round, per section 2's "learn
     * once" policy, now scoped to the layout it was learned on). Clamped to [MIN_PAR]..[MAX_PAR],
     * same range [RoundAction.SetPar] enforces mid-round. No-op if [courseId]/[layoutId] don't
     * resolve or [holeIndex] is out of bounds for that layout's hole count.
     */
    fun setLayoutPar(courseId: String, layoutId: String, holeIndex: Int, newPar: Int) {
        val clamped = newPar.coerceIn(MIN_PAR, MAX_PAR)
        _courses.update { list ->
            list.map { course ->
                if (course.id != courseId) {
                    course
                } else {
                    course.copy(
                        layouts = course.layouts.map { layout ->
                            if (layout.id == layoutId && holeIndex in layout.pars.indices) {
                                layout.copy(pars = layout.pars.toMutableList().also { it[holeIndex] = clamped })
                            } else {
                                layout
                            }
                        },
                    )
                }
            }
        }
        persistCourses()
    }

    private fun persistCourses() {
        courseStore?.let { store ->
            val snapshot = _courses.value
            viewModelScope.launch { store.saveCourses(snapshot) }
        }
    }

    /**
     * Manually corrects [layoutId]'s record holder name(s) — [LayoutEditorScreen]'s free-text
     * entry, comma-separated for a tie — the same manual-correction role [setLayoutPar] plays for
     * a wrong *learned* par (PLAN.md section 2 "Course record"/"Layouts"). Each name is sanitized
     * the same as a player/course/layout name; blanks and duplicates are dropped. An empty result
     * **clears** the record entirely (both holders and to-par) rather than leaving a nameless score
     * behind — typed text that isn't blank is validated by the caller before this is invoked, so an
     * empty result here specifically means the person cleared the field.
     *
     * A newly-set holder list that had no prior to-par seeds one at even par (`0`) — the same
     * "reasonable starting guess, then nudge it" convention as an unlearned hole's par — for
     * [setLayoutRecordToPar] to adjust from. No-op if [courseId]/[layoutId] don't resolve.
     */
    fun setLayoutRecordHolders(courseId: String, layoutId: String, rawNames: String) {
        val names = rawNames.split(",").map(::sanitizeName).filter { it.isNotEmpty() }.distinct()
        _courses.update { list ->
            list.map { course ->
                if (course.id != courseId) {
                    course
                } else {
                    course.copy(
                        layouts = course.layouts.map { layout ->
                            when {
                                layout.id != layoutId -> layout
                                names.isEmpty() -> layout.copy(recordHolderNames = emptyList(), recordToPar = null)
                                else -> layout.copy(recordHolderNames = names, recordToPar = layout.recordToPar ?: 0)
                            }
                        },
                    )
                }
            }
        }
        persistCourses()
    }

    /**
     * Manually corrects [layoutId]'s record to-par, clamped to a plausible whole-round range —
     * each hole can swing from [MIN_STROKES] on a [MAX_PAR] hole to [MAX_STROKES] on a [MIN_PAR]
     * one, so `holeCount * (`[MIN_STROKES]` - `[MAX_PAR]`)..holeCount * (`[MAX_STROKES]` - `[MIN_PAR]`)`
     * scales that per-hole swing up to a full card, the same way [setLayoutPar]'s stroke clamp
     * scales up. No-op if [courseId]/[layoutId] don't resolve, or if the layout has no record
     * holders yet — there's nothing sensible to attach a score to (set the holders first, via
     * [setLayoutRecordHolders]).
     */
    fun setLayoutRecordToPar(courseId: String, layoutId: String, toPar: Int) {
        _courses.update { list ->
            list.map { course ->
                if (course.id != courseId) {
                    course
                } else {
                    course.copy(
                        layouts = course.layouts.map { layout ->
                            if (layout.id == layoutId && layout.recordHolderNames.isNotEmpty()) {
                                val min = layout.holeCount * (MIN_STROKES - MAX_PAR)
                                val max = layout.holeCount * (MAX_STROKES - MIN_PAR)
                                layout.copy(recordToPar = toPar.coerceIn(min, max))
                            } else {
                                layout
                            }
                        },
                    )
                }
            }
        }
        persistCourses()
    }

    // ---- The active round ---------------------------------------------------------------------

    /** Starts a fresh round on [course], played on [layout], with [players] — see [newRound]. Persists immediately, same as every other round mutation. */
    fun startRound(course: Course, layout: Layout, players: List<Player>) {
        _round.value = newRound(course, layout, players)
        _justSetRecord.value = false
        persistRoundAndWriteBack()
    }

    fun setPar(par: Int) = dispatch(RoundAction.SetPar(par))
    fun adjust(playerId: String, delta: Int) = dispatch(RoundAction.Adjust(playerId, delta))
    fun nextHole() = dispatch(RoundAction.NextHole)
    fun prevHole() = dispatch(RoundAction.PrevHole)

    /**
     * Both confirmed-finish paths (PLAN.md section 3) end up here after the UI's own confirmation.
     * The round is saved to [history], and the course record write-back applied, at the moment it
     * *becomes* finished — only on that transition, so calling this again on an already-finished
     * round (including one restored from disk) can't save it, or advance the record, a second time.
     */
    fun finishRound() {
        val wasFinished = _round.value?.finished ?: return
        dispatch(RoundAction.Finish)
        val round = _round.value ?: return
        if (!wasFinished && round.finished) {
            saveToHistory(round)
            _justSetRecord.value = applyRecordWriteBack(round)
        }
    }

    /** No-op if there is no active round — every [RoundAction] needs one to apply to. */
    private fun dispatch(action: RoundAction) {
        val current = _round.value ?: return
        _round.value = reduce(current, action)
        persistRoundAndWriteBack()
    }

    /**
     * Persists the round on every change (PLAN.md section 4 "Persistence" — "written on every
     * action") and applies the par write-back (this task's Phase 3 scope: "the ViewModel calls
     * parsToLearn() and updates the course"). Safe to call unconditionally after every mutation:
     * [RoundState.parsToLearn] is itself bounded to holes reached so far and holes the course
     * didn't already know at round start, so recomputing and reapplying it is idempotent.
     */
    private fun persistRoundAndWriteBack() {
        val round = _round.value ?: return
        roundStore?.let { store ->
            viewModelScope.launch { store.saveRound(round) }
        }
        applyParWriteBack(round)
    }

    /**
     * No-ops if [RoundState.courseId]/[RoundState.layoutId] no longer resolve to a saved course
     * and layout — PLAN.md section 2: "Par write-back... no-ops if that course [or layout] has
     * since been deleted."
     */
    private fun applyParWriteBack(round: RoundState) {
        val courseId = round.courseId ?: return
        val layoutId = round.layoutId ?: return
        val toLearn = round.parsToLearn()
        if (toLearn.isEmpty()) return

        val current = _courses.value
        val course = current.find { it.id == courseId } ?: return
        val layout = course.layouts.find { it.id == layoutId } ?: return

        val updatedPars = layout.pars.toMutableList()
        for ((holeNumber, par) in toLearn) {
            val index = holeNumber - 1
            if (index in updatedPars.indices) updatedPars[index] = par
        }
        if (updatedPars == layout.pars) return // nothing actually changed — skip the write

        val updatedLayout = layout.copy(pars = updatedPars)
        val updatedCourse = course.copy(layouts = course.layouts.map { if (it.id == layoutId) updatedLayout else it })
        _courses.value = current.map { if (it.id == courseId) updatedCourse else it }
        persistCourses()
    }

    /**
     * The automatic half of "Layout record" (PLAN.md section 2): applies [recordAfterRound] for
     * [round]'s layout, if any, and returns whether it actually changed anything — [finishRound]
     * uses that to drive [justSetRecord]. No-ops (and returns `false`) if [RoundState.courseId]/
     * [RoundState.layoutId] no longer resolve, same as [applyParWriteBack].
     */
    private fun applyRecordWriteBack(round: RoundState): Boolean {
        val courseId = round.courseId ?: return false
        val layoutId = round.layoutId ?: return false
        val course = _courses.value.find { it.id == courseId } ?: return false
        val layout = course.layouts.find { it.id == layoutId } ?: return false
        val updatedLayout = recordAfterRound(layout, round) ?: return false
        val updatedCourse = course.copy(layouts = course.layouts.map { if (it.id == layoutId) updatedLayout else it })
        _courses.value = _courses.value.map { if (it.id == courseId) updatedCourse else it }
        persistCourses()
        return true
    }

    /**
     * Clears the round from storage — the only thing that can (PLAN.md section 2 "Finished round
     * persistence"). Deliberately not a [RoundAction]: it's a repository/ViewModel concern per
     * PLAN.md section 4 "A finished round is frozen... What clears it is DONE". Only the *active*
     * round is cleared — its copy in [history], saved when it finished, stays.
     */
    fun done() {
        _round.value = null
        _justSetRecord.value = false
        roundStore?.let { store ->
            viewModelScope.launch { store.clearRound() }
        }
    }

    // ---- Past rounds ----------------------------------------------------------------------------

    fun deleteSavedRound(id: String) {
        _history.update { list -> list.filterNot { it.id == id } }
        persistHistory()
    }

    private fun saveToHistory(round: RoundState) {
        val saved = SavedRound(id = idGenerator(), finishedAt = clock(), round = round)
        _history.update { listOf(saved) + it }
        persistHistory()
    }

    private fun persistHistory() {
        historyStore?.let { store ->
            val snapshot = _history.value
            viewModelScope.launch { store.saveHistory(snapshot) }
        }
    }

    // ---- Cloud saves (CLOUD_SAVES.md) ---------------------------------------------------------
    //
    // Both directions follow the same three-step shape: resolve [_syncConfig] (bailing out to
    // [SyncStatus.Failure] with no [BackupClient] built at all if nothing is configured — the
    // "not-configured" path `CLOUD_SAVES.md` section 6 Phase B calls out by name), build a fresh
    // client via [backupClientFactory], then interpret whatever it returns. Neither method touches
    // [RoundState] — cloud saves never syncs the active round (`CLOUD_SAVES.md` section 2 "Active
    // round is not backed up").

    /**
     * Pushes the current players/courses/history to the configured sheet — Home's `BACK UP NOW`.
     * Upsert only: nothing already in the sheet is ever removed by a push, only added or updated
     * (`CLOUD_SAVES.md` section 2 "Push is upsert, never delete") — which is simply what *not*
     * sending a delete instruction of any kind already guarantees, since the wire format
     * (`CLOUD_SAVES.md` section 3) has no such instruction to send.
     */
    fun backUpNow() {
        val config = _syncConfig.value
        if (config == null) {
            _syncStatus.value = SyncStatus.Failure(NOT_CONFIGURED_MESSAGE)
            return
        }
        _syncStatus.value = SyncStatus.InProgress
        viewModelScope.launch {
            val client = backupClientFactory(config.url, config.secret)
            val data = BackupData(players = _players.value, courses = _courses.value, rounds = _history.value)
            when (val result = client.push(data)) {
                is PushResult.Success -> {
                    _syncStatus.value = SyncStatus.Success(formatPushSummary(result))
                    recordSyncTime()
                }
                is PushResult.Failure -> _syncStatus.value = SyncStatus.Failure(result.message)
            }
        }
    }

    /**
     * Pulls the sheet's current state and merges it into the watch's own via [mergeBackup] — Home's
     * `RESTORE`, behind its own confirmation screen. Merge, never replace: an id present on both
     * sides takes the sheet's version, an id only on the watch is left alone, an id only in the
     * sheet is added (`CLOUD_SAVES.md` section 2 "Restore merges by id, sheet wins") — so a restore
     * can only ever add to or correct what's on the watch, never silently discard something played
     * since the last push.
     */
    fun restore() {
        val config = _syncConfig.value
        if (config == null) {
            _syncStatus.value = SyncStatus.Failure(NOT_CONFIGURED_MESSAGE)
            return
        }
        _syncStatus.value = SyncStatus.InProgress
        viewModelScope.launch {
            val client = backupClientFactory(config.url, config.secret)
            when (val result = client.pull()) {
                is PullResult.Success -> {
                    val local = BackupData(players = _players.value, courses = _courses.value, rounds = _history.value)
                    val merged = mergeBackup(local, result.data)
                    _players.value = merged.players
                    _courses.value = merged.courses
                    _history.value = merged.rounds
                    persistPlayers()
                    persistCourses()
                    persistHistory()
                    _syncStatus.value = SyncStatus.Success(formatPullSummary(result))
                    recordSyncTime()
                }
                is PullResult.Failure -> _syncStatus.value = SyncStatus.Failure(result.message)
            }
        }
    }

    /** Stamps [SyncConfig.lastSyncAt] after a push or pull that actually succeeded, and reloads [_syncConfig] so the Cloud screen's status line updates without waiting for a full re-launch. No-op with no [syncConfigStore] (every test that isn't exercising sync itself). */
    private fun recordSyncTime() {
        val store = syncConfigStore ?: return
        val now = clock()
        viewModelScope.launch {
            store.recordSyncAt(now)
            _syncConfig.value = store.loadConfig()
        }
    }

    /** The Cloud screen's `CLEAR CONFIG` (`CLOUD_SAVES.md` section 6 Phase D) — the only UI-driven way to remove a configured endpoint; setting one is adb-only (see [SyncConfigReceiver]). */
    fun clearSyncConfig() {
        _syncConfig.value = null
        _syncStatus.value = SyncStatus.Idle
        syncConfigStore?.let { store ->
            viewModelScope.launch { store.clearConfig() }
        }
    }
}

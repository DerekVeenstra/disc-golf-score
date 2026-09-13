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
 */
class RoundViewModel(
    private val playerStore: PlayerStore? = null,
    private val courseStore: CourseStore? = null,
    private val roundStore: RoundStore? = null,
    private val historyStore: RoundHistoryStore? = null,
    private val idGenerator: () -> String = { System.currentTimeMillis().toString() },
    private val colorGenerator: (taken: List<Long>) -> Long = ::nextPlayerColor,
    private val clock: () -> Long = { System.currentTimeMillis() },
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
     * Creates a course with [holeCount] holes, all pars unlearned (PLAN.md section 2 "Course
     * creation" — "pars are learned by playing"). Returns `null` and does nothing if [name]
     * sanitizes to blank or [holeCount] isn't positive.
     */
    fun addCourse(name: String, holeCount: Int): Course? {
        val sanitized = sanitizeName(name)
        if (sanitized.isEmpty() || holeCount <= 0) return null
        val course = Course(id = idGenerator(), name = sanitized, holeCount = holeCount, pars = List(holeCount) { 0 })
        _courses.update { it + course }
        persistCourses()
        return course
    }

    /** No-op if [id] isn't in the saved list, or if [name] sanitizes to blank. */
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
     * Directly overwrites one hole's learned par (PLAN.md section 3 "Course editor" — the only
     * way to fix a wrong learned par outside of playing a round, per section 2's "learn once"
     * policy). Clamped to [MIN_PAR]..[MAX_PAR], same range [RoundAction.SetPar] enforces mid-round.
     * No-op if [id] isn't a saved course or [holeIndex] is out of bounds for its hole count.
     */
    fun setCoursePar(id: String, holeIndex: Int, newPar: Int) {
        val clamped = newPar.coerceIn(MIN_PAR, MAX_PAR)
        _courses.update { list ->
            list.map { course ->
                if (course.id == id && holeIndex in course.pars.indices) {
                    course.copy(pars = course.pars.toMutableList().also { it[holeIndex] = clamped })
                } else {
                    course
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
     * Manually corrects this course's record holder name(s) — [CourseEditorScreen]'s free-text
     * entry, comma-separated for a tie — the same manual-correction role [setCoursePar] plays for
     * a wrong *learned* par (PLAN.md section 2 "Course record"). Each name is sanitized the same
     * as a player/course name; blanks and duplicates are dropped. An empty result **clears** the
     * record entirely (both holders and to-par) rather than leaving a nameless score behind —
     * typed text that isn't blank is validated by the caller before this is invoked, so an empty
     * result here specifically means the person cleared the field.
     *
     * A newly-set holder list that had no prior to-par seeds one at even par (`0`) — the same
     * "reasonable starting guess, then nudge it" convention as an unlearned hole's par — for
     * [setCourseRecordToPar] to adjust from. No-op if [id] isn't a saved course.
     */
    fun setCourseRecordHolders(id: String, rawNames: String) {
        val names = rawNames.split(",").map(::sanitizeName).filter { it.isNotEmpty() }.distinct()
        _courses.update { list ->
            list.map { course ->
                if (course.id != id) {
                    course
                } else if (names.isEmpty()) {
                    course.copy(recordHolderNames = emptyList(), recordToPar = null)
                } else {
                    course.copy(
                        recordHolderNames = names,
                        recordToPar = course.recordToPar ?: 0,
                    )
                }
            }
        }
        persistCourses()
    }

    /**
     * Manually corrects this course's record to-par, clamped to a plausible whole-round range —
     * each hole can swing from [MIN_STROKES] on a [MAX_PAR] hole to [MAX_STROKES] on a [MIN_PAR]
     * one, so `holeCount * (`[MIN_STROKES]` - `[MAX_PAR]`)..holeCount * (`[MAX_STROKES]` - `[MIN_PAR]`)`
     * scales that per-hole swing up to a full card, the same way [setCoursePar]'s stroke clamp
     * scales up. No-op if [id] isn't a saved course, or if it has no record holders yet — there's
     * nothing sensible to attach a score to (set the holders first, via [setCourseRecordHolders]).
     */
    fun setCourseRecordToPar(id: String, toPar: Int) {
        _courses.update { list ->
            list.map { course ->
                if (course.id == id && course.recordHolderNames.isNotEmpty()) {
                    val min = course.holeCount * (MIN_STROKES - MAX_PAR)
                    val max = course.holeCount * (MAX_STROKES - MIN_PAR)
                    course.copy(recordToPar = toPar.coerceIn(min, max))
                } else {
                    course
                }
            }
        }
        persistCourses()
    }

    // ---- The active round ---------------------------------------------------------------------

    /** Starts a fresh round on [course] with [players] — see [newRound]. Persists immediately, same as every other round mutation. */
    fun startRound(course: Course, players: List<Player>) {
        _round.value = newRound(course, players)
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

    /** No-ops if [RoundState.courseId] no longer names a course in [courses] — PLAN.md section 4: "Par write-back... no-ops if that course has since been deleted." */
    private fun applyParWriteBack(round: RoundState) {
        val courseId = round.courseId ?: return
        val toLearn = round.parsToLearn()
        if (toLearn.isEmpty()) return

        val current = _courses.value
        val course = current.find { it.id == courseId } ?: return

        val updatedPars = course.pars.toMutableList()
        for ((holeNumber, par) in toLearn) {
            val index = holeNumber - 1
            if (index in updatedPars.indices) updatedPars[index] = par
        }
        if (updatedPars == course.pars) return // nothing actually changed — skip the write

        val updatedCourse = course.copy(pars = updatedPars)
        _courses.value = current.map { if (it.id == courseId) updatedCourse else it }
        persistCourses()
    }

    /**
     * The automatic half of "Course record" (PLAN.md section 2): applies [recordAfterRound] for
     * [round]'s course, if any, and returns whether it actually changed anything — [finishRound]
     * uses that to drive [justSetRecord]. No-ops (and returns `false`) if [RoundState.courseId] no
     * longer names a course in [courses], same as [applyParWriteBack].
     */
    private fun applyRecordWriteBack(round: RoundState): Boolean {
        val courseId = round.courseId ?: return false
        val course = _courses.value.find { it.id == courseId } ?: return false
        val updated = recordAfterRound(course, round) ?: return false
        _courses.value = _courses.value.map { if (it.id == courseId) updated else it }
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
}

package com.veenstra.discgolfscore

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first

/**
 * Where [RoundViewModel] loads and saves the saved player roster. An interface (rather than a
 * concrete DataStore class) so the ViewModel stays constructible with no Android framework
 * dependency in plain JVM unit tests — see RoundViewModelRosterTest's in-memory fake, the same
 * shape as ultimate-score's `ScoreHistoryStore`/`TeamPresetStore` split (PLAN.md section 4).
 */
interface PlayerStore {
    suspend fun loadPlayers(): List<Player>
    suspend fun savePlayers(players: List<Player>)
}

/** Where the saved course list is loaded from and persisted to. Independent of [PlayerStore] for the same reason ultimate-score split its two preset lists: a player add/rename/delete has no reason to re-encode and rewrite the course list, or vice versa. */
interface CourseStore {
    suspend fun loadCourses(): List<Course>
    suspend fun saveCourses(courses: List<Course>)
}

/**
 * Where the single active round (in progress or just finished) is loaded from and persisted to.
 * [saveRound] is called on every [RoundAction] (PLAN.md section 4 "Persistence" — "written on
 * every change"). [clearRound] is the *only* way a round leaves storage — it's what `DONE` calls
 * (PLAN.md section 2 "Finished round persistence"), never something a [RoundAction] does, since a
 * finished round must survive an accidental right-swipe until the player explicitly presses DONE.
 */
interface RoundStore {
    suspend fun loadRound(): RoundState?
    suspend fun saveRound(round: RoundState)
    suspend fun clearRound()
}

/**
 * Where finished rounds are kept once they're done (Home's `PAST ROUNDS`), newest first. Separate
 * from [RoundStore] for the same reason players and courses are split: the active round is
 * rewritten on every tap during play, while history only changes when a round finishes or one is
 * deleted.
 */
interface RoundHistoryStore {
    suspend fun loadHistory(): List<SavedRound>
    suspend fun saveHistory(history: List<SavedRound>)
}

// ---------------------------------------------------------------------------------------------
// Pure codecs. Top-level and free of Android imports specifically so the serialization round-trip
// — including every malformed-input case — is covered by plain JVM unit tests (see
// DiscGolfPersistenceCodecTest). Per PLAN.md section 4 "Persistence": U+001F (Unit Separator)
// between the fields of one record, U+001E (Record Separator) between records, and plain commas
// only inside digits-only fields (par lists, stroke lists, and the id:strokes pairs below — every
// player/course id in this app is generated from a wall-clock millisecond count, so ids are
// digits-only too, same as ultimate-score's preset ids). No escaping scheme: [sanitizeName]
// guarantees a name can never contain a control character in the first place, so a name
// containing a raw separator character can only happen if that guarantee is bypassed — the codecs
// below still don't crash on it (they just split on whatever's actually there), they simply can't
// promise which field the pieces land in, and that's on the caller, not the codec.
// ---------------------------------------------------------------------------------------------

private const val FIELD_SEP = "" // Unit Separator — between fields of one record
private const val RECORD_SEP = "" // Record Separator — between records

// ---- Players --------------------------------------------------------------------------------

/** One player per record (`id`[US]`name`[US]`color`), records joined by RS. [Player.color] is a packed ARGB `Long`, always non-negative, so its decimal string is digits-only per PLAN.md section 4. Reused verbatim for the round's snapshot player list (`RoundState.players`) since it's the exact same [Player] shape. */
internal fun encodePlayers(players: List<Player>): String =
    players.joinToString(RECORD_SEP) { p -> listOf(p.id, p.name, p.color.toString()).joinToString(FIELD_SEP) }

internal fun decodePlayers(raw: String?): List<Player> {
    if (raw.isNullOrBlank()) return emptyList()
    return raw.split(RECORD_SEP).mapNotNull(::decodePlayer)
}

/**
 * Malformed entries (wrong field count, blank id/name, a non-numeric color) are dropped rather
 * than crashing. A 2-field entry (`id`[US]`name`, no color) is accepted, not malformed — it's a
 * player saved before [Player] had a color field, and decodes to [DEFAULT_PLAYER_COLOR], the same
 * value a bare `Player(id, name)` gets.
 */
private fun decodePlayer(entry: String): Player? {
    val parts = entry.split(FIELD_SEP)
    if (parts.size != 2 && parts.size != 3) return null
    val id = parts[0]
    val name = parts[1]
    if (id.isBlank() || name.isBlank()) return null
    val color = if (parts.size == 3) parts[2].toLongOrNull() ?: return null else DEFAULT_PLAYER_COLOR
    return Player(id, name, color)
}

// ---- Courses ---------------------------------------------------------------------------------

/** One course per record (`id`[US]`name`[US]`holeCount`[US]`pars`), records joined by RS. [Course.pars] is digits-only, so it's plain-comma-joined per PLAN.md section 4. */
internal fun encodeCourses(courses: List<Course>): String =
    courses.joinToString(RECORD_SEP) { c ->
        listOf(c.id, c.name, c.holeCount.toString(), c.pars.joinToString(",")).joinToString(FIELD_SEP)
    }

internal fun decodeCourses(raw: String?): List<Course> {
    if (raw.isNullOrBlank()) return emptyList()
    return raw.split(RECORD_SEP).mapNotNull(::decodeCourse)
}

/**
 * Malformed entries are dropped rather than crashing: wrong field count, blank id/name, a
 * non-numeric or non-positive hole count, a non-numeric par, or — the one Phase 3 called out by
 * name — a par list whose length disagrees with the hole count.
 */
private fun decodeCourse(entry: String): Course? {
    val parts = entry.split(FIELD_SEP)
    if (parts.size != 4) return null
    val id = parts[0]
    val name = parts[1]
    if (id.isBlank() || name.isBlank()) return null
    val holeCount = parts[2].toIntOrNull() ?: return null
    if (holeCount <= 0) return null
    val parsField = parts[3]
    val pars = if (parsField.isEmpty()) emptyList() else parsField.split(",").map { it.toIntOrNull() }
    if (pars.any { it == null }) return null
    val parsInts = pars.filterNotNull()
    if (parsInts.size != holeCount) return null
    return Course(id, name, holeCount, parsInts)
}

// ---- Active round ------------------------------------------------------------------------------
//
// The round is split across three preference keys — meta, players, holes — rather than crammed
// into one nested string. It has to be: `playersBlob`/`holesBlob` each need US to separate their
// own records' fields *and* RS to separate the records themselves, which already exhausts both
// control characters PLAN.md section 4 sanctions one level down. A top-level string joining
// `[meta, playersBlob, holesBlob]` with either separator would collide with that same separator
// reused inside the blobs it's trying to hold apart, corrupting the split. Three flat preference
// keys sidestep the problem entirely — the same "no reason to force one key" call ultimate-score
// made for its several `game_state` keys — rather than inventing a third separator PLAN.md never
// asked for.
// ---------------------------------------------------------------------------------------------

/** The round's scalar fields, one record: `courseId`[US]`courseName`[US]`currentHole`[US]`finished`. `courseId` empty means `null` (a blank id never comes from [Course]'s own decode, so this is unambiguous). */
internal fun encodeRoundMeta(round: RoundState): String =
    listOf(
        round.courseId ?: "",
        round.courseName,
        round.currentHole.toString(),
        if (round.finished) "1" else "0",
    ).joinToString(FIELD_SEP)

private data class RoundMeta(val courseId: String?, val courseName: String, val currentHole: Int, val finished: Boolean)

/** Malformed meta (wrong field count, blank course name, non-numeric current hole, unrecognized finished flag) decodes to `null`. */
private fun decodeRoundMeta(raw: String?): RoundMeta? {
    if (raw.isNullOrBlank()) return null
    val parts = raw.split(FIELD_SEP)
    if (parts.size != 4) return null
    val courseId = parts[0].ifEmpty { null }
    val courseName = parts[1]
    if (courseName.isBlank()) return null
    val currentHole = parts[2].toIntOrNull() ?: return null
    val finished = when (parts[3]) {
        "1" -> true
        "0" -> false
        else -> return null
    }
    return RoundMeta(courseId, courseName, currentHole, finished)
}

/**
 * Reassembles the active round from its three stored pieces. `null` (no round) if any piece is
 * malformed, or if [currentHole] falls outside `1..holes.size` — a corrupt piece invalidates the
 * whole round rather than being dropped in isolation; unlike the player/course lists, a round
 * isn't a list of independent records, it's one card, and a card missing a hole in the middle
 * can't be trusted to mean anything.
 */
internal fun decodeRound(metaRaw: String?, playersRaw: String?, holesRaw: String?): RoundState? {
    val meta = decodeRoundMeta(metaRaw) ?: return null
    val holes = decodeHoles(holesRaw) ?: return null
    if (meta.currentHole < 1 || meta.currentHole > holes.size) return null
    val players = decodePlayers(playersRaw)
    return RoundState(
        courseId = meta.courseId,
        courseName = meta.courseName,
        players = players,
        holes = holes,
        currentHole = meta.currentHole,
        finished = meta.finished,
    )
}

/** Encodes all three pieces of [round] at once, in the same order [decodeRound] expects them back. */
internal fun encodeRound(round: RoundState): Triple<String, String, String> =
    Triple(encodeRoundMeta(round), encodePlayers(round.players), encodeHoles(round.holes))

/** One hole per record (`par`[US]`wasLearnedAtStart`[US]`strokes`[US]`touched`), records joined by RS. */
internal fun encodeHoles(holes: List<HoleScore>): String =
    holes.joinToString(RECORD_SEP) { h ->
        listOf(
            h.par.toString(),
            if (h.wasLearnedAtStart) "1" else "0",
            h.strokes.entries.joinToString(",") { (id, strokes) -> "$id:$strokes" },
            h.touched.joinToString(","),
        ).joinToString(FIELD_SEP)
    }

/** `null` (rather than an empty list) means "unparsable" — a round must have at least one hole, and every hole present must have decoded cleanly. */
private fun decodeHoles(raw: String?): List<HoleScore>? {
    if (raw.isNullOrEmpty()) return null
    val entries = raw.split(RECORD_SEP)
    val holes = ArrayList<HoleScore>(entries.size)
    for (entry in entries) {
        holes.add(decodeHole(entry) ?: return null)
    }
    return holes
}

private fun decodeHole(entry: String): HoleScore? {
    val parts = entry.split(FIELD_SEP)
    if (parts.size != 4) return null
    val par = parts[0].toIntOrNull() ?: return null
    val wasLearnedAtStart = when (parts[1]) {
        "1" -> true
        "0" -> false
        else -> return null
    }
    val strokes = decodeStrokes(parts[2]) ?: return null
    val touched = if (parts[3].isEmpty()) emptySet() else parts[3].split(",").toSet()
    return HoleScore(par = par, wasLearnedAtStart = wasLearnedAtStart, strokes = strokes, touched = touched)
}

/** `playerId:strokes` pairs, comma-joined — digits-only on both sides (PLAN.md section 4), so plain commas/colons are safe with no escaping. */
private fun decodeStrokes(raw: String): Map<String, Int>? {
    if (raw.isEmpty()) return emptyMap()
    val map = LinkedHashMap<String, Int>()
    for (entry in raw.split(",")) {
        val parts = entry.split(":")
        if (parts.size != 2) return null
        val id = parts[0]
        if (id.isBlank()) return null
        val strokes = parts[1].toIntOrNull() ?: return null
        map[id] = strokes
    }
    return map
}

// ---- Round history -----------------------------------------------------------------------------
//
// A list of rounds can't be one string for the same reason one round can't be (see "Active round"
// above): each round's pieces already use both separators. So every saved round is stored as the
// same three pieces the active round uses — encoded by the exact same codecs — under keys prefixed
// with that round's id, plus one index key listing which rounds exist, in order.
// ---------------------------------------------------------------------------------------------

private const val HISTORY_INDEX_KEY = "index"

private fun historyKey(id: String, piece: String): String = "round_${id}_$piece"

/**
 * Every preference key and value needed to store [history]: the index (one `id`[US]`finishedAt`
 * record per round, RS-joined, in list order) plus each round's meta/players/holes pieces.
 */
internal fun encodeHistory(history: List<SavedRound>): Map<String, String> {
    val stored = LinkedHashMap<String, String>()
    stored[HISTORY_INDEX_KEY] =
        history.joinToString(RECORD_SEP) { listOf(it.id, it.finishedAt.toString()).joinToString(FIELD_SEP) }
    for (saved in history) {
        val (meta, players, holes) = encodeRound(saved.round)
        stored[historyKey(saved.id, "meta")] = meta
        stored[historyKey(saved.id, "players")] = players
        stored[historyKey(saved.id, "holes")] = holes
    }
    return stored
}

/**
 * Reads history back through [read], a lookup by preference key name. Unlike a single round, history
 * *is* a list of independent records, so a malformed index record, or a round whose pieces don't
 * decode (per [decodeRound]), is dropped on its own and every other round still loads.
 */
internal fun decodeHistory(read: (key: String) -> String?): List<SavedRound> {
    val index = read(HISTORY_INDEX_KEY)
    if (index.isNullOrBlank()) return emptyList()
    return index.split(RECORD_SEP).mapNotNull { entry ->
        val parts = entry.split(FIELD_SEP)
        if (parts.size != 2) return@mapNotNull null
        val id = parts[0]
        if (id.isBlank()) return@mapNotNull null
        val finishedAt = parts[1].toLongOrNull() ?: return@mapNotNull null
        val round = decodeRound(
            read(historyKey(id, "meta")),
            read(historyKey(id, "players")),
            read(historyKey(id, "holes")),
        ) ?: return@mapNotNull null
        SavedRound(id, finishedAt, round)
    }
}

// ---------------------------------------------------------------------------------------------

private const val DATASTORE_NAME = "discgolf_state"
private val PLAYERS_KEY = stringPreferencesKey("players")
private val COURSES_KEY = stringPreferencesKey("courses")
private val ROUND_META_KEY = stringPreferencesKey("round_meta")
private val ROUND_PLAYERS_KEY = stringPreferencesKey("round_players")
private val ROUND_HOLES_KEY = stringPreferencesKey("round_holes")
private val Context.discGolfDataStore: DataStore<Preferences> by preferencesDataStore(name = DATASTORE_NAME)

private const val HISTORY_DATASTORE_NAME = "discgolf_history"
private val Context.discGolfHistoryDataStore: DataStore<Preferences> by preferencesDataStore(name = HISTORY_DATASTORE_NAME)

/**
 * Persists the player roster, the course list, and the single active round (across its three
 * keys — see "Active round" above) to disk via Jetpack DataStore, all in one preferences file —
 * same "no reason to split it" call ultimate-score made (PLAN.md section 4 "Persistence").
 *
 * Round history is the exception, in its own `discgolf_history` file: a preferences file is
 * rewritten whole on every edit, and the active round is edited on every tap during play, so
 * keeping every past round in the same file would rewrite all of them on every tap.
 *
 * Implements each store interface separately so [RoundViewModel] can be handed just the one(s) a
 * test cares about.
 */
class DataStoreDiscGolfRepository(private val context: Context) : PlayerStore, CourseStore, RoundStore, RoundHistoryStore {

    override suspend fun loadPlayers(): List<Player> {
        val prefs = context.discGolfDataStore.data.first()
        return decodePlayers(prefs[PLAYERS_KEY])
    }

    override suspend fun savePlayers(players: List<Player>) {
        context.discGolfDataStore.edit { prefs -> prefs[PLAYERS_KEY] = encodePlayers(players) }
    }

    override suspend fun loadCourses(): List<Course> {
        val prefs = context.discGolfDataStore.data.first()
        return decodeCourses(prefs[COURSES_KEY])
    }

    override suspend fun saveCourses(courses: List<Course>) {
        context.discGolfDataStore.edit { prefs -> prefs[COURSES_KEY] = encodeCourses(courses) }
    }

    override suspend fun loadRound(): RoundState? {
        val prefs = context.discGolfDataStore.data.first()
        return decodeRound(prefs[ROUND_META_KEY], prefs[ROUND_PLAYERS_KEY], prefs[ROUND_HOLES_KEY])
    }

    override suspend fun saveRound(round: RoundState) {
        context.discGolfDataStore.edit { prefs ->
            prefs[ROUND_META_KEY] = encodeRoundMeta(round)
            prefs[ROUND_PLAYERS_KEY] = encodePlayers(round.players)
            prefs[ROUND_HOLES_KEY] = encodeHoles(round.holes)
        }
    }

    override suspend fun clearRound() {
        context.discGolfDataStore.edit { prefs ->
            prefs.remove(ROUND_META_KEY)
            prefs.remove(ROUND_PLAYERS_KEY)
            prefs.remove(ROUND_HOLES_KEY)
        }
    }

    override suspend fun loadHistory(): List<SavedRound> {
        val prefs = context.discGolfHistoryDataStore.data.first()
        return decodeHistory { key -> prefs[stringPreferencesKey(key)] }
    }

    override suspend fun saveHistory(history: List<SavedRound>) {
        context.discGolfHistoryDataStore.edit { prefs ->
            // Rewritten whole, so a deleted round's pieces go along with its index record. Safe to
            // clear because this file holds nothing but history.
            prefs.clear()
            for ((key, value) in encodeHistory(history)) {
                prefs[stringPreferencesKey(key)] = value
            }
        }
    }
}

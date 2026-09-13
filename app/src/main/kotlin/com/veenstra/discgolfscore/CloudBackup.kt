package com.veenstra.discgolfscore

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * The DIY cloud-saves feature (`CLOUD_SAVES.md`). This file is the pure half of it: encode the
 * watch's saved state to JSON, decode it back, and merge two decoded snapshots into one. No
 * Android imports, no coroutines, no networking — exactly the same "pure codecs, JVM-testable"
 * shape [DiscGolfRepository.kt]'s player/course/round codecs already use (`CLOUD_SAVES.md` section
 * 2 "Pure codecs, JVM-testable"), and for the same reason: the round-trip and merge rules are
 * where this feature's correctness actually lives, and they're worth testing without a device, a
 * fake server, or even the `BackupClient` interface Phase B adds on top of this file.
 *
 * Unlike [DiscGolfRepository.kt]'s hand-rolled control-character format, this one is JSON via
 * `org.json` (`CLOUD_SAVES.md` section 2 "`org.json`, not kotlinx.serialization") — the wire format
 * a hand-written Apps Script can read, and the one Derek can eyeball in the Apps Script editor's
 * logs when something goes wrong. `org.json` ships inside the Android framework, so production code
 * pays no runtime-dependency or APK-size cost for it; the one thing it needs that
 * [DiscGolfRepository.kt]'s codecs don't is `testImplementation("org.json:json:…")` in
 * `app/build.gradle.kts`, because android.jar's `org.json` classes are stubs in a plain JVM unit
 * test (`RuntimeException("Stub!")` the moment any method is called) — see that file's comment and
 * `CLOUD_SAVES.md` section 5 item 4.
 */
data class BackupData(
    val players: List<Player>,
    val courses: List<Course>,
    val rounds: List<SavedRound>,
)

/**
 * The one version tag this format carries, at the root of every document [encodeBackup] writes
 * (`CLOUD_SAVES.md` section 2 "One version tag, forward-compatible decode"). [decodeBackup] rejects
 * a document whose `v` doesn't match this exactly — including one where `v` is missing entirely —
 * the same all-or-nothing failure [COURSE_FORMAT_TAG] would trigger for the on-disk course format
 * if that codec didn't have a legacy-format fallback to fall back to. There's no legacy format here
 * (this feature never shipped before this document had a version tag), so unlike that codec, a
 * mismatched `v` here isn't "decode it the old way" — it's "an app newer or older than this decoder
 * expects wrote this, don't guess," matching `CLOUD_SAVES.md`'s own framing: unknown *fields* are
 * ignored (forward compatibility for additive changes), but the version tag itself is not a field
 * to be lenient about.
 *
 * Only [CloudBackup.kt]'s own document carries this tag. It is deliberately a different concern
 * from the wire envelope's own `"v": 1` (`CLOUD_SAVES.md` section 3) that `BackupClient` (Phase B)
 * wraps a push request in, or reads back off a pull response — this file has no idea an HTTP
 * request or an `op`/`secret`/`deviceId` even exist. `BackupClient` is the seam: it calls
 * [encodeBackup] to get this file's own tagged JSON, nests it under the request's `"data"` key
 * (where the extra, request-scoped `"v"` key riding along inside `data` is simply another unknown
 * field the Apps Script ignores — see the class doc above), and, on the way back, reassembles a
 * document this function will accept out of the response's `"data"` object plus the response's own
 * `"v"`, since section 3's example response shows `v` living beside `data`, not inside it.
 */
private const val BACKUP_FORMAT_VERSION = 1

// ---------------------------------------------------------------------------------------------
// Encode
// ---------------------------------------------------------------------------------------------

/**
 * The entire watch-local state this feature backs up, as one JSON document: `{"v":1,"players":
 * [...],"courses":[...],"rounds":[...]}`, matching `CLOUD_SAVES.md` section 3's `data` shape (with
 * the version tag folded in — see [BACKUP_FORMAT_VERSION]). Deliberately excludes the active round
 * (`CLOUD_SAVES.md` section 2 "Active round is not backed up") — [history] is the *finished* round
 * list ([RoundHistoryStore]), never [RoundState] directly, so there's no `RoundState?` parameter
 * for a caller to accidentally pass the live round into.
 */
internal fun encodeBackup(players: List<Player>, courses: List<Course>, history: List<SavedRound>): String {
    val root = JSONObject()
    root.put("v", BACKUP_FORMAT_VERSION)
    root.put("players", JSONArray().apply { players.forEach { put(encodePlayerJson(it)) } })
    root.put("courses", JSONArray().apply { courses.forEach { put(encodeCourseJson(it)) } })
    root.put("rounds", JSONArray().apply { history.forEach { put(encodeSavedRoundJson(it)) } })
    return root.toString()
}

/**
 * `id`/`name`/`color`. [Player.color] is written with the `Long`-taking `put` overload — it's a
 * packed ARGB value that can exceed `Int.MAX_VALUE` (`0xFFFFFFFF` opaque white being the everyday
 * example, [DEFAULT_PLAYER_COLOR] itself), and `org.json` has separate `Int`/`Long` `put` overloads
 * that don't silently truncate the way reading it back with the wrong `optXxx` would (`CLOUD_SAVES.md`
 * section 5 item 5 — the read side of the same trap). Reused verbatim for a round's snapshot roster
 * ([encodeSavedRoundJson]), the exact same [Player] shape either way.
 */
private fun encodePlayerJson(player: Player): JSONObject =
    JSONObject().put("id", player.id).put("name", player.name).put("color", player.color)

/**
 * `id`/`name`/`holeCount`/`pars`/`recordToPar`/`recordHolders`. `recordToPar`/`recordHolders` are
 * omitted entirely (not written as JSON `null`) when [Layout.recordToPar] is `null` — an absent key
 * and an explicit JSON `null` decode to the same "no record" outcome in [decodeLayoutJson] (it
 * checks `has()` before trusting the value either way, per `CLOUD_SAVES.md` section 5 item 6), and
 * omitting the key entirely keeps a record-less layout's JSON a little smaller with no loss of
 * meaning. `pars` keeps every `0` in it — an unlearned hole, per `CLOUD_SAVES.md` section 3's field
 * notes — there is no filtering or substitution here.
 */
private fun encodeLayoutJson(layout: Layout): JSONObject {
    val obj = JSONObject()
        .put("id", layout.id)
        .put("name", layout.name)
        .put("holeCount", layout.holeCount)
        .put("pars", JSONArray(layout.pars))
    val recordToPar = layout.recordToPar
    if (recordToPar != null) {
        obj.put("recordToPar", recordToPar)
        obj.put("recordHolders", JSONArray(layout.recordHolderNames))
    }
    return obj
}

/** `id`/`name`/`layouts` — a course's own three fields, one level up from [encodeLayoutJson]. */
private fun encodeCourseJson(course: Course): JSONObject =
    JSONObject()
        .put("id", course.id)
        .put("name", course.name)
        .put("layouts", JSONArray().apply { course.layouts.forEach { put(encodeLayoutJson(it)) } })

/**
 * One finished round: [SavedRound.id]/[SavedRound.finishedAt] plus every field of its
 * [SavedRound.round] `CLOUD_SAVES.md` section 3 names — including `players`, the round's **snapshot**
 * roster ([RoundState.players]), not the live roster. `courseId`/`layoutId` follow the same "empty
 * string means null" convention [DiscGolfRepository.kt]'s own `encodeRoundMeta` uses, rather than a
 * JSON `null`/`has()` pair — a real id is never blank, so the round trip is unambiguous either way,
 * and reusing the convention already established elsewhere in this app beats introducing a second
 * one for the same idea.
 */
private fun encodeSavedRoundJson(saved: SavedRound): JSONObject {
    val round = saved.round
    return JSONObject()
        .put("id", saved.id)
        .put("finishedAt", saved.finishedAt)
        .put("courseId", round.courseId ?: "")
        .put("courseName", round.courseName)
        .put("layoutId", round.layoutId ?: "")
        .put("layoutName", round.layoutName)
        .put("currentHole", round.currentHole)
        .put("finished", round.finished)
        .put("players", JSONArray().apply { round.players.forEach { put(encodePlayerJson(it)) } })
        .put("holes", JSONArray().apply { round.holes.forEach { put(encodeHoleJson(it)) } })
}

/**
 * `par`/`learned`/`strokes`/`touched`. `learned` is the wire name for
 * [HoleScore.wasLearnedAtStart] — `CLOUD_SAVES.md` section 3's field notes: "short name because it
 * appears once per hole per round and the payload cell has a 50 000-character limit" (section 5
 * item 7). `strokes` is a JSON object keyed by player id (itself a numeric string, per this app's
 * id convention) rather than an array of pairs — the natural JSON shape for "map", and the one
 * that's also easiest to hand-edit if a `_payload` cell is ever inspected directly.
 */
private fun encodeHoleJson(hole: HoleScore): JSONObject {
    val strokes = JSONObject()
    hole.strokes.forEach { (playerId, value) -> strokes.put(playerId, value) }
    return JSONObject()
        .put("par", hole.par)
        .put("learned", hole.wasLearnedAtStart)
        .put("strokes", strokes)
        .put("touched", JSONArray(hole.touched.toList()))
}

// ---------------------------------------------------------------------------------------------
// Decode
// ---------------------------------------------------------------------------------------------

/**
 * The inverse of [encodeBackup]. `null` for a blank/absent string, a string that isn't valid JSON
 * at all (including one truncated mid-object — a network response cut off by a timeout is exactly
 * this), or one whose `v` doesn't match [BACKUP_FORMAT_VERSION] (see that constant's doc for why a
 * version mismatch is rejected outright rather than treated the way an unrecognized *field* is).
 *
 * Past that gate, this follows the same "drop bad records, never crash" rule as
 * [DiscGolfRepository.kt]'s codecs (`CLOUD_SAVES.md` section 1 "Design principles"): a malformed
 * player, layout, course, or round is dropped individually rather than failing the whole decode,
 * because a hand-edited sheet is *expected* to contain the occasional typo (`CLOUD_SAVES.md`'s "The
 * tradeoff on hand-editing"), and one bad row should cost that row, not the whole restore. A course
 * is the one partial exception: dropping one malformed layout out of several keeps the rest of that
 * course (a course is a container, and a typo in "9 short reds" shouldn't take "18 long blues" down
 * with it — the same reasoning `CLOUD_SAVES.md` gives for merging layouts one level deeper rather
 * than replacing a course wholesale), but a course left with *zero* surviving layouts is dropped
 * entirely, since [Course.layouts] is never empty by construction anywhere else in this app.
 */
internal fun decodeBackup(json: String?): BackupData? {
    if (json.isNullOrBlank()) return null
    val root = try {
        JSONObject(json)
    } catch (malformed: JSONException) {
        return null
    }
    if (root.optInt("v", -1) != BACKUP_FORMAT_VERSION) return null

    val players = root.optJSONArray("players")?.let { decodeJsonObjects(it, ::decodePlayerJson) } ?: emptyList()
    val courses = root.optJSONArray("courses")?.let { decodeJsonObjects(it, ::decodeCourseJson) } ?: emptyList()
    val rounds = root.optJSONArray("rounds")?.let { decodeJsonObjects(it, ::decodeSavedRoundJson) } ?: emptyList()
    return BackupData(players, courses, rounds)
}

/**
 * Applies [decode] to every JSON object in [array], dropping (not failing on) any element that
 * isn't itself a JSON object, or that [decode] itself rejects — the "one bad record doesn't sink
 * the rest" rule stated on [decodeBackup], applied once here for every list this file decodes.
 */
private fun <T> decodeJsonObjects(array: JSONArray, decode: (JSONObject) -> T?): List<T> {
    val result = ArrayList<T>(array.length())
    for (i in 0 until array.length()) {
        val obj = array.optJSONObject(i) ?: continue
        decode(obj)?.let(result::add)
    }
    return result
}

/**
 * Dropped if `id`/`name` is missing or blank. `color` uses `optLong`, never `optInt`
 * (`CLOUD_SAVES.md` section 5 item 5): `0xFFFFFFFF` overflows a signed 32-bit `Int`, and reading it
 * with `optInt` comes back as a nonsense value depending on how the overflow happens to be
 * interpreted — turning [DEFAULT_PLAYER_COLOR]'s opaque white into black or fully transparent. A
 * missing/non-numeric `color` field defaults to [DEFAULT_PLAYER_COLOR], the same value a bare
 * `Player(id, name)` gets, rather than dropping the whole player over a cosmetic field.
 */
private fun decodePlayerJson(obj: JSONObject): Player? {
    val id = obj.optString("id", "")
    val name = obj.optString("name", "")
    if (id.isBlank() || name.isBlank()) return null
    val color = obj.optLong("color", DEFAULT_PLAYER_COLOR)
    return Player(id = id, name = name, color = color)
}

/**
 * Dropped if `id`/`name` is missing or blank, `holeCount` isn't a positive integer, `pars` is
 * missing or any entry in it isn't a whole number, or `pars`' length disagrees with `holeCount` —
 * the same structural rules [decodeLayoutFields][DiscGolfRepository.kt] already enforces for the
 * on-disk format, applied here to JSON instead of a control-character record.
 *
 * `recordToPar` is read with an explicit `has()`/`isNull()` check, never a bare `optInt` with a
 * fallback (`CLOUD_SAVES.md` section 5 item 6): `optInt("recordToPar", 0)` on a genuinely absent
 * field returns `0`, which is a **valid even-par record**, not "no record" — that would invent a
 * record for every layout that has never actually recorded one. A `recordToPar` present but not a
 * whole number drops the whole layout, same as a malformed `holeCount`. `recordHolders` is only
 * consulted when `recordToPar` is non-null, and defaults to an empty list if that array is itself
 * missing — a record with a score but a blank holder list is unusual but not a reason to lose the
 * layout's par data along with it.
 */
private fun decodeLayoutJson(obj: JSONObject): Layout? {
    val id = obj.optString("id", "")
    val name = obj.optString("name", "")
    if (id.isBlank() || name.isBlank()) return null
    val holeCount = obj.optInt("holeCount", -1)
    if (holeCount <= 0) return null

    val parsArray = obj.optJSONArray("pars") ?: return null
    if (parsArray.length() != holeCount) return null
    val pars = ArrayList<Int>(holeCount)
    for (i in 0 until parsArray.length()) {
        pars.add(parsArray.optIntOrNull(i) ?: return null)
    }

    val recordToPar: Int? = when {
        !obj.has("recordToPar") || obj.isNull("recordToPar") -> null
        else -> obj.optIntOrNull("recordToPar") ?: return null
    }
    val recordHolderNames = if (recordToPar == null) {
        emptyList()
    } else {
        val holders = obj.optJSONArray("recordHolders")
        if (holders == null) {
            emptyList()
        } else {
            (0 until holders.length()).mapNotNull { i -> holders.optString(i, "").ifBlank { null } }
        }
    }
    return Layout(id, name, holeCount, pars, recordHolderNames, recordToPar)
}

/** `JSONArray.opt(index)` as a whole number, or `null` if that index isn't one — used instead of `optInt`'s silent 0-on-failure default, which can't be told apart from an index that genuinely holds `0`. */
private fun JSONArray.optIntOrNull(index: Int): Int? = when (val value = opt(index)) {
    is Int -> value
    is Long -> value.toInt()
    null -> null
    else -> null
}

/** `JSONObject.opt(key)` as a whole number, or `null` if that key isn't one — the object-keyed twin of [JSONArray.optIntOrNull]. */
private fun JSONObject.optIntOrNull(key: String): Int? = when (val value = opt(key)) {
    is Int -> value
    is Long -> value.toInt()
    null -> null
    else -> null
}

/**
 * Dropped if `id`/`name` is missing or blank, or `layouts` is missing, not a list of objects, or
 * decodes to zero surviving layouts (see [decodeBackup]'s own doc for why a course tolerates
 * *some* of its layouts being dropped but not *all* of them).
 */
private fun decodeCourseJson(obj: JSONObject): Course? {
    val id = obj.optString("id", "")
    val name = obj.optString("name", "")
    if (id.isBlank() || name.isBlank()) return null
    val layoutsArray = obj.optJSONArray("layouts") ?: return null
    val layouts = decodeJsonObjects(layoutsArray, ::decodeLayoutJson)
    if (layouts.isEmpty()) return null
    return Course(id, name, layouts)
}

/**
 * Dropped if `id` is blank, `finishedAt`/`courseName`/`currentHole`/`holes` are missing or the
 * wrong type, `holes` is empty, or `currentHole` falls outside `1..holes.size` — the same
 * "a round isn't a list of independent records, it's one card" rule
 * [decodeRound][DiscGolfRepository.kt] applies to the on-disk active-round format, applied here to
 * a *finished* round's JSON instead. `courseId`/`layoutId` decode an empty string back to `null`,
 * the inverse of [encodeSavedRoundJson]'s own convention. `players` — the round's **snapshot**
 * roster, per `CLOUD_SAVES.md` section 3's field notes — defaults to an empty list if missing
 * rather than failing the round outright; a round with nobody in it is meaningless but not this
 * function's problem to police, since [RoundState.strokesThrough]/[RoundState.toPar] already treat
 * a missing per-player entry as `0`.
 */
private fun decodeSavedRoundJson(obj: JSONObject): SavedRound? {
    val id = obj.optString("id", "")
    if (id.isBlank()) return null
    val finishedAt = obj.optLongOrNull("finishedAt") ?: return null
    val courseName = obj.optString("courseName", "")
    if (courseName.isBlank()) return null
    val currentHole = obj.optIntOrNull("currentHole") ?: return null

    val holesArray = obj.optJSONArray("holes") ?: return null
    val holes = ArrayList<HoleScore>(holesArray.length())
    for (i in 0 until holesArray.length()) {
        val holeObj = holesArray.optJSONObject(i) ?: return null
        holes.add(decodeHoleJson(holeObj) ?: return null)
    }
    if (holes.isEmpty()) return null
    if (currentHole < 1 || currentHole > holes.size) return null

    val courseId = obj.optString("courseId", "").ifEmpty { null }
    val layoutId = obj.optString("layoutId", "").ifEmpty { null }
    val layoutName = obj.optString("layoutName", "")
    val finished = obj.optBoolean("finished", false)
    val players = obj.optJSONArray("players")?.let { decodeJsonObjects(it, ::decodePlayerJson) } ?: emptyList()

    val round = RoundState(
        courseId = courseId,
        courseName = courseName,
        players = players,
        holes = holes,
        currentHole = currentHole,
        finished = finished,
        layoutId = layoutId,
        layoutName = layoutName,
    )
    return SavedRound(id = id, finishedAt = finishedAt, round = round)
}

/** `JSONObject.opt(key)` as a `Long`, or `null` if that key isn't a whole number — the `Long`-typed twin of [optIntOrNull], for [SavedRound.finishedAt] (an epoch-millisecond timestamp, too large for `Int` well before the year 2038). */
private fun JSONObject.optLongOrNull(key: String): Long? = when (val value = opt(key)) {
    is Int -> value.toLong()
    is Long -> value
    null -> null
    else -> null
}

/**
 * Dropped if `par`/`learned` is missing or the wrong type, or any `strokes` value isn't a whole
 * number. `strokes` keys are trusted as-is (a hole's `strokes` map is keyed by player id, and an
 * id that doesn't match anyone in `players` is harmless — [RoundState.strokesThrough] only ever
 * looks up ids it already knows). `touched` defaults to an empty set if missing, and its entries
 * are read as strings and blanks filtered out, rather than failing the whole hole over a stray
 * empty string.
 */
private fun decodeHoleJson(obj: JSONObject): HoleScore? {
    val par = obj.optIntOrNull("par") ?: return null
    val learned = obj.opt("learned") as? Boolean ?: return null

    val strokesObj = obj.optJSONObject("strokes") ?: JSONObject()
    val strokes = LinkedHashMap<String, Int>()
    for (key in strokesObj.keys()) {
        strokes[key] = strokesObj.optIntOrNull(key) ?: return null
    }

    val touchedArray = obj.optJSONArray("touched")
    val touched = if (touchedArray == null) {
        emptySet()
    } else {
        (0 until touchedArray.length()).mapNotNull { i -> touchedArray.optString(i, "").ifBlank { null } }.toSet()
    }
    return HoleScore(par = par, wasLearnedAtStart = learned, strokes = strokes, touched = touched)
}

// ---------------------------------------------------------------------------------------------
// Merge
// ---------------------------------------------------------------------------------------------

/**
 * Combines a watch's own [local] snapshot with a [remote] one just pulled from the sheet, per
 * `CLOUD_SAVES.md` section 2's three merge-rule rows: **ids present in both take the sheet's
 * (`remote`'s) version; ids only on the watch are kept untouched; ids only in the sheet are
 * added.** Never deletes anything local — restore merges, it never clears (`CLOUD_SAVES.md`
 * section 1 "Never lose data on the watch").
 *
 * `courses` merges one level deeper than `players`/`rounds`: for a course id present on both
 * sides, its `layouts` list is itself merged by [Layout.id] under the same rule, rather than the
 * sheet's whole course record replacing the watch's outright — `CLOUD_SAVES.md` section 2 "Layouts
 * merge one level deeper": a course is a container, and replacing it wholesale would delete a
 * layout the sheet's copy doesn't yet know about (e.g. one added on the watch after the last
 * push). A shared course's `name`, like every other one-level field, still takes the sheet's
 * version.
 *
 * `rounds` is re-sorted by [SavedRound.finishedAt] descending after merging — newest first, the
 * order [RoundHistoryStore]/[PastRoundsScreen] already expect (`RoundViewModel.saveToHistory`
 * prepends), since merging two already-sorted lists by id doesn't itself preserve that order.
 */
internal fun mergeBackup(local: BackupData, remote: BackupData): BackupData {
    val players = mergeById(local.players, remote.players) { it.id }
    val courses = mergeCourses(local.courses, remote.courses)
    val rounds = mergeById(local.rounds, remote.rounds) { it.id }.sortedByDescending { it.finishedAt }
    return BackupData(players = players, courses = courses, rounds = rounds)
}

/**
 * The shared shape behind every "sheet wins, nothing local is deleted" merge in this file: for
 * each item in [local], substitute [remote]'s copy if [remote] has one with the same id (sheet
 * wins), otherwise keep the local one untouched; then append every [remote] item whose id isn't in
 * [local] at all, in [remote]'s own order. [local]'s own relative order is preserved for every id
 * it already had, id-only-in-remote items are simply new arrivals appended after.
 */
private fun <T> mergeById(local: List<T>, remote: List<T>, idOf: (T) -> String): List<T> {
    val remoteById = remote.associateBy(idOf)
    val merged = local.map { remoteById[idOf(it)] ?: it }.toMutableList()
    val localIds = local.mapTo(HashSet(), idOf)
    remote.forEach { item -> if (idOf(item) !in localIds) merged.add(item) }
    return merged
}

/**
 * [mergeById] specialized for [Course], which additionally merges `layouts` one level deeper for
 * any course id present on both sides — see [mergeBackup]'s own doc for why. A course only on one
 * side is kept/added exactly as-is, layouts included; only a course present on *both* sides gets
 * its layout list merged rather than replaced.
 */
private fun mergeCourses(local: List<Course>, remote: List<Course>): List<Course> {
    val remoteById = remote.associateBy { it.id }
    val merged = local.map { localCourse ->
        val remoteCourse = remoteById[localCourse.id] ?: return@map localCourse
        Course(
            id = localCourse.id,
            name = remoteCourse.name,
            layouts = mergeById(localCourse.layouts, remoteCourse.layouts) { it.id },
        )
    }.toMutableList()
    val localIds = local.mapTo(HashSet()) { it.id }
    remote.forEach { course -> if (course.id !in localIds) merged.add(course) }
    return merged
}

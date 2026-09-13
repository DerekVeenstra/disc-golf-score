package com.veenstra.discgolfscore

import org.json.JSONException
import org.json.JSONObject

/**
 * The transport half of the cloud-saves feature (`CLOUD_SAVES.md` section 6 Phase B), on top of
 * [CloudBackup.kt]'s pure `data` codec. [BackupClient] is the seam [RoundViewModel] talks to and
 * tests fake — see `RoundViewModelSyncTest`'s in-memory fake, the same shape every other store
 * interface in this app gets ([PlayerStore], [RoundStore], …). [HttpBackupClient] is the one real
 * implementation, built on `HttpURLConnection`; it can only be verified on-device (Phase E), the
 * same limitation [DataStoreDiscGolfRepository] has for the exact same reason — a real network
 * call, like a real DataStore file, isn't something a plain JVM unit test can exercise.
 *
 * What *can* be unit-tested with no device and no network is everything [HttpBackupClient] hands
 * off to below: building the request envelope's JSON and parsing the response envelope's JSON are
 * both pure string-in/string-out functions, so every one of `CLOUD_SAVES.md` section 3's three
 * example response shapes (success-with-counts, success-with-data-and-warnings, `ok:false`) gets
 * covered directly in `BackupEnvelopeTest`, the same way [CloudBackup.kt]'s own format is covered
 * without a device.
 */
sealed interface PushResult {
    /** [counts] is what the script reports it actually wrote — not necessarily what was sent, if the script's own validation dropped something. [warnings] names any row it couldn't parse (`CLOUD_SAVES.md`'s "tradeoff on hand-editing"). */
    data class Success(val counts: SyncCounts, val warnings: List<String>) : PushResult

    /** A user-facing reason nothing was pushed: "No network", an HTTP status, `"unauthorized"` from a wrong secret, or a malformed response. [RoundViewModel] shows this text as-is. */
    data class Failure(val message: String) : PushResult
}

sealed interface PullResult {
    data class Success(val data: BackupData, val warnings: List<String>) : PullResult
    data class Failure(val message: String) : PullResult
}

/** How many of each kind the script actually wrote on a push — `CLOUD_SAVES.md` section 3's response `counts` object. */
data class SyncCounts(val players: Int, val courses: Int, val rounds: Int)

/**
 * Pushes the watch's full backup-eligible state (players, courses, finished history — never the
 * active round, `CLOUD_SAVES.md` section 2 "Active round is not backed up") to the configured
 * sheet, or pulls the sheet's current state back down. Neither method merges — that's
 * [mergeBackup]'s job, called by [RoundViewModel] with whatever [PullResult.Success.data] hands
 * back. A [BackupClient] is already bound to one endpoint and secret (constructor parameters on
 * [HttpBackupClient]) rather than taking them per-call, so [RoundViewModel] builds a fresh one from
 * [SyncConfig] each time it's needed instead of holding one for the app's lifetime — cheap, and it
 * means a config change (or [SyncConfigReceiver] rewriting it) can never leave a stale client
 * pointed at an old URL.
 */
interface BackupClient {
    suspend fun push(data: BackupData): PushResult
    suspend fun pull(): PullResult
}

// ---------------------------------------------------------------------------------------------
// The wire envelope (CLOUD_SAVES.md section 3) — pure, JVM-testable, no networking. Wraps/unwraps
// CloudBackup.kt's own `data` JSON with the request's op/secret/deviceId/sentAt, and the
// response's ok/v/counts/data/warnings/error. [HttpBackupClient] is the only caller; kept as
// free functions (not methods on it) so BackupEnvelopeTest can exercise them with plain strings.
// ---------------------------------------------------------------------------------------------

/** `CLOUD_SAVES.md` section 3's request envelope for a push: `{"v":1,"op":"push","secret":…,"deviceId":…,"sentAt":…,"data":{…}}`. `data` is [encodeBackup]'s own JSON, nested under the `data` key — its own `v` field rides along inside `data` as an extra field the Apps Script simply ignores (`CLOUD_SAVES.md`'s "unknown fields are ignored" principle), rather than this function stripping it back out for no benefit. */
internal fun buildPushRequestBody(secret: String, deviceId: String, sentAt: Long, data: BackupData): String {
    val dataJson = JSONObject(encodeBackup(data.players, data.courses, data.rounds))
    return JSONObject()
        .put("v", 1)
        .put("op", "push")
        .put("secret", secret)
        .put("deviceId", deviceId)
        .put("sentAt", sentAt)
        .put("data", dataJson)
        .toString()
}

/** `CLOUD_SAVES.md` section 3's request envelope for a pull: the same shape as [buildPushRequestBody] minus `data`, which a pull has nothing to send. */
internal fun buildPullRequestBody(secret: String, deviceId: String, sentAt: Long): String =
    JSONObject()
        .put("v", 1)
        .put("op", "pull")
        .put("secret", secret)
        .put("deviceId", deviceId)
        .put("sentAt", sentAt)
        .toString()

/**
 * `CLOUD_SAVES.md` section 3's push response: `{"ok":true,"v":1,"counts":{…},"warnings":[…]}` on
 * success, `{"ok":false,"error":"…"}` on failure. [body] failing to parse at all (blank, not JSON,
 * truncated — the same failure modes [decodeBackup] guards against) is [PushResult.Failure] with a
 * generic message, never a crash. The `ok:false` shape is checked **before** the version tag: that
 * example response in `CLOUD_SAVES.md` section 3 has no `v` field at all, so requiring one there
 * first would misreport a real "wrong secret" as "unsupported response version."
 */
internal fun parsePushResponse(body: String?): PushResult {
    val response = parseResponseEnvelope(body) ?: return PushResult.Failure(BAD_RESPONSE_MESSAGE)
    val error = response.errorMessageOrNull()
    if (error != null) return PushResult.Failure(error)
    if (!response.hasSupportedVersion()) return PushResult.Failure(UNSUPPORTED_VERSION_MESSAGE)

    val countsObj = response.obj.optJSONObject("counts") ?: return PushResult.Failure(BAD_RESPONSE_MESSAGE)
    val counts = SyncCounts(
        players = countsObj.optInt("players", 0),
        courses = countsObj.optInt("courses", 0),
        rounds = countsObj.optInt("rounds", 0),
    )
    return PushResult.Success(counts, response.warnings())
}

/**
 * `CLOUD_SAVES.md` section 3's pull response: `{"ok":true,"v":1,"data":{…},"warnings":[…]}`. The
 * response's `v` lives beside `data`, not inside it — unlike [encodeBackup]'s own documents, which
 * carry their version tag at their own root (see [BACKUP_FORMAT_VERSION]'s doc for why these are
 * two deliberately separate version tags). So `data` gets the response's `v` copied into it before
 * being handed to [decodeBackup], reusing that decoder exactly rather than re-implementing its
 * rules here a second time.
 */
internal fun parsePullResponse(body: String?): PullResult {
    val response = parseResponseEnvelope(body) ?: return PullResult.Failure(BAD_RESPONSE_MESSAGE)
    val error = response.errorMessageOrNull()
    if (error != null) return PullResult.Failure(error)
    if (!response.hasSupportedVersion()) return PullResult.Failure(UNSUPPORTED_VERSION_MESSAGE)

    val dataObj = response.obj.optJSONObject("data") ?: return PullResult.Failure(BAD_RESPONSE_MESSAGE)
    dataObj.put("v", response.obj.optInt("v", -1))
    val decoded = decodeBackup(dataObj.toString()) ?: return PullResult.Failure(BAD_RESPONSE_MESSAGE)
    return PullResult.Success(decoded, response.warnings())
}

private const val BAD_RESPONSE_MESSAGE = "Bad response from server"
private const val UNSUPPORTED_VERSION_MESSAGE = "Unsupported response version"

/** A parsed, not-yet-interpreted response body, shared by [parsePushResponse]/[parsePullResponse]. */
@JvmInline
private value class ResponseEnvelope(val obj: JSONObject) {
    /** `null` when `ok` is `true` (or, generously, simply absent on an otherwise-valid response); the script's own `error` string, or a generic fallback, when `ok` is explicitly `false`. */
    fun errorMessageOrNull(): String? {
        if (obj.has("ok") && !obj.optBoolean("ok", true)) return obj.optString("error", "Unknown error")
        return null
    }

    fun hasSupportedVersion(): Boolean = obj.optInt("v", -1) == 1

    fun warnings(): List<String> {
        val array = obj.optJSONArray("warnings") ?: return emptyList()
        return (0 until array.length()).mapNotNull { i -> array.optString(i, "").ifBlank { null } }
    }
}

private fun parseResponseEnvelope(body: String?): ResponseEnvelope? {
    if (body.isNullOrBlank()) return null
    return try {
        ResponseEnvelope(JSONObject(body))
    } catch (malformed: JSONException) {
        null
    }
}

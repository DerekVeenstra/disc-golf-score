package com.veenstra.discgolfscore

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `buildPushRequestBody`/`buildPullRequestBody`/`parsePushResponse`/`parsePullResponse` — the wire
 * envelope `CLOUD_SAVES.md` section 3 defines, wrapped around [CloudBackup.kt]'s own `data` JSON.
 * Pure string-in/string-out functions, so every one of section 3's three example response shapes
 * (success-with-counts, success-with-data-and-warnings, `ok:false`) is covered here with no device
 * and no [HttpBackupClient] involved.
 */
class BackupEnvelopeTest {

    private val player = Player(id = "1", name = "Derek", color = 0xFF1E6FD9)
    private val data = BackupData(players = listOf(player), courses = emptyList(), rounds = emptyList())

    // ---- Requests --------------------------------------------------------------------------------

    @Test
    fun `a push request envelope carries v, op, secret, deviceId, sentAt, and data`() {
        val body = buildPushRequestBody(secret = "s3cr3t", deviceId = "ticwatch", sentAt = 1757779200000L, data = data)
        val obj = JSONObject(body)
        assertEquals(1, obj.getInt("v"))
        assertEquals("push", obj.getString("op"))
        assertEquals("s3cr3t", obj.getString("secret"))
        assertEquals("ticwatch", obj.getString("deviceId"))
        assertEquals(1757779200000L, obj.getLong("sentAt"))
        assertEquals("Derek", obj.getJSONObject("data").getJSONArray("players").getJSONObject(0).getString("name"))
    }

    @Test
    fun `a pull request envelope carries no data key at all`() {
        val body = buildPullRequestBody(secret = "s3cr3t", deviceId = "ticwatch", sentAt = 1757779200000L)
        val obj = JSONObject(body)
        assertEquals("pull", obj.getString("op"))
        assertTrue(!obj.has("data"))
    }

    // ---- Push responses --------------------------------------------------------------------------

    @Test
    fun `a successful push response yields counts and empty warnings`() {
        val body = """{"ok":true,"v":1,"counts":{"players":4,"courses":3,"rounds":27},"warnings":[]}"""
        val result = parsePushResponse(body) as PushResult.Success
        assertEquals(SyncCounts(players = 4, courses = 3, rounds = 27), result.counts)
        assertEquals(emptyList<String>(), result.warnings)
    }

    @Test
    fun `a successful push response with warnings surfaces them`() {
        val body = """{"ok":true,"v":1,"counts":{"players":1,"courses":1,"rounds":1},
            "warnings":["Courses!C14: par list length 17 != hole count 18"]}"""
        val result = parsePushResponse(body) as PushResult.Success
        assertEquals(1, result.warnings.size)
    }

    @Test
    fun `an ok-false push response yields the script's own error message`() {
        val body = """{"ok":false,"error":"unauthorized"}"""
        val result = parsePushResponse(body) as PushResult.Failure
        assertEquals("unauthorized", result.message)
    }

    @Test
    fun `an ok-false response with no v field still reports the error, not a version mismatch`() {
        // CLOUD_SAVES.md section 3's own example of this shape has no "v" key at all.
        val body = """{"ok":false,"error":"unauthorized"}"""
        val result = parsePushResponse(body)
        assertTrue(result is PushResult.Failure)
        assertEquals("unauthorized", (result as PushResult.Failure).message)
    }

    @Test
    fun `a push response with a mismatched v is a failure distinct from an auth error`() {
        val body = """{"ok":true,"v":2,"counts":{"players":0,"courses":0,"rounds":0},"warnings":[]}"""
        val result = parsePushResponse(body) as PushResult.Failure
        assertEquals("Unsupported response version", result.message)
    }

    @Test
    fun `null, blank, and unparsable push response bodies all fail cleanly`() {
        assertTrue(parsePushResponse(null) is PushResult.Failure)
        assertTrue(parsePushResponse("") is PushResult.Failure)
        assertTrue(parsePushResponse("not json") is PushResult.Failure)
        assertTrue(parsePushResponse("""{"ok":true""") is PushResult.Failure) // truncated
    }

    @Test
    fun `a push response missing its counts object fails rather than reporting zero counts`() {
        val body = """{"ok":true,"v":1,"warnings":[]}"""
        assertTrue(parsePushResponse(body) is PushResult.Failure)
    }

    // ---- Pull responses --------------------------------------------------------------------------

    @Test
    fun `a successful pull response decodes data via decodeBackup and surfaces warnings`() {
        val innerData = JSONObject(encodeBackup(listOf(player), emptyList(), emptyList()))
        val body = JSONObject()
            .put("ok", true)
            .put("v", 1)
            .put("data", innerData)
            .put("warnings", org.json.JSONArray(listOf("Courses!C14: par list length 17 != hole count 18")))
            .toString()

        val result = parsePullResponse(body) as PullResult.Success
        assertEquals(listOf(player), result.data.players)
        assertEquals(1, result.warnings.size)
    }

    @Test
    fun `a pull response's inner data object needs no v of its own -- the outer v is reused`() {
        // Section 3's own example response shows "v" beside "data", not inside it.
        val innerData = JSONObject(encodeBackup(emptyList(), emptyList(), emptyList()))
        innerData.remove("v")
        val body = JSONObject().put("ok", true).put("v", 1).put("data", innerData).put("warnings", org.json.JSONArray()).toString()

        val result = parsePullResponse(body)
        assertTrue(result is PullResult.Success)
    }

    @Test
    fun `an ok-false pull response yields the script's own error message`() {
        val body = """{"ok":false,"error":"unauthorized"}"""
        val result = parsePullResponse(body) as PullResult.Failure
        assertEquals("unauthorized", result.message)
    }

    @Test
    fun `a pull response with a mismatched v is a failure`() {
        val innerData = JSONObject(encodeBackup(emptyList(), emptyList(), emptyList()))
        val body = JSONObject().put("ok", true).put("v", 99).put("data", innerData).toString()
        assertTrue(parsePullResponse(body) is PullResult.Failure)
    }

    @Test
    fun `a pull response missing data fails rather than restoring nothing silently`() {
        val body = """{"ok":true,"v":1,"warnings":[]}"""
        assertTrue(parsePullResponse(body) is PullResult.Failure)
    }

    @Test
    fun `null, blank, and unparsable pull response bodies all fail cleanly`() {
        assertTrue(parsePullResponse(null) is PullResult.Failure)
        assertTrue(parsePullResponse("") is PullResult.Failure)
        assertTrue(parsePullResponse("garbage") is PullResult.Failure)
    }
}

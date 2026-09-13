package com.veenstra.discgolfscore

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `encodeBackup`/`decodeBackup`'s round trip, and every malformed-input case `CLOUD_SAVES.md`
 * section 6 Phase A calls out by name: round-trip fidelity (including `touched` sets, `0` pars,
 * `null` vs `0` records, and a round's snapshot roster), malformed/blank/truncated JSON, unknown
 * fields ignored, and a wrong version tag. Merge rules live in [BackupMergeTest].
 */
class CloudBackupCodecTest {

    private val player1 = Player(id = "1725812345678", name = "Derek", color = 0xFF1E6FD9)
    private val player2 = Player(id = "1725812345679", name = "Sam", color = DEFAULT_PLAYER_COLOR)

    private val recordedLayout = Layout(
        id = "1725812300000",
        name = "18 long blues",
        holeCount = 3,
        pars = listOf(3, 4, 3),
        recordHolderNames = listOf("Derek", "Sam"),
        recordToPar = -6,
    )
    private val unlearnedLayout = Layout(
        id = "1725812300001",
        name = "9 short reds",
        holeCount = 2,
        pars = listOf(0, 4), // 0 = "not yet learned" -- must survive the round trip untouched
    )
    private val course = Course(id = "1725812300000", name = "Columbia Lake", layouts = listOf(recordedLayout, unlearnedLayout))

    private val roundState = RoundState(
        courseId = course.id,
        courseName = course.name,
        players = listOf(player1, player2), // the round's *snapshot* roster
        holes = listOf(
            HoleScore(par = 3, wasLearnedAtStart = true, strokes = mapOf(player1.id to 2, player2.id to 3), touched = setOf(player1.id)),
            HoleScore(par = 4, wasLearnedAtStart = false, strokes = mapOf(player1.id to 4, player2.id to 5), touched = emptySet()),
        ),
        currentHole = 2,
        finished = true,
        layoutId = recordedLayout.id,
        layoutName = recordedLayout.name,
    )
    private val savedRound = SavedRound(id = "1757779200000", finishedAt = 1757779200000L, round = roundState)

    // ---- Round trip ------------------------------------------------------------------------------

    @Test
    fun `an empty backup round trips to an empty BackupData`() {
        val json = encodeBackup(emptyList(), emptyList(), emptyList())
        assertEquals(BackupData(emptyList(), emptyList(), emptyList()), decodeBackup(json))
    }

    @Test
    fun `players, courses, and rounds all round trip exactly`() {
        val json = encodeBackup(listOf(player1, player2), listOf(course), listOf(savedRound))
        val decoded = decodeBackup(json)
        assertEquals(BackupData(listOf(player1, player2), listOf(course), listOf(savedRound)), decoded)
    }

    @Test
    fun `a player's default color round trips, not just an explicitly chosen one`() {
        val json = encodeBackup(listOf(player2), emptyList(), emptyList())
        assertEquals(listOf(player2), decodeBackup(json)!!.players)
    }

    @Test
    fun `a 0 par meaning unlearned survives the round trip untouched`() {
        val json = encodeBackup(emptyList(), listOf(course), emptyList())
        val decodedLayout = decodeBackup(json)!!.courses.single().layouts.single { it.id == unlearnedLayout.id }
        assertEquals(listOf(0, 4), decodedLayout.pars)
    }

    @Test
    fun `a layout with no record decodes recordToPar as null, not 0`() {
        val noRecordLayout = unlearnedLayout.copy(recordToPar = null, recordHolderNames = emptyList())
        val json = encodeBackup(emptyList(), listOf(course.copy(layouts = listOf(noRecordLayout))), emptyList())
        val decoded = decodeBackup(json)!!.courses.single().layouts.single()
        assertNull(decoded.recordToPar)
        assertEquals(emptyList<String>(), decoded.recordHolderNames)
    }

    @Test
    fun `a layout with an even-par (0) record is distinguishable from no record at all`() {
        val evenParRecord = unlearnedLayout.copy(recordToPar = 0, recordHolderNames = listOf("Derek"))
        val json = encodeBackup(emptyList(), listOf(course.copy(layouts = listOf(evenParRecord))), emptyList())
        val decoded = decodeBackup(json)!!.courses.single().layouts.single()
        assertEquals(0, decoded.recordToPar)
        assertEquals(listOf("Derek"), decoded.recordHolderNames)
    }

    @Test
    fun `a round's touched set survives the round trip, including an empty one`() {
        val json = encodeBackup(emptyList(), emptyList(), listOf(savedRound))
        val decodedHoles = decodeBackup(json)!!.rounds.single().round.holes
        assertEquals(setOf(player1.id), decodedHoles[0].touched)
        assertEquals(emptySet<String>(), decodedHoles[1].touched)
    }

    @Test
    fun `a round's snapshot player roster round trips, independent of the top-level players list`() {
        // The round's own `players` is a snapshot, not a reference -- decode it even when the
        // top-level players list is empty, matching how a renamed/deleted player still shows up
        // under their old name in an old round (CLOUD_SAVES.md section 3 field notes).
        val json = encodeBackup(emptyList(), emptyList(), listOf(savedRound))
        val decodedRound = decodeBackup(json)!!.rounds.single().round
        assertEquals(listOf(player1, player2), decodedRound.players)
    }

    @Test
    fun `wasLearnedAtStart round trips per hole, independent per hole`() {
        val json = encodeBackup(emptyList(), emptyList(), listOf(savedRound))
        val decodedHoles = decodeBackup(json)!!.rounds.single().round.holes
        assertTrue(decodedHoles[0].wasLearnedAtStart)
        assertTrue(!decodedHoles[1].wasLearnedAtStart)
    }

    @Test
    fun `a round with null courseId and layoutId round trips as null, not an empty string`() {
        val noCourse = roundState.copy(courseId = null, layoutId = null, layoutName = "")
        val saved = SavedRound(id = "1", finishedAt = 1L, round = noCourse)
        val json = encodeBackup(emptyList(), emptyList(), listOf(saved))
        val decoded = decodeBackup(json)!!.rounds.single().round
        assertNull(decoded.courseId)
        assertNull(decoded.layoutId)
    }

    // ---- Malformed / blank / truncated JSON --------------------------------------------------------

    @Test
    fun `null, blank, and empty input all decode to null rather than crashing`() {
        assertNull(decodeBackup(null))
        assertNull(decodeBackup(""))
        assertNull(decodeBackup("   "))
    }

    @Test
    fun `garbage that isn't JSON at all decodes to null`() {
        assertNull(decodeBackup("not json"))
    }

    @Test
    fun `JSON truncated mid-object (a cut-off network response) decodes to null`() {
        val full = encodeBackup(listOf(player1), listOf(course), listOf(savedRound))
        val truncated = full.substring(0, full.length / 2)
        assertNull(decodeBackup(truncated))
    }

    @Test
    fun `a syntactically valid JSON value that isn't an object decodes to null`() {
        assertNull(decodeBackup("[]"))
        assertNull(decodeBackup("42"))
        assertNull(decodeBackup("\"just a string\""))
    }

    // ---- Unknown fields ignored ---------------------------------------------------------------------

    @Test
    fun `unknown top-level fields are ignored, not rejected`() {
        val root = JSONObject(encodeBackup(listOf(player1), emptyList(), emptyList()))
        root.put("aFieldFromANewerAppVersion", "whatever")
        assertEquals(listOf(player1), decodeBackup(root.toString())!!.players)
    }

    @Test
    fun `unknown fields nested inside a player, layout, or round are ignored`() {
        val root = JSONObject(encodeBackup(listOf(player1), listOf(course), listOf(savedRound)))
        root.getJSONArray("players").getJSONObject(0).put("nickname", "Big D")
        root.getJSONArray("courses").getJSONObject(0).getJSONArray("layouts").getJSONObject(0).put("teeType", "DX")
        root.getJSONArray("rounds").getJSONObject(0).put("weather", "sunny")
        val decoded = decodeBackup(root.toString())!!
        assertEquals(listOf(player1), decoded.players)
        assertEquals(listOf(course), decoded.courses)
        assertEquals(listOf(savedRound), decoded.rounds)
    }

    // ---- Wrong version tag -----------------------------------------------------------------------------

    @Test
    fun `a mismatched version tag rejects the whole document`() {
        val root = JSONObject(encodeBackup(listOf(player1), emptyList(), emptyList()))
        root.put("v", 2)
        assertNull(decodeBackup(root.toString()))
    }

    @Test
    fun `a missing version tag rejects the whole document`() {
        val root = JSONObject(encodeBackup(listOf(player1), emptyList(), emptyList()))
        root.remove("v")
        assertNull(decodeBackup(root.toString()))
    }

    // ---- Malformed sub-records dropped, not crashing the whole decode --------------------------------

    @Test
    fun `a player missing an id is dropped, its siblings still decode`() {
        val root = JSONObject(encodeBackup(listOf(player1, player2), emptyList(), emptyList()))
        root.getJSONArray("players").getJSONObject(0).remove("id")
        assertEquals(listOf(player2), decodeBackup(root.toString())!!.players)
    }

    @Test
    fun `a layout whose pars length disagrees with holeCount is dropped, its course's other layouts survive`() {
        val root = JSONObject(encodeBackup(emptyList(), listOf(course), emptyList()))
        val layouts = root.getJSONArray("courses").getJSONObject(0).getJSONArray("layouts")
        // recordedLayout is index 0 (holeCount 3); corrupt its pars to length 2.
        layouts.getJSONObject(0).put("pars", org.json.JSONArray(listOf(3, 4)))
        val decodedCourse = decodeBackup(root.toString())!!.courses.single()
        assertEquals(listOf(unlearnedLayout), decodedCourse.layouts)
    }

    @Test
    fun `a course left with zero surviving layouts is dropped entirely`() {
        val root = JSONObject(encodeBackup(emptyList(), listOf(course), emptyList()))
        val layouts = root.getJSONArray("courses").getJSONObject(0).getJSONArray("layouts")
        for (i in 0 until layouts.length()) {
            layouts.getJSONObject(i).put("holeCount", -1) // every layout now malformed
        }
        assertEquals(emptyList<Course>(), decodeBackup(root.toString())!!.courses)
    }

    @Test
    fun `a round whose currentHole is out of range is dropped`() {
        val root = JSONObject(encodeBackup(emptyList(), emptyList(), listOf(savedRound)))
        root.getJSONArray("rounds").getJSONObject(0).put("currentHole", 99)
        assertEquals(emptyList<SavedRound>(), decodeBackup(root.toString())!!.rounds)
    }

    @Test
    fun `a round with zero holes is dropped`() {
        val root = JSONObject(encodeBackup(emptyList(), emptyList(), listOf(savedRound)))
        root.getJSONArray("rounds").getJSONObject(0).put("holes", org.json.JSONArray())
        assertEquals(emptyList<SavedRound>(), decodeBackup(root.toString())!!.rounds)
    }

    @Test
    fun `a hole with a non-numeric stroke value drops that round, its sibling rounds survive`() {
        val other = SavedRound(id = "2", finishedAt = 2L, round = roundState)
        val root = JSONObject(encodeBackup(emptyList(), emptyList(), listOf(savedRound, other)))
        val firstRoundHoles = root.getJSONArray("rounds").getJSONObject(0).getJSONArray("holes")
        firstRoundHoles.getJSONObject(0).getJSONObject("strokes").put(player1.id, "two")
        val decoded = decodeBackup(root.toString())!!.rounds
        assertEquals(listOf(other.id), decoded.map { it.id })
    }

    @Test
    fun `color as a value overflowing Int (0xFFFFFFFF) decodes exactly, not as -1 or 0`() {
        val whitePlayer = Player(id = "9", name = "White", color = 0xFFFFFFFFL)
        val json = encodeBackup(listOf(whitePlayer), emptyList(), emptyList())
        assertEquals(0xFFFFFFFFL, decodeBackup(json)!!.players.single().color)
    }
}

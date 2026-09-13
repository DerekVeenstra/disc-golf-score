package com.veenstra.discgolfscore

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pure serialization used by DataStoreDiscGolfRepository. Kept free of Android imports
 * precisely so this — including every malformed-input case — can be covered without an emulator.
 * See PLAN.md section 4 "Persistence": U+001F (Unit Separator) between fields, U+001E (Record
 * Separator) between records, plain commas only inside digits-only fields.
 */
class DiscGolfPersistenceCodecTest {

    // ---- Players -------------------------------------------------------------------------------

    @Test
    fun `a single player survives an encode-decode round trip`() {
        val player = Player(id = "1699000000000", name = "Derek")
        assertEquals(listOf(player), decodePlayers(encodePlayers(listOf(player))))
    }

    @Test
    fun `multiple players survive an encode-decode round trip in order`() {
        val players = listOf(Player("1", "Derek"), Player("2", "Sam"), Player("3", "Alex"))
        assertEquals(players, decodePlayers(encodePlayers(players)))
    }

    @Test
    fun `an empty player list round trips to empty`() {
        assertEquals(emptyList<Player>(), decodePlayers(encodePlayers(emptyList())))
    }

    @Test
    fun `absent, empty, or blank stored players decode to an empty list, not a crash`() {
        assertEquals(emptyList<Player>(), decodePlayers(null))
        assertEquals(emptyList<Player>(), decodePlayers(""))
        assertEquals(emptyList<Player>(), decodePlayers("   "))
    }

    @Test
    fun `a player name containing commas and colons round trips exactly`() {
        val player = Player(id = "1", name = "Derek, Jr.: The Sequel")
        assertEquals(listOf(player), decodePlayers(encodePlayers(listOf(player))))
    }

    @Test
    fun `a player name containing the codec's own separator characters is stored stripped, and still round trips`() {
        // sanitizeName() strips control characters before a Player is ever constructed; this pins
        // the contract that even if one snuck through, whatever's left still round trips cleanly
        // rather than corrupting a neighbouring field.
        val sanitized = sanitizeName("DerekJr.")
        val player = Player(id = "1", name = sanitized)
        assertEquals(listOf(player), decodePlayers(encodePlayers(listOf(player))))
    }

    @Test
    fun `malformed player records are dropped rather than crashing the app`() {
        val valid = Player("2", "Sam")
        val raw = listOf(
            "NoId", // blank id
            "1", // blank name
            "onlyonefield", // wrong field count (< 5)
            "${valid.id}${valid.name}",
        ).joinToString("")
        assertEquals(listOf(valid), decodePlayers(raw))
    }

    // ---- Courses -------------------------------------------------------------------------------

    @Test
    fun `a single course survives an encode-decode round trip`() {
        val course = Course(id = "1", name = "Riverside", holeCount = 3, pars = listOf(3, 4, 5))
        assertEquals(listOf(course), decodeCourses(encodeCourses(listOf(course))))
    }

    @Test
    fun `multiple courses, including one with unlearned holes, survive an encode-decode round trip`() {
        val courses = listOf(
            Course("1", "Riverside", 3, listOf(3, 4, 5)),
            Course("2", "Maple Hill", 2, listOf(0, 0)), // freshly created, nothing learned yet
        )
        assertEquals(courses, decodeCourses(encodeCourses(courses)))
    }

    @Test
    fun `an empty course list round trips to empty`() {
        assertEquals(emptyList<Course>(), decodeCourses(encodeCourses(emptyList())))
    }

    @Test
    fun `absent, empty, or blank stored courses decode to an empty list, not a crash`() {
        assertEquals(emptyList<Course>(), decodeCourses(null))
        assertEquals(emptyList<Course>(), decodeCourses(""))
        assertEquals(emptyList<Course>(), decodeCourses("   "))
    }

    @Test
    fun `a course name containing commas and colons round trips exactly`() {
        val course = Course(id = "1", name = "Riverside, Round 2: The Comeback", holeCount = 1, pars = listOf(3))
        assertEquals(listOf(course), decodeCourses(encodeCourses(listOf(course))))
    }

    @Test
    fun `a course name containing the codec's own separator characters is stored stripped, and still round trips`() {
        val sanitized = sanitizeName("RiversidePark")
        val course = Course(id = "1", name = sanitized, holeCount = 1, pars = listOf(3))
        assertEquals(listOf(course), decodeCourses(encodeCourses(listOf(course))))
    }

    @Test
    fun `a course with a single record holder survives an encode-decode round trip`() {
        val course = Course("1", "Riverside", 3, listOf(3, 4, 5), recordHolderNames = listOf("Derek"), recordToPar = -5)
        assertEquals(listOf(course), decodeCourses(encodeCourses(listOf(course))))
    }

    @Test
    fun `a course with a tied record survives an encode-decode round trip, holder order intact`() {
        val course = Course("1", "Riverside", 3, listOf(3, 4, 5), recordHolderNames = listOf("Derek", "Sam", "Alex"), recordToPar = -2)
        assertEquals(listOf(course), decodeCourses(encodeCourses(listOf(course))))
    }

    @Test
    fun `a course with no record round trips with empty holders and a null to-par`() {
        val course = Course("1", "Riverside", 3, listOf(3, 4, 5))
        val decoded = decodeCourses(encodeCourses(listOf(course))).single()
        assertEquals(emptyList<String>(), decoded.recordHolderNames)
        assertNull(decoded.recordToPar)
    }

    @Test
    fun `a record holder name containing a comma round trips exactly, since names aren't comma-joined`() {
        val course = Course("1", "Riverside", 1, listOf(3), recordHolderNames = listOf("Derek, Jr."), recordToPar = -5)
        assertEquals(listOf(course), decodeCourses(encodeCourses(listOf(course))))
    }

    @Test
    fun `a record of exactly even par (0) round trips, not confused with no record`() {
        val course = Course("1", "Riverside", 3, listOf(3, 4, 5), recordHolderNames = listOf("Derek"), recordToPar = 0)
        assertEquals(listOf(course), decodeCourses(encodeCourses(listOf(course))))
    }

    @Test
    fun `malformed course records are dropped rather than crashing the app`() {
        val valid = Course("2", "Sockeye Park", 2, listOf(3, 4))
        val raw = listOf(
            "NoId33,4,5", // blank id
            "133,4,5", // blank name
            "1Namenotanumber3,4,5", // non-numeric hole count
            "1Name0", // non-positive hole count
            "1Name33,4", // par list length disagrees with hole count
            "1Name33,x,5", // non-numeric par
            "onlyonefield", // wrong field count (< 5)
            encodeCourses(listOf(valid)),
        ).joinToString("")
        assertEquals(listOf(valid), decodeCourses(raw))
    }

    @Test
    fun `a course record with a non-numeric recordToPar is dropped`() {
        val valid = Course("2", "Sockeye Park", 2, listOf(3, 4))
        val raw = listOf(
            "1Name33,4notanumber", // non-numeric recordToPar
            encodeCourses(listOf(valid)),
        ).joinToString("")
        assertEquals(listOf(valid), decodeCourses(raw))
    }

    // ---- Active round --------------------------------------------------------------------------
    //
    // The round is stored as three pieces (meta, players, holes) rather than one string -- see
    // DiscGolfRepository.kt's "Active round" comment for why. [encodeRound] returns all three as
    // a Triple purely for these tests' convenience; [decodeRound] takes them back the same way.

    private fun sampleInProgressRound(): RoundState {
        val course = Course(id = "course-1", name = "Riverside", holeCount = 3, pars = listOf(3, 0, 5))
        val players = listOf(Player("p1", "Derek"), Player("p2", "Sam"))
        var round = newRound(course, players)
        round = reduce(round, RoundAction.Adjust("p1", 1)) // touch p1 on hole 1
        round = reduce(round, RoundAction.NextHole)
        round = reduce(round, RoundAction.SetPar(4)) // learn hole 2 as par 4
        return round
    }

    private fun roundTrip(round: RoundState): RoundState? {
        val (meta, playersRaw, holesRaw) = encodeRound(round)
        return decodeRound(meta, playersRaw, holesRaw)
    }

    @Test
    fun `a full in-progress round survives an encode-decode round trip`() {
        val round = sampleInProgressRound()
        assertEquals(round, roundTrip(round))
    }

    @Test
    fun `a finished round survives an encode-decode round trip, still finished`() {
        val round = reduce(sampleInProgressRound(), RoundAction.Finish)
        val decoded = roundTrip(round)
        assertEquals(round, decoded)
        assertTrue(decoded!!.finished)
    }

    @Test
    fun `a round with no course (courseId null) survives an encode-decode round trip`() {
        val round = sampleInProgressRound().copy(courseId = null)
        assertEquals(round, roundTrip(round))
    }

    @Test
    fun `a round referencing a course id that no longer exists still decodes cleanly`() {
        // Not malformed at the codec level -- the codec has no notion of "which courses currently
        // exist"; that check (and the write-back no-op it drives) is RoundViewModel's job, covered
        // in RoundViewModelRosterTest, not here.
        val round = sampleInProgressRound().copy(courseId = "a-deleted-course-id")
        assertEquals(round, roundTrip(round))
    }

    @Test
    fun `a round whose course name contains commas, colons, and the codec's own separators round trips`() {
        val sanitizedName = sanitizeName("Riverside, Round2: The Comeback")
        val round = sampleInProgressRound().copy(courseName = sanitizedName)
        assertEquals(round, roundTrip(round))
    }

    @Test
    fun `a single-hole, single-player round round trips`() {
        val course = Course("c1", "Short", 1, listOf(3))
        val round = newRound(course, listOf(Player("p1", "Derek")))
        assertEquals(round, roundTrip(round))
    }

    @Test
    fun `absent, empty, or blank stored round pieces decode to null, not a crash`() {
        assertNull(decodeRound(null, null, null))
        assertNull(decodeRound("", "", ""))
        assertNull(decodeRound("   ", null, null))
    }

    @Test
    fun `a round missing its players piece still decodes -- players default to empty, not a crash`() {
        // Not expected in practice (a round always has at least one player), but the codec
        // shouldn't crash over it: an absent/blank players key just means no players.
        val (meta, _, holesRaw) = encodeRound(sampleInProgressRound())
        val decoded = decodeRound(meta, null, holesRaw)
        assertEquals(emptyList<Player>(), decoded!!.players)
    }

    @Test
    fun `malformed round meta decodes the whole round to null rather than crashing the app`() {
        val valid = sampleInProgressRound()
        val (meta, playersRaw, holesRaw) = encodeRound(valid)

        // Wrong field count.
        assertNull(decodeRound("onlytwofields", playersRaw, holesRaw))

        // Blank course name.
        assertNull(decodeRound(withField(meta, 1, ""), playersRaw, holesRaw))

        // Non-numeric current hole.
        assertNull(decodeRound(withField(meta, 2, "notanumber"), playersRaw, holesRaw))

        // Unrecognized finished flag.
        assertNull(decodeRound(withField(meta, 3, "maybe"), playersRaw, holesRaw))

        // Current hole out of range (round only has 3 holes).
        assertNull(decodeRound(withField(meta, 2, "999"), playersRaw, holesRaw))
        assertNull(decodeRound(withField(meta, 2, "0"), playersRaw, holesRaw))

        // Sanity: the untouched pieces still decode.
        assertEquals(valid, decodeRound(meta, playersRaw, holesRaw))
    }

    @Test
    fun `a round with a corrupt hole in the middle is dropped entirely, not partially recovered`() {
        val (meta, playersRaw, holesRaw) = encodeRound(sampleInProgressRound())
        val holeRecords = holesRaw.split("").toMutableList()
        val holeFields = holeRecords[0].split("").toMutableList()
        holeFields[0] = "notanumber" // corrupt hole 1's par
        holeRecords[0] = holeFields.joinToString("")
        val corruptHoles = holeRecords.joinToString("")
        assertNull(decodeRound(meta, playersRaw, corruptHoles))
    }

    // ---- Round history -------------------------------------------------------------------------
    //
    // Stored as flat key/value pairs (an index plus each round's three pieces). These tests go
    // through encodeHistory's own output rather than hard-coding its key names.

    private fun sampleHistory(): List<SavedRound> {
        val newer = SavedRound("200", 1_757_600_000_000, reduce(sampleInProgressRound(), RoundAction.Finish))
        val olderCourse = Course("c2", "Maple Hill", 1, listOf(4))
        val older = SavedRound("100", 1_757_500_000_000, reduce(newRound(olderCourse, listOf(Player("p1", "Derek"))), RoundAction.Finish))
        return listOf(newer, older)
    }

    private fun decodeHistoryFrom(stored: Map<String, String>): List<SavedRound> = decodeHistory { stored[it] }

    @Test
    fun `a history of several rounds survives an encode-decode round trip in order`() {
        val history = sampleHistory()
        assertEquals(history, decodeHistoryFrom(encodeHistory(history)))
    }

    @Test
    fun `an empty history round trips to empty`() {
        assertEquals(emptyList<SavedRound>(), decodeHistoryFrom(encodeHistory(emptyList())))
    }

    @Test
    fun `absent stored history decodes to an empty list, not a crash`() {
        assertEquals(emptyList<SavedRound>(), decodeHistory { null })
    }

    @Test
    fun `a saved round with a corrupt piece is dropped, and the other rounds still load`() {
        val (newer, older) = sampleHistory()
        val stored = encodeHistory(listOf(newer, older)).toMutableMap()
        val newerHolesKey = stored.entries.single { it.value == encodeHoles(newer.round.holes) }.key
        stored[newerHolesKey] = "notanumber"

        assertEquals(listOf(older), decodeHistoryFrom(stored))
    }

    @Test
    fun `malformed history index records are dropped rather than crashing the app`() {
        val history = sampleHistory()
        val stored = encodeHistory(history).toMutableMap()
        val indexKey = stored.entries.single { it.value.startsWith("200") }.key
        stored[indexKey] = listOf(
            "123", // blank id
            "300notanumber", // non-numeric finish time
            "onlyonefield", // wrong field count (< 5)
            "9991", // well-formed, but no round is stored under that id
            stored.getValue(indexKey),
        ).joinToString("")

        assertEquals(history, decodeHistoryFrom(stored))
    }

    /** Replaces the field at [index] in an encoded meta string, for malformed-input tests. */
    private fun withField(encoded: String, index: Int, value: String): String {
        val parts = encoded.split("").toMutableList()
        parts[index] = value
        return parts.joinToString("")
    }
}

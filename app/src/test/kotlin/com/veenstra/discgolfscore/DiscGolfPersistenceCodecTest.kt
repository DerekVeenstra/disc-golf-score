package com.veenstra.discgolfscore

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pure serialization used by DataStoreDiscGolfRepository. Kept free of Android imports
 * precisely so this — including every malformed-input case — can be covered without an emulator.
 * See PLAN.md section 4 "Persistence": U+001F (Unit Separator) between fields, U+001E (Record
 * Separator) between records, plain commas only inside digits-only fields, and (added for
 * layouts, PLAN.md section 2 "Layouts") U+001D (Group Separator) between the layout records
 * nested inside one course record.
 *
 * [US]/[RS]/[GS] below are written with explicit `\u` escapes — rather than the invisible literal
 * control characters the app's own file-private `FIELD_SEP`/`RECORD_SEP`/`GROUP_SEP` constants use
 * — purely so this file stays legible and editable; they're the identical characters at runtime.
 */
class DiscGolfPersistenceCodecTest {

    private val US = "" // Unit Separator
    private val RS = "" // Record Separator
    private val GS = "" // Group Separator, between a course's layouts

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
        val sanitized = sanitizeName("Derek${US}Jr.")
        val player = Player(id = "1", name = sanitized)
        assertEquals(listOf(player), decodePlayers(encodePlayers(listOf(player))))
    }

    @Test
    fun `malformed player records are dropped rather than crashing the app`() {
        val valid = Player("2", "Sam")
        val raw = listOf(
            "NoId", // blank id
            "1", // blank name
            "onlyonefield", // wrong field count (< 5)
            listOf(valid.id, valid.name).joinToString(US),
        ).joinToString(RS)
        assertEquals(listOf(valid), decodePlayers(raw))
    }

    // ---- Courses / Layouts ------------------------------------------------------------------------

    @Test
    fun `a single-layout course survives an encode-decode round trip`() {
        val layout = Layout(id = "l1", name = "18 holes", holeCount = 3, pars = listOf(3, 4, 5))
        val course = Course(id = "1", name = "Riverside", layouts = listOf(layout))
        assertEquals(listOf(course), decodeCourses(encodeCourses(listOf(course))))
    }

    @Test
    fun `a multi-layout course survives an encode-decode round trip, layout order intact`() {
        val course = Course(
            id = "1",
            name = "Columbia Lake",
            layouts = listOf(
                Layout("l1", "9 short reds", 9, List(9) { 3 }),
                Layout("l2", "18 long blues", 18, List(18) { 4 }),
            ),
        )
        assertEquals(course, decodeCourses(encodeCourses(listOf(course))).single())
    }

    @Test
    fun `multiple courses, including one with an unlearned layout, survive an encode-decode round trip`() {
        val courses = listOf(
            Course("1", "Riverside", listOf(Layout("l1", "18 holes", 3, listOf(3, 4, 5)))),
            Course("2", "Maple Hill", listOf(Layout("l2", "9 holes", 2, listOf(0, 0)))), // freshly created, nothing learned yet
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
    fun `a course name and layout name containing commas and colons round trip exactly`() {
        val layout = Layout("l1", "18 long blues: the good tees", 1, listOf(3))
        val course = Course("1", "Riverside, Round 2: The Comeback", listOf(layout))
        assertEquals(listOf(course), decodeCourses(encodeCourses(listOf(course))))
    }

    @Test
    fun `a course or layout name containing the codec's own separator characters is stored stripped, and still round trips`() {
        val sanitizedCourseName = sanitizeName("Riverside${RS}Park")
        val sanitizedLayoutName = sanitizeName("18${US}holes")
        val course = Course("1", sanitizedCourseName, listOf(Layout("l1", sanitizedLayoutName, 1, listOf(3))))
        assertEquals(listOf(course), decodeCourses(encodeCourses(listOf(course))))
    }

    @Test
    fun `a layout with a single record holder survives an encode-decode round trip`() {
        val layout = Layout("l1", "18 holes", 3, listOf(3, 4, 5), recordHolderNames = listOf("Derek"), recordToPar = -5)
        val course = Course("1", "Riverside", listOf(layout))
        assertEquals(listOf(course), decodeCourses(encodeCourses(listOf(course))))
    }

    @Test
    fun `a layout with a tied record survives an encode-decode round trip, holder order intact`() {
        val layout = Layout("l1", "18 holes", 3, listOf(3, 4, 5), recordHolderNames = listOf("Derek", "Sam", "Alex"), recordToPar = -2)
        val course = Course("1", "Riverside", listOf(layout))
        assertEquals(listOf(course), decodeCourses(encodeCourses(listOf(course))))
    }

    @Test
    fun `a layout with no record round trips with empty holders and a null to-par`() {
        val layout = Layout("l1", "18 holes", 3, listOf(3, 4, 5))
        val course = Course("1", "Riverside", listOf(layout))
        val decoded = decodeCourses(encodeCourses(listOf(course))).single().layouts.single()
        assertEquals(emptyList<String>(), decoded.recordHolderNames)
        assertNull(decoded.recordToPar)
    }

    @Test
    fun `a record holder name containing a comma round trips exactly, since names aren't comma-joined`() {
        val layout = Layout("l1", "18 holes", 1, listOf(3), recordHolderNames = listOf("Derek, Jr."), recordToPar = -5)
        val course = Course("1", "Riverside", listOf(layout))
        assertEquals(listOf(course), decodeCourses(encodeCourses(listOf(course))))
    }

    @Test
    fun `a record of exactly even par (0) round trips, not confused with no record`() {
        val layout = Layout("l1", "18 holes", 3, listOf(3, 4, 5), recordHolderNames = listOf("Derek"), recordToPar = 0)
        val course = Course("1", "Riverside", listOf(layout))
        assertEquals(listOf(course), decodeCourses(encodeCourses(listOf(course))))
    }

    @Test
    fun `malformed new-format course records are dropped rather than crashing the app`() {
        val valid = Course("2", "Sockeye Park", listOf(Layout("l2", "18 holes", 2, listOf(3, 4))))
        val goodLayout = listOf("l1", "18 holes", "3", "3,4,5", "").joinToString(US)
        val raw = listOf(
            listOf("C2", "", "Name", goodLayout).joinToString(US), // blank course id
            listOf("C2", "1", "", goodLayout).joinToString(US), // blank course name
            listOf("C2", "1", "Name", "").joinToString(US), // empty layoutsBlob -- a course must have >=1 layout
            listOf("C2", "1", "Name", "notenoughfields").joinToString(US), // layout with too few fields
            encodeCourses(listOf(valid)),
        ).joinToString(RS)
        assertEquals(listOf(valid), decodeCourses(raw))
    }

    @Test
    fun `a course with one malformed layout among several is dropped entirely, not partially recovered`() {
        // Same "a card missing a piece can't be trusted" call the active round already makes for a
        // corrupt hole (see the "Active round" tests below) -- one bad layout invalidates its whole
        // course rather than silently shrinking that course's layout list.
        val goodLayout = listOf("l1", "18 holes", "3", "3,4,5", "").joinToString(US)
        val badLayout = listOf("l2", "9 holes", "notanumber", "3,4,5", "").joinToString(US) // non-numeric hole count
        val raw = listOf("C2", "1", "Name", listOf(goodLayout, badLayout).joinToString(GS)).joinToString(US)
        assertEquals(emptyList<Course>(), decodeCourses(raw))
    }

    // ---- Migration: pre-layouts course records auto-wrap into one layout --------------------------
    //
    // PLAN.md section 2 "Migration": every course saved before this feature existed is the old flat
    // shape (`id`[US]`name`[US]`holeCount`[US]`pars`[US]`recordToPar`[US]`holder`..., no layout
    // nesting at all). These tests hand-encode that exact old shape directly, rather than calling
    // any function from the current codec, since nothing in the current app ever writes this shape
    // again -- see DiscGolfRepository.kt's decodeLegacyCourseAndWrap.

    private fun encodeLegacyCourse(
        id: String,
        name: String,
        holeCount: Int,
        pars: List<Int>,
        recordToPar: Int? = null,
        recordHolderNames: List<String> = emptyList(),
    ): String =
        (listOf(id, name, holeCount.toString(), pars.joinToString(","), recordToPar?.toString() ?: "") + recordHolderNames)
            .joinToString(US)

    @Test
    fun `a pre-layouts course with 3 holes wraps into one layout named 3 holes`() {
        val raw = encodeLegacyCourse("100", "Riverside", 3, listOf(3, 4, 5))
        val decoded = decodeCourses(raw).single()
        assertEquals("100", decoded.id)
        assertEquals("Riverside", decoded.name)
        val layout = decoded.layouts.single()
        assertEquals("3 holes", layout.name)
        assertEquals(3, layout.holeCount)
        assertEquals(listOf(3, 4, 5), layout.pars)
        assertEquals(emptyList<String>(), layout.recordHolderNames)
        assertNull(layout.recordToPar)
    }

    @Test
    fun `the migrated layout reuses the course's own id`() {
        val raw = encodeLegacyCourse("101", "Riverside", 1, listOf(4))
        assertEquals("101", decodeCourses(raw).single().layouts.single().id)
    }

    @Test
    fun `a pre-layouts course with 18 holes wraps into a layout named 18 holes`() {
        val raw = encodeLegacyCourse("101", "Maple Hill", 18, List(18) { 0 })
        assertEquals("18 holes", decodeCourses(raw).single().layouts.single().name)
    }

    @Test
    fun `a pre-layouts course with a single hole wraps into a layout named 1 hole`() {
        val raw = encodeLegacyCourse("102", "Tiny", 1, listOf(0))
        assertEquals("1 hole", decodeCourses(raw).single().layouts.single().name)
    }

    @Test
    fun `a pre-layouts course with unlearned holes wraps with those holes still unlearned`() {
        val raw = encodeLegacyCourse("103", "Maple Hill", 2, listOf(0, 0))
        assertEquals(listOf(0, 0), decodeCourses(raw).single().layouts.single().pars)
    }

    @Test
    fun `a pre-layouts course's record wraps onto the generated layout untouched`() {
        val raw = encodeLegacyCourse("104", "Riverside", 3, listOf(3, 4, 5), recordToPar = -5, recordHolderNames = listOf("Derek", "Sam"))
        val layout = decodeCourses(raw).single().layouts.single()
        assertEquals(-5, layout.recordToPar)
        assertEquals(listOf("Derek", "Sam"), layout.recordHolderNames)
    }

    @Test
    fun `a mix of pre-layouts and current-format courses in the same stored string both decode correctly`() {
        val legacy = encodeLegacyCourse("200", "Old Course", 2, listOf(4, 4))
        val current = Course("300", "New Course", listOf(Layout("l1", "9 short reds", 9, List(9) { 3 })))
        val raw = listOf(legacy, encodeCourses(listOf(current))).joinToString(RS)

        val decoded = decodeCourses(raw)
        assertEquals(2, decoded.size)
        assertEquals(defaultLayoutName(2), decoded[0].layouts.single().name)
        assertEquals(current, decoded[1])
    }

    @Test
    fun `a malformed pre-layouts course record is dropped rather than crashing the app`() {
        val valid = encodeLegacyCourse("2", "Sockeye Park", 2, listOf(3, 4))
        val raw = listOf(
            listOf("", "Name", "3", "3,4,5", "").joinToString(US), // blank id
            listOf("1", "", "3", "3,4,5", "").joinToString(US), // blank name
            listOf("1", "Name", "notanumber", "3,4,5", "").joinToString(US), // non-numeric hole count
            listOf("1", "Name", "0", "", "").joinToString(US), // non-positive hole count
            listOf("1", "Name", "3", "3,4", "").joinToString(US), // par list length disagrees with hole count
            listOf("1", "Name", "3", "3,x,5", "").joinToString(US), // non-numeric par
            "onlyonefield", // wrong field count (< 5)
            valid,
        ).joinToString(RS)
        val decoded = decodeCourses(raw)
        assertEquals(1, decoded.size)
        assertEquals("2", decoded.single().id)
    }

    @Test
    fun `a pre-layouts course record with a non-numeric recordToPar is dropped`() {
        val valid = encodeLegacyCourse("2", "Sockeye Park", 2, listOf(3, 4))
        val raw = listOf(
            listOf("1", "Name", "3", "3,4,5", "notanumber").joinToString(US),
            valid,
        ).joinToString(RS)
        val decoded = decodeCourses(raw)
        assertEquals(1, decoded.size)
        assertEquals("2", decoded.single().id)
    }

    // ---- Active round --------------------------------------------------------------------------
    //
    // The round is stored as three pieces (meta, players, holes) rather than one string -- see
    // DiscGolfRepository.kt's "Active round" comment for why. [encodeRound] returns all three as
    // a Triple purely for these tests' convenience; [decodeRound] takes them back the same way.

    private fun sampleLayout(): Layout = Layout(id = "layout-1", name = "18 long blues", holeCount = 3, pars = listOf(3, 0, 5))
    private fun sampleCourse(layout: Layout): Course = Course(id = "course-1", name = "Riverside", layouts = listOf(layout))

    private fun sampleInProgressRound(): RoundState {
        val layout = sampleLayout()
        val course = sampleCourse(layout)
        val players = listOf(Player("p1", "Derek"), Player("p2", "Sam"))
        var round = newRound(course, layout, players)
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
    fun `a round with no layout (layoutId null) survives an encode-decode round trip`() {
        val round = sampleInProgressRound().copy(layoutId = null)
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
    fun `a round referencing a layout id that no longer exists still decodes cleanly`() {
        val round = sampleInProgressRound().copy(layoutId = "a-deleted-layout-id")
        assertEquals(round, roundTrip(round))
    }

    @Test
    fun `a round whose course name contains commas, colons, and the codec's own separators round trips`() {
        val sanitizedName = sanitizeName("Riverside, Round2: The Comeback${US}${RS}")
        val round = sampleInProgressRound().copy(courseName = sanitizedName)
        assertEquals(round, roundTrip(round))
    }

    @Test
    fun `a round whose layout name contains commas, colons, and the codec's own separators round trips`() {
        val sanitizedName = sanitizeName("18 long blues, tee 2: the good pins${US}${RS}")
        val round = sampleInProgressRound().copy(layoutName = sanitizedName)
        assertEquals(round, roundTrip(round))
    }

    @Test
    fun `a single-hole, single-player round round trips`() {
        val layout = Layout("l1", "1 hole", 1, listOf(3))
        val course = Course("c1", "Short", listOf(layout))
        val round = newRound(course, layout, listOf(Player("p1", "Derek")))
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
    fun `a round meta saved before layouts existed (4 fields, no layout) decodes with a blank layout`() {
        // PLAN.md section 2 "RoundState snapshots the layout": "old saved rounds must still
        // display sensibly (course name only, no layout line)". Reproduces the exact pre-layouts
        // encodeRoundMeta shape by hand, since nothing in the current app ever writes it again.
        val legacyMeta = listOf("course-1", "Riverside", "2", "0").joinToString(US)
        val (_, playersRaw, holesRaw) = encodeRound(sampleInProgressRound())
        val decoded = decodeRound(legacyMeta, playersRaw, holesRaw)
        assertEquals("course-1", decoded!!.courseId)
        assertEquals("Riverside", decoded.courseName)
        assertNull(decoded.layoutId)
        assertEquals("", decoded.layoutName)
    }

    @Test
    fun `malformed round meta decodes the whole round to null rather than crashing the app`() {
        val valid = sampleInProgressRound()
        val (meta, playersRaw, holesRaw) = encodeRound(valid)

        // Wrong field count (neither 4 nor 6).
        assertNull(decodeRound("onlytwofields", playersRaw, holesRaw))
        assertNull(decodeRound(withField(meta, 0, "x").let { it + US + "extra" }, playersRaw, holesRaw))

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
        val holeRecords = holesRaw.split(RS).toMutableList()
        val holeFields = holeRecords[0].split(US).toMutableList()
        holeFields[0] = "notanumber" // corrupt hole 1's par
        holeRecords[0] = holeFields.joinToString(US)
        val corruptHoles = holeRecords.joinToString(RS)
        assertNull(decodeRound(meta, playersRaw, corruptHoles))
    }

    // ---- Round history -------------------------------------------------------------------------
    //
    // Stored as flat key/value pairs (an index plus each round's three pieces). These tests go
    // through encodeHistory's own output rather than hard-coding its key names.

    private fun sampleHistory(): List<SavedRound> {
        val newer = SavedRound("200", 1_757_600_000_000, reduce(sampleInProgressRound(), RoundAction.Finish))
        val olderLayout = Layout("layout-2", "18 holes", 1, listOf(4))
        val olderCourse = Course("c2", "Maple Hill", listOf(olderLayout))
        val older = SavedRound(
            "100",
            1_757_500_000_000,
            reduce(newRound(olderCourse, olderLayout, listOf(Player("p1", "Derek"))), RoundAction.Finish),
        )
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
        val indexKey = stored.entries.single { it.value.startsWith("200") }.key
        stored[indexKey] = listOf(
            listOf("", "123").joinToString(US), // blank id
            listOf("300", "notanumber").joinToString(US), // non-numeric finish time
            "onlyonefield", // wrong field count (< 2)
            listOf("9991", "500").joinToString(US), // well-formed, but no round is stored under that id
            stored.getValue(indexKey),
        ).joinToString(RS)

        assertEquals(history, decodeHistoryFrom(stored))
    }

    /** Replaces the field at [index] in an encoded meta string, for malformed-input tests. */
    private fun withField(encoded: String, index: Int, value: String): String {
        val parts = encoded.split(US).toMutableList()
        parts[index] = value
        return parts.joinToString(US)
    }
}

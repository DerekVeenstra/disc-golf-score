package com.veenstra.discgolfscore

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Exercises RoundViewModel's roster management (players, courses) and the par write-back that
 * ties an in-progress/finished round back to its course — all against in-memory fakes, no Android
 * framework or real DataStore involved. Same approach as ultimate-score's ScoreViewModelPresetTest.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RoundViewModelRosterTest {

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun idSequence(): () -> String {
        var next = 0
        return { "id-${next++}" }
    }

    // ---- Players -------------------------------------------------------------------------------

    @Test
    fun `both roster lists start empty with no stores`() {
        val vm = RoundViewModel()
        assertTrue(vm.players.value.isEmpty())
        assertTrue(vm.courses.value.isEmpty())
    }

    @Test
    fun `a persisted player list loads into state immediately`() {
        val store = FakePlayerStore(initial = listOf(Player("1", "Derek")))
        val vm = RoundViewModel(playerStore = store)
        assertEquals("Derek", vm.players.value.single().name)
    }

    @Test
    fun `adding a player updates state, persists, and returns the created player`() {
        val store = FakePlayerStore()
        val vm = RoundViewModel(playerStore = store, idGenerator = idSequence(), colorGenerator = { 0xFF112233L })

        val created = vm.addPlayer("Derek")

        assertEquals(Player("id-0", "Derek", 0xFF112233L), created)
        assertEquals(listOf(created), vm.players.value)
        assertEquals(listOf(created), store.saved)
    }

    @Test
    fun `each added player gets a fresh color from colorGenerator`() {
        val store = FakePlayerStore()
        var next = 0xFF000000L
        val vm = RoundViewModel(playerStore = store, idGenerator = idSequence(), colorGenerator = { next++ })

        val derek = vm.addPlayer("Derek")!!
        val sam = vm.addPlayer("Sam")!!

        assertEquals(0xFF000000L, derek.color)
        assertEquals(0xFF000001L, sam.color)
    }

    @Test
    fun `adding a blank-named player is a no-op`() {
        val store = FakePlayerStore()
        val vm = RoundViewModel(playerStore = store, idGenerator = idSequence())

        val created = vm.addPlayer("   ")

        assertNull(created)
        assertTrue(vm.players.value.isEmpty())
    }

    @Test
    fun `a player name is sanitized on the way in`() {
        val store = FakePlayerStore()
        val vm = RoundViewModel(playerStore = store, idGenerator = idSequence())

        val created = vm.addPlayer("  Derek, Jr.  ")

        assertEquals("Derek, Jr.", created!!.name)
    }

    @Test
    fun `renaming a player updates state and persists, leaving its id alone`() {
        val store = FakePlayerStore()
        val vm = RoundViewModel(playerStore = store, idGenerator = idSequence())
        val original = vm.addPlayer("Derek")!!

        vm.renamePlayer(original.id, "Derek V.")

        val renamed = vm.players.value.single()
        assertEquals(original.id, renamed.id)
        assertEquals("Derek V.", renamed.name)
        assertEquals(listOf(renamed), store.saved)
    }

    @Test
    fun `renaming to a blank name is a no-op`() {
        val store = FakePlayerStore()
        val vm = RoundViewModel(playerStore = store, idGenerator = idSequence())
        val original = vm.addPlayer("Derek")!!

        vm.renamePlayer(original.id, "   ")

        assertEquals(original, vm.players.value.single())
    }

    @Test
    fun `deleting a player removes it from state and persists the shrunk list`() {
        val store = FakePlayerStore()
        val vm = RoundViewModel(playerStore = store, idGenerator = idSequence())
        val keep = vm.addPlayer("Derek")!!
        val remove = vm.addPlayer("Sam")!!

        vm.deletePlayer(remove.id)

        assertEquals(listOf(keep), vm.players.value)
        assertEquals(listOf(keep), store.saved)
    }

    @Test
    fun `deleting an unknown player id is a no-op`() {
        val store = FakePlayerStore()
        val vm = RoundViewModel(playerStore = store, idGenerator = idSequence())
        val kept = vm.addPlayer("Derek")!!

        vm.deletePlayer("not-a-real-id")

        assertEquals(listOf(kept), vm.players.value)
    }

    // ---- Courses -------------------------------------------------------------------------------

    @Test
    fun `adding a course creates it with every hole unlearned`() {
        val store = FakeCourseStore()
        val vm = RoundViewModel(courseStore = store, idGenerator = idSequence())

        val created = vm.addCourse("Riverside", 3)

        assertEquals(Course("id-0", "Riverside", 3, listOf(0, 0, 0)), created)
        assertEquals(listOf(created), vm.courses.value)
        assertEquals(listOf(created), store.saved)
    }

    @Test
    fun `adding a course with a non-positive hole count is a no-op`() {
        val store = FakeCourseStore()
        val vm = RoundViewModel(courseStore = store, idGenerator = idSequence())

        assertNull(vm.addCourse("Riverside", 0))
        assertTrue(vm.courses.value.isEmpty())
    }

    @Test
    fun `renaming a course updates state and persists, leaving its pars alone`() {
        val store = FakeCourseStore()
        val vm = RoundViewModel(courseStore = store, idGenerator = idSequence())
        val original = vm.addCourse("Riverside", 2)!!

        vm.renameCourse(original.id, "Riverside Park")

        val renamed = vm.courses.value.single()
        assertEquals("Riverside Park", renamed.name)
        assertEquals(original.pars, renamed.pars)
        assertEquals(listOf(renamed), store.saved)
    }

    @Test
    fun `deleting a course removes it from state and persists the shrunk list`() {
        val store = FakeCourseStore()
        val vm = RoundViewModel(courseStore = store, idGenerator = idSequence())
        val keep = vm.addCourse("Riverside", 3)!!
        val remove = vm.addCourse("Maple Hill", 2)!!

        vm.deleteCourse(remove.id)

        assertEquals(listOf(keep), vm.courses.value)
        assertEquals(listOf(keep), store.saved)
    }

    // ---- Par write-back (PLAN.md section 4 "Par write-back to a course happens here too") -----

    @Test
    fun `par write-back teaches an unlearned, reached hole's par to its course`() {
        val courseStore = FakeCourseStore()
        val vm = RoundViewModel(courseStore = courseStore, idGenerator = idSequence())
        val course = vm.addCourse("Riverside", 3)!! // pars [0, 0, 0]
        val players = listOf(Player("p1", "Derek"))

        vm.startRound(course, players)
        vm.setPar(4) // hole 1, unlearned, reached -- should teach par 4 back

        val updated = vm.courses.value.single { it.id == course.id }
        assertEquals(listOf(4, 0, 0), updated.pars)
        assertEquals(updated, courseStore.saved.single())
    }

    @Test
    fun `par write-back does not fire for a hole the round never reached`() {
        val courseStore = FakeCourseStore()
        val vm = RoundViewModel(courseStore = courseStore, idGenerator = idSequence())
        val course = vm.addCourse("Riverside", 3)!! // pars [0, 0, 0]
        val players = listOf(Player("p1", "Derek"))

        vm.startRound(course, players)
        vm.finishRound() // finished on hole 1 -- holes 2 and 3 never reached

        val updated = vm.courses.value.single { it.id == course.id }
        assertEquals(listOf(3, 0, 0), updated.pars) // only hole 1 (reached, default par 3) learned
    }

    @Test
    fun `par write-back does not re-teach a hole the course already knew at round start`() {
        val courseStore = FakeCourseStore(initial = listOf(Course("c1", "Riverside", 2, listOf(4, 5))))
        val vm = RoundViewModel(courseStore = courseStore, idGenerator = idSequence())
        val course = vm.courses.value.single()
        val players = listOf(Player("p1", "Derek"))

        vm.startRound(course, players)
        vm.setPar(3) // changing an already-learned hole's par mid-round -- round-only, per "learn once"

        val updated = vm.courses.value.single()
        assertEquals(listOf(4, 5), updated.pars) // unchanged -- the course never re-learns hole 1
    }

    @Test
    fun `par write-back no-ops entirely if the round's course has since been deleted`() {
        val courseStore = FakeCourseStore()
        val vm = RoundViewModel(courseStore = courseStore, idGenerator = idSequence())
        val course = vm.addCourse("Riverside", 3)!!
        val players = listOf(Player("p1", "Derek"))

        vm.startRound(course, players)
        vm.deleteCourse(course.id) // course gone mid-round -- the round keeps its snapshot courseId

        val saveCountBeforeSetPar = courseStore.saveCount
        vm.setPar(4) // would otherwise teach hole 1's par back

        assertTrue(vm.courses.value.none { it.id == course.id }) // still deleted
        assertEquals(saveCountBeforeSetPar, courseStore.saveCount) // no extra course save happened
    }

    @Test
    fun `a round plays and scores normally to the end after its course is deleted mid-round`() {
        // PLAN.md section 3 "Final scoreboard" / section 11 #15: "round plays and scores off its own
        // snapshot; write-back no-ops". The test above only checks the write-back half; this checks
        // the round itself keeps working end to end -- adjust, PrevHole/NextHole, Finish, and the
        // scoreboard ranking -- once its course is gone, not just that no crash happens on the next
        // dispatch. Uses a real 3-hole course so NextHole/PrevHole/Finish all get exercised.
        val courseStore = FakeCourseStore()
        val vm = RoundViewModel(courseStore = courseStore, idGenerator = idSequence())
        val course = vm.addCourse("Riverside", 3)!!
        val derek = Player("p1", "Derek")
        val sam = Player("p2", "Sam")
        vm.startRound(course, listOf(derek, sam))

        vm.deleteCourse(course.id) // gone mid-round; the round keeps its own snapshot

        vm.adjust(derek.id, -1) // hole 1: Derek touched, strokes 3-1=2
        vm.setPar(5) // hole 1 par -> 5 (would-be write-back, already covered as a no-op above);
        // Derek stays at 2 (touched, keeps what the human put there), Sam (untouched) follows to 5
        vm.nextHole() // hole 2
        vm.adjust(sam.id, 1) // hole 2: Sam touched, strokes 3+1=4; Derek stays untouched at 3
        vm.nextHole() // hole 3
        vm.prevHole() // back to hole 2
        vm.nextHole() // forward again -- hole 3
        vm.nextHole() // last hole -- no-op, still hole 3
        vm.finishRound()

        val finished = vm.round.value
        requireNotNull(finished)
        assertTrue(finished.finished)
        assertEquals("Riverside", finished.courseName) // snapshot survives the course's deletion
        assertEquals(course.id, finished.courseId) // courseId kept, even though it no longer resolves

        // The score itself is right: hole 1 Derek=2, Sam=5; hole 2 Derek=3 (untouched default),
        // Sam=4; hole 3 both untouched default par 3. Totals: Derek 2+3+3=8, Sam 5+4+3=12.
        assertEquals(8, finished.strokesThrough(derek.id))
        assertEquals(12, finished.strokesThrough(sam.id))

        // The final scoreboard (what FinalScoreboardScreen renders) works off this same snapshot,
        // with no reference back to the course list at all.
        val rows = finished.scoreboard()
        assertEquals(2, rows.size)
        assertEquals("Derek", rows[0].player.name)
        assertEquals(1, rows[0].rank)
        assertEquals("Sam", rows[1].player.name)
        assertEquals(2, rows[1].rank)

        // And the course really is gone -- this isn't accidentally passing because deleteCourse no-op'd.
        assertTrue(vm.courses.value.none { it.id == course.id })
    }

    @Test
    fun `par write-back applies across multiple holes as the round advances`() {
        val courseStore = FakeCourseStore()
        val vm = RoundViewModel(courseStore = courseStore, idGenerator = idSequence())
        val course = vm.addCourse("Riverside", 3)!! // pars [0, 0, 0]
        val players = listOf(Player("p1", "Derek"))
        vm.startRound(course, players)

        vm.setPar(4) // hole 1 -> 4
        vm.nextHole()
        vm.setPar(5) // hole 2 -> 5
        vm.nextHole()
        // hole 3 left untouched -- learns the default par 3 once reached (PLAN.md section 4).

        val updated = vm.courses.value.single { it.id == course.id }
        assertEquals(listOf(4, 5, 3), updated.pars)
    }
}

private class FakePlayerStore(initial: List<Player> = emptyList()) : PlayerStore {
    var saved: List<Player> = initial
        private set

    override suspend fun loadPlayers(): List<Player> = saved

    override suspend fun savePlayers(players: List<Player>) {
        saved = players
    }
}

private class FakeCourseStore(initial: List<Course> = emptyList()) : CourseStore {
    var saved: List<Course> = initial
        private set
    var saveCount: Int = 0
        private set

    override suspend fun loadCourses(): List<Course> = saved

    override suspend fun saveCourses(courses: List<Course>) {
        saved = courses
        saveCount++
    }
}

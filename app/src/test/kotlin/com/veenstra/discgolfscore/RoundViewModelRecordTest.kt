package com.veenstra.discgolfscore

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Exercises the course record: [RoundViewModel.finishRound]'s automatic write-back (via
 * [recordAfterRound]) plus [RoundViewModel.justSetRecord], and the manual corrections
 * [CourseEditorScreen] drives ([RoundViewModel.setCourseRecordHolders] /
 * [RoundViewModel.setCourseRecordToPar]) — all against an in-memory fake, same approach as
 * RoundViewModelRosterTest's par write-back tests.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RoundViewModelRecordTest {

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

    // ---- Automatic write-back on finish ---------------------------------------------------------

    @Test
    fun `finishing the first full round on a course sets the record and flags justSetRecord`() {
        val courseStore = RecordFakeCourseStore()
        val vm = RoundViewModel(courseStore = courseStore, idGenerator = idSequence())
        val course = vm.addCourse("Riverside", 1)!!
        val derek = Player("p1", "Derek")
        vm.startRound(course, listOf(derek))

        vm.finishRound()

        val updated = vm.courses.value.single { it.id == course.id }
        assertEquals(listOf("Derek"), updated.recordHolderNames)
        assertEquals(0, updated.recordToPar) // 1 hole, default par 3, untouched -- even par
        assertTrue(vm.justSetRecord.value)
    }

    @Test
    fun `a round that does not beat the standing record leaves it alone and does not flag justSetRecord`() {
        val courseStore = RecordFakeCourseStore(
            initial = listOf(Course("c1", "Riverside", 1, listOf(4), recordHolderNames = listOf("Sam"), recordToPar = -1)),
        )
        val vm = RoundViewModel(courseStore = courseStore, idGenerator = idSequence())
        val course = vm.courses.value.single()
        val derek = Player("p1", "Derek")
        vm.startRound(course, listOf(derek))

        vm.finishRound() // Derek cards a 4 (untouched, par 4, so even par) -- worse than the existing −1

        val updated = vm.courses.value.single { it.id == course.id }
        assertEquals(listOf("Sam"), updated.recordHolderNames)
        assertEquals(-1, updated.recordToPar)
        assertFalse(vm.justSetRecord.value)
    }

    @Test
    fun `a round tying the standing record adds a holder and flags justSetRecord`() {
        val courseStore = RecordFakeCourseStore(
            initial = listOf(Course("c1", "Riverside", 1, listOf(3), recordHolderNames = listOf("Sam"), recordToPar = 0)),
        )
        val vm = RoundViewModel(courseStore = courseStore, idGenerator = idSequence())
        val course = vm.courses.value.single()
        val derek = Player("p1", "Derek")
        vm.startRound(course, listOf(derek))

        vm.finishRound() // Derek cards a 3 (untouched, par 3, so even par) -- ties the existing record

        val updated = vm.courses.value.single { it.id == course.id }
        assertEquals(listOf("Sam", "Derek"), updated.recordHolderNames)
        assertTrue(vm.justSetRecord.value)
    }

    @Test
    fun `finishing a round early never sets a record`() {
        val courseStore = RecordFakeCourseStore()
        val vm = RoundViewModel(courseStore = courseStore, idGenerator = idSequence())
        val course = vm.addCourse("Riverside", 3)!!
        val derek = Player("p1", "Derek")
        vm.startRound(course, listOf(derek))

        vm.finishRound() // finished on hole 1 of 3 -- never reached the whole card

        val updated = vm.courses.value.single { it.id == course.id }
        assertTrue(updated.recordHolderNames.isEmpty())
        assertNull(updated.recordToPar)
        assertFalse(vm.justSetRecord.value)
    }

    @Test
    fun `record write-back no-ops entirely if the round's course has since been deleted`() {
        val courseStore = RecordFakeCourseStore()
        val vm = RoundViewModel(courseStore = courseStore, idGenerator = idSequence())
        val course = vm.addCourse("Riverside", 1)!!
        vm.startRound(course, listOf(Player("p1", "Derek")))
        vm.deleteCourse(course.id)

        vm.finishRound()

        assertTrue(vm.courses.value.none { it.id == course.id })
        assertFalse(vm.justSetRecord.value)
    }

    @Test
    fun `justSetRecord resets when a new round starts`() {
        val courseStore = RecordFakeCourseStore()
        val vm = RoundViewModel(courseStore = courseStore, idGenerator = idSequence())
        val course = vm.addCourse("Riverside", 1)!!
        vm.startRound(course, listOf(Player("p1", "Derek")))
        vm.finishRound()
        assertTrue(vm.justSetRecord.value)

        vm.done()
        assertFalse(vm.justSetRecord.value)

        vm.startRound(course, listOf(Player("p1", "Derek")))
        assertFalse(vm.justSetRecord.value)
    }

    // ---- Manual correction: holder names -----------------------------------------------------

    @Test
    fun `setCourseRecordHolders sets holders and seeds an even-par default when there was none`() {
        val courseStore = RecordFakeCourseStore()
        val vm = RoundViewModel(courseStore = courseStore, idGenerator = idSequence())
        val course = vm.addCourse("Riverside", 6)!!

        vm.setCourseRecordHolders(course.id, "Derek")

        val updated = vm.courses.value.single()
        assertEquals(listOf("Derek"), updated.recordHolderNames)
        assertEquals(0, updated.recordToPar)
    }

    @Test
    fun `setCourseRecordHolders splits and sanitizes a comma-separated tie`() {
        val courseStore = RecordFakeCourseStore()
        val vm = RoundViewModel(courseStore = courseStore, idGenerator = idSequence())
        val course = vm.addCourse("Riverside", 3)!!

        vm.setCourseRecordHolders(course.id, "  Derek , Sam ,, Derek ")

        assertEquals(listOf("Derek", "Sam"), vm.courses.value.single().recordHolderNames)
    }

    @Test
    fun `setCourseRecordHolders preserves an existing to-par rather than reseeding it`() {
        val courseStore = RecordFakeCourseStore(
            initial = listOf(Course("c1", "Riverside", 3, listOf(0, 0, 0), recordHolderNames = listOf("Sam"), recordToPar = -5)),
        )
        val vm = RoundViewModel(courseStore = courseStore, idGenerator = idSequence())
        val course = vm.courses.value.single()

        vm.setCourseRecordHolders(course.id, "Derek")

        val updated = vm.courses.value.single()
        assertEquals(listOf("Derek"), updated.recordHolderNames)
        assertEquals(-5, updated.recordToPar) // untouched
    }

    @Test
    fun `setCourseRecordHolders with a blank result clears the record entirely`() {
        val courseStore = RecordFakeCourseStore(
            initial = listOf(Course("c1", "Riverside", 3, listOf(0, 0, 0), recordHolderNames = listOf("Sam"), recordToPar = -5)),
        )
        val vm = RoundViewModel(courseStore = courseStore, idGenerator = idSequence())
        val course = vm.courses.value.single()

        vm.setCourseRecordHolders(course.id, "   ")

        val updated = vm.courses.value.single()
        assertTrue(updated.recordHolderNames.isEmpty())
        assertNull(updated.recordToPar)
    }

    @Test
    fun `setCourseRecordHolders for an unknown course id is a no-op`() {
        val courseStore = RecordFakeCourseStore()
        val vm = RoundViewModel(courseStore = courseStore, idGenerator = idSequence())
        val course = vm.addCourse("Riverside", 3)!!

        vm.setCourseRecordHolders("not-a-real-id", "Derek")

        assertEquals(course, vm.courses.value.single())
    }

    // ---- Manual correction: to-par -----------------------------------------------------------

    @Test
    fun `setCourseRecordToPar adjusts the to-par once holders exist`() {
        val courseStore = RecordFakeCourseStore(
            initial = listOf(Course("c1", "Riverside", 3, listOf(0, 0, 0), recordHolderNames = listOf("Derek"), recordToPar = -5)),
        )
        val vm = RoundViewModel(courseStore = courseStore, idGenerator = idSequence())
        val course = vm.courses.value.single()

        vm.setCourseRecordToPar(course.id, -8) // within 3 holes * (1-5)..(15-3) = -12..36

        assertEquals(-8, vm.courses.value.single().recordToPar)
    }

    @Test
    fun `setCourseRecordToPar clamps to the whole-round to-par range`() {
        val courseStore = RecordFakeCourseStore(
            initial = listOf(Course("c1", "Riverside", 3, listOf(0, 0, 0), recordHolderNames = listOf("Derek"), recordToPar = -5)),
        )
        val vm = RoundViewModel(courseStore = courseStore, idGenerator = idSequence())
        val course = vm.courses.value.single()

        vm.setCourseRecordToPar(course.id, -999) // below 3 holes * (MIN_STROKES(1) - MAX_PAR(5)) = -12
        assertEquals(-12, vm.courses.value.single().recordToPar)

        vm.setCourseRecordToPar(course.id, 999) // above 3 holes * (MAX_STROKES(15) - MIN_PAR(3)) = 36
        assertEquals(36, vm.courses.value.single().recordToPar)
    }

    @Test
    fun `setCourseRecordToPar is a no-op when the course has no holders yet`() {
        val courseStore = RecordFakeCourseStore()
        val vm = RoundViewModel(courseStore = courseStore, idGenerator = idSequence())
        val course = vm.addCourse("Riverside", 3)!!

        vm.setCourseRecordToPar(course.id, -5)

        assertNull(vm.courses.value.single().recordToPar)
    }
}

private class RecordFakeCourseStore(initial: List<Course> = emptyList()) : CourseStore {
    var saved: List<Course> = initial
        private set

    override suspend fun loadCourses(): List<Course> = saved

    override suspend fun saveCourses(courses: List<Course>) {
        saved = courses
    }
}

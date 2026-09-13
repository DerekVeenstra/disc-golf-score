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
 * Exercises the layout record (PLAN.md section 2 "Layouts" — the record moved from [Course] onto
 * [Layout]): [RoundViewModel.finishRound]'s automatic write-back (via [recordAfterRound]) plus
 * [RoundViewModel.justSetRecord] — against an in-memory fake, same approach as
 * RoundViewModelRosterTest's par write-back tests. There's no manual-correction path any more
 * (PLAN.md section 2 "Layout record editing removed") — a wrong record is fixed from the Google
 * Sheet cloud saves write to instead.
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

    /** The single layout of whatever course [vm.addCourse] most recently created. */
    private fun RoundViewModel.layoutOf(course: Course): Layout =
        courses.value.single { it.id == course.id }.layouts.single()

    // ---- Automatic write-back on finish ---------------------------------------------------------

    @Test
    fun `finishing the first full round on a layout sets the record and flags justSetRecord`() {
        val courseStore = RecordFakeCourseStore()
        val vm = RoundViewModel(courseStore = courseStore, idGenerator = idSequence())
        val course = vm.addCourse("Riverside", 1)!!
        val layout = vm.layoutOf(course)
        val derek = Player("p1", "Derek")
        vm.startRound(course, layout, listOf(derek))

        vm.finishRound()

        val updated = vm.layoutOf(course)
        assertEquals(listOf("Derek"), updated.recordHolderNames)
        assertEquals(0, updated.recordToPar) // 1 hole, default par 3, untouched -- even par
        assertTrue(vm.justSetRecord.value)
    }

    @Test
    fun `a round that does not beat the standing record leaves it alone and does not flag justSetRecord`() {
        val layout = Layout("l1", "18 holes", 1, listOf(4), recordHolderNames = listOf("Sam"), recordToPar = -1)
        val courseStore = RecordFakeCourseStore(initial = listOf(Course("c1", "Riverside", listOf(layout))))
        val vm = RoundViewModel(courseStore = courseStore, idGenerator = idSequence())
        val course = vm.courses.value.single()
        val derek = Player("p1", "Derek")
        vm.startRound(course, vm.layoutOf(course), listOf(derek))

        vm.finishRound() // Derek cards a 4 (untouched, par 4, so even par) -- worse than the existing −1

        val updated = vm.layoutOf(course)
        assertEquals(listOf("Sam"), updated.recordHolderNames)
        assertEquals(-1, updated.recordToPar)
        assertFalse(vm.justSetRecord.value)
    }

    @Test
    fun `a round tying the standing record adds a holder and flags justSetRecord`() {
        val layout = Layout("l1", "18 holes", 1, listOf(3), recordHolderNames = listOf("Sam"), recordToPar = 0)
        val courseStore = RecordFakeCourseStore(initial = listOf(Course("c1", "Riverside", listOf(layout))))
        val vm = RoundViewModel(courseStore = courseStore, idGenerator = idSequence())
        val course = vm.courses.value.single()
        val derek = Player("p1", "Derek")
        vm.startRound(course, vm.layoutOf(course), listOf(derek))

        vm.finishRound() // Derek cards a 3 (untouched, par 3, so even par) -- ties the existing record

        val updated = vm.layoutOf(course)
        assertEquals(listOf("Sam", "Derek"), updated.recordHolderNames)
        assertTrue(vm.justSetRecord.value)
    }

    @Test
    fun `a round on a sibling layout of the same course never touches this layout's record`() {
        val vm = RoundViewModel(courseStore = RecordFakeCourseStore(), idGenerator = idSequence())
        val course = vm.addCourse("Columbia Lake", 1)!!
        val firstLayout = vm.layoutOf(course)
        val sibling = vm.addLayout(course.id, "9 short reds", 1)!!
        val derek = Player("p1", "Derek")

        vm.startRound(course, sibling, listOf(derek))
        vm.finishRound() // a great score, but on the sibling layout, not firstLayout

        val updatedFirst = vm.courses.value.single().layouts.single { it.id == firstLayout.id }
        assertTrue(updatedFirst.recordHolderNames.isEmpty())
        assertNull(updatedFirst.recordToPar)
    }

    @Test
    fun `finishing a round early never sets a record`() {
        val courseStore = RecordFakeCourseStore()
        val vm = RoundViewModel(courseStore = courseStore, idGenerator = idSequence())
        val course = vm.addCourse("Riverside", 3)!!
        val derek = Player("p1", "Derek")
        vm.startRound(course, vm.layoutOf(course), listOf(derek))

        vm.finishRound() // finished on hole 1 of 3 -- never reached the whole card

        val updated = vm.layoutOf(course)
        assertTrue(updated.recordHolderNames.isEmpty())
        assertNull(updated.recordToPar)
        assertFalse(vm.justSetRecord.value)
    }

    @Test
    fun `record write-back no-ops entirely if the round's course has since been deleted`() {
        val courseStore = RecordFakeCourseStore()
        val vm = RoundViewModel(courseStore = courseStore, idGenerator = idSequence())
        val course = vm.addCourse("Riverside", 1)!!
        vm.startRound(course, vm.layoutOf(course), listOf(Player("p1", "Derek")))
        vm.deleteCourse(course.id)

        vm.finishRound()

        assertTrue(vm.courses.value.none { it.id == course.id })
        assertFalse(vm.justSetRecord.value)
    }

    @Test
    fun `record write-back no-ops if the round's layout was deleted mid-round but the course remains`() {
        val vm = RoundViewModel(courseStore = RecordFakeCourseStore(), idGenerator = idSequence())
        val course = vm.addCourse("Columbia Lake", 1)!!
        val firstLayout = vm.layoutOf(course)
        vm.addLayout(course.id, "9 short reds", 1) // a second layout, so the first can be deleted
        vm.startRound(course, firstLayout, listOf(Player("p1", "Derek")))

        vm.deleteLayout(course.id, firstLayout.id)
        vm.finishRound()

        assertFalse(vm.justSetRecord.value)
    }

    @Test
    fun `justSetRecord resets when a new round starts`() {
        val courseStore = RecordFakeCourseStore()
        val vm = RoundViewModel(courseStore = courseStore, idGenerator = idSequence())
        val course = vm.addCourse("Riverside", 1)!!
        val layout = vm.layoutOf(course)
        vm.startRound(course, layout, listOf(Player("p1", "Derek")))
        vm.finishRound()
        assertTrue(vm.justSetRecord.value)

        vm.done()
        assertFalse(vm.justSetRecord.value)

        vm.startRound(course, layout, listOf(Player("p1", "Derek")))
        assertFalse(vm.justSetRecord.value)
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

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
 * Exercises RoundViewModel saving finished rounds to [RoundHistoryStore] (Home's `PAST ROUNDS`)
 * against an in-memory fake — same approach as RoundViewModelPersistenceTest.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RoundViewModelHistoryTest {

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private val course = Course(id = "c1", name = "Riverside", holeCount = 3, pars = listOf(3, 4, 5))
    private val players = listOf(Player("p1", "Derek"), Player("p2", "Sam"))

    private fun idSequence(): () -> String {
        var next = 0
        return { "id-${next++}" }
    }

    private fun clockSequence(vararg times: Long): () -> Long {
        var next = 0
        return { times[next++] }
    }

    @Test
    fun `history starts empty with no store`() {
        assertTrue(RoundViewModel().history.value.isEmpty())
    }

    @Test
    fun `a persisted history loads into state`() {
        val saved = SavedRound("r1", 1000L, reduce(newRound(course, players), RoundAction.Finish))
        val vm = RoundViewModel(historyStore = FakeRoundHistoryStore(initial = listOf(saved)))
        assertEquals(listOf(saved), vm.history.value)
    }

    @Test
    fun `finishing a round saves the finished card to history with its finish time, and persists it`() {
        val store = FakeRoundHistoryStore()
        val vm = RoundViewModel(historyStore = store, idGenerator = idSequence(), clock = { 1234L })
        vm.startRound(course, players)
        vm.adjust("p1", 1)

        vm.finishRound()

        val saved = vm.history.value.single()
        assertEquals(SavedRound(id = "id-0", finishedAt = 1234L, round = vm.round.value!!), saved)
        assertTrue(saved.round.finished)
        assertEquals(4, saved.round.strokesThrough("p1"))
        assertEquals(listOf(saved), store.saved)
    }

    @Test
    fun `a round in progress is not in history`() {
        val store = FakeRoundHistoryStore()
        val vm = RoundViewModel(historyStore = store, idGenerator = idSequence())

        vm.startRound(course, players)
        vm.adjust("p1", 1)
        vm.nextHole()

        assertTrue(vm.history.value.isEmpty())
        assertTrue(store.saved.isEmpty())
    }

    @Test
    fun `finishing an already-finished round does not save it twice`() {
        val vm = RoundViewModel(historyStore = FakeRoundHistoryStore(), idGenerator = idSequence())
        vm.startRound(course, players)

        vm.finishRound()
        vm.finishRound()

        assertEquals(1, vm.history.value.size)
    }

    @Test
    fun `a finished round restored from disk is not saved again`() {
        // It was already saved to history when it was first finished, before the relaunch.
        val finished = reduce(newRound(course, players), RoundAction.Finish)
        val roundStore = object : RoundStore {
            override suspend fun loadRound(): RoundState = finished
            override suspend fun saveRound(round: RoundState) {}
            override suspend fun clearRound() {}
        }
        val vm = RoundViewModel(roundStore = roundStore, historyStore = FakeRoundHistoryStore())

        vm.finishRound()

        assertTrue(vm.history.value.isEmpty())
    }

    @Test
    fun `finishing with no active round saves nothing`() {
        val vm = RoundViewModel(historyStore = FakeRoundHistoryStore())
        vm.finishRound()
        assertTrue(vm.history.value.isEmpty())
    }

    @Test
    fun `the newest round comes first`() {
        val store = FakeRoundHistoryStore()
        val vm = RoundViewModel(historyStore = store, idGenerator = idSequence(), clock = clockSequence(1000L, 2000L))

        vm.startRound(course, players)
        vm.finishRound()
        vm.done()
        vm.startRound(course, players.take(1))
        vm.finishRound()

        assertEquals(listOf(2000L, 1000L), vm.history.value.map { it.finishedAt })
        assertEquals(vm.history.value, store.saved)
    }

    @Test
    fun `DONE clears the active round but keeps it in history`() {
        val store = FakeRoundHistoryStore()
        val vm = RoundViewModel(historyStore = store, idGenerator = idSequence())
        vm.startRound(course, players)
        vm.finishRound()

        vm.done()

        assertNull(vm.round.value)
        assertEquals("Riverside", vm.history.value.single().round.courseName)
        assertEquals(1, store.saved.size)
    }

    @Test
    fun `deleting a saved round removes only it, and persists`() {
        val store = FakeRoundHistoryStore()
        val vm = RoundViewModel(historyStore = store, idGenerator = idSequence(), clock = clockSequence(1000L, 2000L))
        vm.startRound(course, players)
        vm.finishRound()
        vm.done()
        vm.startRound(course, players)
        vm.finishRound()
        val (newer, older) = vm.history.value

        vm.deleteSavedRound(newer.id)

        assertEquals(listOf(older), vm.history.value)
        assertEquals(listOf(older), store.saved)
    }

    @Test
    fun `deleting an unknown saved round id is a no-op`() {
        val vm = RoundViewModel(historyStore = FakeRoundHistoryStore(), idGenerator = idSequence())
        vm.startRound(course, players)
        vm.finishRound()
        val before = vm.history.value

        vm.deleteSavedRound("not-a-real-id")

        assertEquals(before, vm.history.value)
    }
}

private class FakeRoundHistoryStore(initial: List<SavedRound> = emptyList()) : RoundHistoryStore {
    var saved: List<SavedRound> = initial
        private set

    override suspend fun loadHistory(): List<SavedRound> = saved

    override suspend fun saveHistory(history: List<SavedRound>) {
        saved = history
    }
}

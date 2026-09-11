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
 * Exercises RoundViewModel's interaction with [RoundStore] using an in-memory fake — no Android
 * framework or real DataStore involved, same approach as ultimate-score's
 * ScoreViewModelPersistenceTest. The real DataStore-backed implementation
 * (DataStoreDiscGolfRepository) can only be verified on-device/emulator.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RoundViewModelPersistenceTest {

    @Before
    fun setUp() {
        // viewModelScope uses Dispatchers.Main; UnconfinedTestDispatcher runs launched
        // coroutines eagerly so loads/saves complete synchronously within each test.
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private val course = Course(id = "c1", name = "Riverside", holeCount = 3, pars = listOf(3, 4, 5))
    private val players = listOf(Player("p1", "Derek"), Player("p2", "Sam"))

    @Test
    fun `with no round store, is ready immediately with no round`() {
        val vm = RoundViewModel()
        assertTrue(vm.isReady.value)
        assertNull(vm.round.value)
    }

    @Test
    fun `with a round store and nothing saved, becomes ready with no round`() {
        val store = FakeRoundStore(initial = null)
        val vm = RoundViewModel(roundStore = store)
        assertTrue(vm.isReady.value)
        assertNull(vm.round.value)
    }

    @Test
    fun `loads a persisted round on start and becomes ready`() {
        val saved = newRound(course, players)
        val store = FakeRoundStore(initial = saved)
        val vm = RoundViewModel(roundStore = store)

        assertTrue(vm.isReady.value)
        assertEquals(saved, vm.round.value)
    }

    @Test
    fun `starting a round persists it`() {
        val store = FakeRoundStore()
        val vm = RoundViewModel(roundStore = store)

        vm.startRound(course, players)

        assertEquals(vm.round.value, store.saved)
    }

    @Test
    fun `every round action persists the updated state`() {
        val store = FakeRoundStore()
        val vm = RoundViewModel(roundStore = store)
        vm.startRound(course, players)

        vm.setPar(4)
        assertEquals(4, store.saved!!.holes[0].par)

        vm.adjust("p1", 1)
        assertEquals(5, store.saved!!.holes[0].strokes.getValue("p1"))

        vm.nextHole()
        assertEquals(2, store.saved!!.currentHole)

        vm.prevHole()
        assertEquals(1, store.saved!!.currentHole)
    }

    @Test
    fun `dispatching an action with no active round is a no-op`() {
        val store = FakeRoundStore()
        val vm = RoundViewModel(roundStore = store)

        vm.setPar(4)
        vm.adjust("p1", 1)
        vm.nextHole()

        assertNull(vm.round.value)
        assertNull(store.saved)
    }

    @Test
    fun `finishing persists finished true`() {
        val store = FakeRoundStore()
        val vm = RoundViewModel(roundStore = store)
        vm.startRound(course, players)

        vm.finishRound()

        assertTrue(vm.round.value!!.finished)
        assertTrue(store.saved!!.finished)
    }

    @Test
    fun `a finished round is restored as finished on next start`() {
        val finished = reduce(newRound(course, players), RoundAction.Finish)
        val store = FakeRoundStore(initial = finished)
        val vm = RoundViewModel(roundStore = store)

        assertTrue(vm.round.value!!.finished)
    }

    @Test
    fun `actions on an already-finished round are no-ops, matching the pure reducer`() {
        val store = FakeRoundStore()
        val vm = RoundViewModel(roundStore = store)
        vm.startRound(course, players)
        vm.finishRound()
        val finishedState = vm.round.value

        vm.setPar(5)
        vm.adjust("p1", 3)
        vm.nextHole()

        assertEquals(finishedState, vm.round.value)
    }

    @Test
    fun `DONE clears the round from state and storage`() {
        val store = FakeRoundStore()
        val vm = RoundViewModel(roundStore = store)
        vm.startRound(course, players)
        vm.finishRound()

        vm.done()

        assertNull(vm.round.value)
        assertNull(store.saved)
        assertTrue(store.cleared)
    }

    @Test
    fun `DONE with no round store is still safe to call`() {
        val vm = RoundViewModel()
        vm.done()
        assertNull(vm.round.value)
    }

    @Test
    fun `DONE on an in-progress (not finished) round still clears it -- DONE is not gated on finished`() {
        // Nothing in PLAN.md's model requires DONE to check `finished` itself -- it's the screen
        // flow (only the final scoreboard shows a DONE control) that ensures this only happens
        // once a round is finished. The ViewModel-level operation just clears whatever's there.
        val store = FakeRoundStore()
        val vm = RoundViewModel(roundStore = store)
        vm.startRound(course, players)

        vm.done()

        assertNull(vm.round.value)
        assertTrue(store.cleared)
    }
}

private class FakeRoundStore(private val initial: RoundState? = null) : RoundStore {
    var saved: RoundState? = initial
        private set
    var cleared: Boolean = false
        private set

    override suspend fun loadRound(): RoundState? = initial

    override suspend fun saveRound(round: RoundState) {
        saved = round
        cleared = false
    }

    override suspend fun clearRound() {
        saved = null
        cleared = true
    }
}

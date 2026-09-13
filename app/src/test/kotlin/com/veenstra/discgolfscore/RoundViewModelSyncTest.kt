package com.veenstra.discgolfscore

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
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
 * `RoundViewModel.backUpNow`/`restore`/`clearSyncConfig` against an in-memory [FakeSyncConfigStore]
 * and [FakeBackupClient] — no real HTTP, no DataStore, no device (`CLOUD_SAVES.md` section 6 Phase
 * B: "so the ViewModel paths (success, failure, not-configured, no-network) are testable"). The
 * wire envelope itself is covered in [BackupEnvelopeTest]; [HttpBackupClient]'s actual redirect
 * handling can only be verified on-device (Phase E).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RoundViewModelSyncTest {

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private val player = Player(id = "1", name = "Derek")
    private val config = SyncConfig(url = "https://script.google.com/macros/s/abc/exec", secret = "s3cr3t", lastSyncAt = null)

    private fun viewModel(
        syncConfigStore: SyncConfigStore? = FakeSyncConfigStore(config),
        client: BackupClient = FakeBackupClient(),
        playerStore: PlayerStore? = null,
        courseStore: CourseStore? = null,
        historyStore: RoundHistoryStore? = null,
    ) = RoundViewModel(
        playerStore = playerStore,
        courseStore = courseStore,
        historyStore = historyStore,
        syncConfigStore = syncConfigStore,
        backupClientFactory = { _, _ -> client },
    )

    // ---- Not configured ---------------------------------------------------------------------------

    @Test
    fun `backUpNow with no sync config store is not-configured, no client ever built`() {
        val client = FakeBackupClient()
        val vm = viewModel(syncConfigStore = null, client = client)

        vm.backUpNow()

        assertEquals(SyncStatus.Failure(NOT_CONFIGURED_MESSAGE), vm.syncStatus.value)
        assertTrue(!client.pushCalled)
    }

    @Test
    fun `backUpNow with a store holding nothing configured is not-configured`() {
        val client = FakeBackupClient()
        val vm = viewModel(syncConfigStore = FakeSyncConfigStore(initial = null), client = client)

        vm.backUpNow()

        assertEquals(SyncStatus.Failure(NOT_CONFIGURED_MESSAGE), vm.syncStatus.value)
        assertTrue(!client.pushCalled)
    }

    @Test
    fun `restore with nothing configured is not-configured, no client ever built`() {
        val client = FakeBackupClient()
        val vm = viewModel(syncConfigStore = FakeSyncConfigStore(initial = null), client = client)

        vm.restore()

        assertEquals(SyncStatus.Failure(NOT_CONFIGURED_MESSAGE), vm.syncStatus.value)
        assertTrue(!client.pullCalled)
    }

    // ---- Success ------------------------------------------------------------------------------------

    @Test
    fun `backUpNow success reports the formatted summary and stamps lastSyncAt`() {
        val configStore = FakeSyncConfigStore(config)
        val counts = SyncCounts(players = 1, courses = 0, rounds = 0)
        val client = FakeBackupClient(pushResult = PushResult.Success(counts, emptyList()))
        val vm = viewModel(syncConfigStore = configStore, client = client, playerStore = SyncFakePlayerStore(listOf(player)))

        vm.backUpNow()

        assertEquals(SyncStatus.Success(formatPushSummary(PushResult.Success(counts, emptyList()))), vm.syncStatus.value)
        assertTrue(client.pushCalled)
        assertEquals(listOf(player), client.lastPushedData?.players)
    }

    @Test
    fun `backUpNow sends the current players, courses, and history, never the active round`() {
        val course = Course(id = "c1", name = "Riverside", layouts = listOf(Layout("l1", "9 holes", 9, List(9) { 0 })))
        val history = listOf(SavedRound(id = "r1", finishedAt = 1L, round = newRound(course, course.layouts.single(), listOf(player))))
        val client = FakeBackupClient()
        val vm = viewModel(
            client = client,
            playerStore = SyncFakePlayerStore(listOf(player)),
            courseStore = SyncFakeCourseStore(listOf(course)),
            historyStore = SyncFakeHistoryStore(history),
        )

        vm.backUpNow()

        val sent = client.lastPushedData!!
        assertEquals(listOf(player), sent.players)
        assertEquals(listOf(course), sent.courses)
        assertEquals(history, sent.rounds)
    }

    @Test
    fun `restore success merges the pulled data into local state and persists it`() {
        val remotePlayer = Player(id = "2", name = "Sam")
        val pulled = BackupData(players = listOf(remotePlayer), courses = emptyList(), rounds = emptyList())
        val client = FakeBackupClient(pullResult = PullResult.Success(pulled, emptyList()))
        val playerStore = SyncFakePlayerStore(listOf(player))
        val vm = viewModel(client = client, playerStore = playerStore)

        vm.restore()

        assertEquals(listOf(player, remotePlayer), vm.players.value)
        assertEquals(listOf(player, remotePlayer), playerStore.saved)
        assertTrue(vm.syncStatus.value is SyncStatus.Success)
    }

    @Test
    fun `a successful sync reloads syncConfig so the status line's lastSyncAt updates`() {
        val configStore = FakeSyncConfigStore(config)
        val vm = viewModel(syncConfigStore = configStore, client = FakeBackupClient())

        vm.backUpNow()

        assertEquals(configStore.recordedSyncAt, vm.syncConfig.value?.lastSyncAt)
        assertTrue(vm.syncConfig.value?.lastSyncAt != null)
    }

    // ---- Failure (including "no network") ------------------------------------------------------------

    @Test
    fun `backUpNow surfaces a BackupClient failure message verbatim`() {
        val client = FakeBackupClient(pushResult = PushResult.Failure("No network"))
        val vm = viewModel(client = client)

        vm.backUpNow()

        assertEquals(SyncStatus.Failure("No network"), vm.syncStatus.value)
    }

    @Test
    fun `restore surfaces a BackupClient failure message verbatim and leaves local state untouched`() {
        val client = FakeBackupClient(pullResult = PullResult.Failure("No network"))
        val playerStore = SyncFakePlayerStore(listOf(player))
        val vm = viewModel(client = client, playerStore = playerStore)

        vm.restore()

        assertEquals(SyncStatus.Failure("No network"), vm.syncStatus.value)
        assertEquals(listOf(player), vm.players.value) // untouched -- merge never ran
        assertEquals(null, playerStore.saved) // never re-persisted on failure
    }

    @Test
    fun `a failed sync does not stamp lastSyncAt`() {
        val configStore = FakeSyncConfigStore(config)
        val client = FakeBackupClient(pushResult = PushResult.Failure("unauthorized"))
        val vm = viewModel(syncConfigStore = configStore, client = client)

        vm.backUpNow()

        assertEquals(null, configStore.recordedSyncAt)
        assertEquals(null, vm.syncConfig.value?.lastSyncAt)
    }

    // ---- Live config updates (bug: config was read once at init and never refreshed) -----------------

    /**
     * Reproduces the on-device bug: `SyncConfigReceiver`'s `adb` broadcast writes the config from a
     * different process entry point *while the app is already running* (`CLOUD_SAVES.md`'s
     * documented setup order requires launching the app before broadcasting). A `RoundViewModel` that
     * only reads [SyncConfigStore] once at construction never sees that write, and the Cloud screen
     * is stuck on "Not configured" until a force-stop and relaunch. This test writes the config
     * *after* the ViewModel is constructed, through the store directly (not through any
     * [RoundViewModel] method — [FakeSyncConfigStore.writeExternally] stands in for the receiver),
     * and asserts [RoundViewModel.syncConfig] reflects it with no re-construction. It fails against a
     * one-shot `loadConfig()` read and passes once the config is observed via [SyncConfigStore.configFlow].
     */
    @Test
    fun `syncConfig reflects a config written after construction, with no ViewModel re-construction`() {
        val configStore = FakeSyncConfigStore(initial = null)
        val vm = viewModel(syncConfigStore = configStore)

        assertNull(vm.syncConfig.value) // nothing configured yet, same as a fresh install

        configStore.writeExternally(config) // the broadcast landing while the Cloud screen might already be open

        assertEquals(config, vm.syncConfig.value)
    }

    // ---- In-progress / clear -----------------------------------------------------------------------

    @Test
    fun `clearSyncConfig clears both the in-memory config and the store, and resets status to idle`() {
        val configStore = FakeSyncConfigStore(config)
        val vm = viewModel(syncConfigStore = configStore)
        vm.backUpNow() // leaves a non-Idle status behind

        vm.clearSyncConfig()

        assertNull(vm.syncConfig.value)
        assertTrue(configStore.cleared)
        assertEquals(SyncStatus.Idle, vm.syncStatus.value)
    }

    @Test
    fun `sync status starts Idle before anything is attempted`() {
        val vm = viewModel()
        assertEquals(SyncStatus.Idle, vm.syncStatus.value)
    }
}

/**
 * Backed by a [MutableStateFlow] rather than a plain `var`, so [configFlow] behaves like the real
 * [DataStoreSyncConfigStore]: every write is visible to whatever's already collecting, with no
 * separate re-read step. [writeExternally] stands in for [SyncConfigReceiver] writing straight to
 * DataStore from its own process entry point, independent of any [RoundViewModel] method call —
 * exactly the write [RoundViewModelSyncTest]'s live-update test needs to simulate.
 */
private class FakeSyncConfigStore(initial: SyncConfig?) : SyncConfigStore {
    private val state = MutableStateFlow(initial)
    var cleared: Boolean = false
        private set
    var recordedSyncAt: Long? = null
        private set

    override fun configFlow(): Flow<SyncConfig?> = state

    override suspend fun saveConfig(url: String, secret: String) {
        state.value = SyncConfig(url = url, secret = secret, lastSyncAt = null)
        cleared = false
    }

    override suspend fun clearConfig() {
        state.value = null
        cleared = true
    }

    override suspend fun recordSyncAt(epochMillis: Long) {
        recordedSyncAt = epochMillis
        state.update { it?.copy(lastSyncAt = epochMillis) }
    }

    /** Simulates [SyncConfigReceiver] writing a config while the app (and this store) is already running, with no [RoundViewModel] method involved at all. */
    fun writeExternally(config: SyncConfig) {
        state.value = config
    }
}

private class FakeBackupClient(
    private val pushResult: PushResult = PushResult.Success(SyncCounts(0, 0, 0), emptyList()),
    private val pullResult: PullResult = PullResult.Success(BackupData(emptyList(), emptyList(), emptyList()), emptyList()),
) : BackupClient {
    var pushCalled: Boolean = false
        private set
    var pullCalled: Boolean = false
        private set
    var lastPushedData: BackupData? = null
        private set

    override suspend fun push(data: BackupData): PushResult {
        pushCalled = true
        lastPushedData = data
        return pushResult
    }

    override suspend fun pull(): PullResult {
        pullCalled = true
        return pullResult
    }
}

private class SyncFakePlayerStore(initial: List<Player> = emptyList()) : PlayerStore {
    var saved: List<Player>? = null
        private set
    private val initialSnapshot = initial

    override suspend fun loadPlayers(): List<Player> = initialSnapshot

    override suspend fun savePlayers(players: List<Player>) {
        saved = players
    }
}

private class SyncFakeCourseStore(initial: List<Course> = emptyList()) : CourseStore {
    private val initialSnapshot = initial
    var saved: List<Course>? = null
        private set

    override suspend fun loadCourses(): List<Course> = initialSnapshot

    override suspend fun saveCourses(courses: List<Course>) {
        saved = courses
    }
}

private class SyncFakeHistoryStore(initial: List<SavedRound> = emptyList()) : RoundHistoryStore {
    private val initialSnapshot = initial
    var saved: List<SavedRound>? = null
        private set

    override suspend fun loadHistory(): List<SavedRound> = initialSnapshot

    override suspend fun saveHistory(history: List<SavedRound>) {
        saved = history
    }
}

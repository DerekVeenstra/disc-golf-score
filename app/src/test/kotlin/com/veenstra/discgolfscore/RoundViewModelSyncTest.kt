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

private class FakeSyncConfigStore(initial: SyncConfig?) : SyncConfigStore {
    private var config: SyncConfig? = initial
    var cleared: Boolean = false
        private set
    var recordedSyncAt: Long? = null
        private set

    override suspend fun loadConfig(): SyncConfig? = config

    override suspend fun saveConfig(url: String, secret: String) {
        config = SyncConfig(url = url, secret = secret, lastSyncAt = null)
        cleared = false
    }

    override suspend fun clearConfig() {
        config = null
        cleared = true
    }

    override suspend fun recordSyncAt(epochMillis: Long) {
        recordedSyncAt = epochMillis
        config = config?.copy(lastSyncAt = epochMillis)
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

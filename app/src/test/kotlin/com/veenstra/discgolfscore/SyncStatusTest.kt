package com.veenstra.discgolfscore

import org.junit.Assert.assertEquals
import org.junit.Test

/** [formatPushSummary]/[formatPullSummary] — the Cloud screen's result-line text (`CLOUD_SAVES.md` section 6 Phase D). */
class SyncStatusTest {

    @Test
    fun `a clean push summary names each count with no warnings suffix`() {
        val result = PushResult.Success(SyncCounts(players = 4, courses = 3, rounds = 27), warnings = emptyList())
        assertEquals("Backed up 4 players · 3 courses · 27 rounds", formatPushSummary(result))
    }

    @Test
    fun `a push summary with warnings appends the skipped-row count`() {
        val result = PushResult.Success(
            SyncCounts(players = 1, courses = 1, rounds = 1),
            warnings = listOf("Courses!F14: par list length 17 ≠ hole count 18", "Players!A3: missing name"),
        )
        assertEquals("Backed up 1 players · 1 courses · 1 rounds · 2 rows skipped", formatPushSummary(result))
    }

    @Test
    fun `a clean pull summary names the round count with no warnings suffix`() {
        val data = BackupData(players = emptyList(), courses = emptyList(), rounds = List(12) { fakeRound(it) })
        val result = PullResult.Success(data, warnings = emptyList())
        assertEquals("Restored 12 rounds", formatPullSummary(result))
    }

    @Test
    fun `a pull summary with warnings matches CLOUD_SAVES section 2's own illustrative example`() {
        val data = BackupData(players = emptyList(), courses = emptyList(), rounds = List(12) { fakeRound(it) })
        val result = PullResult.Success(data, warnings = listOf("row a", "row b"))
        assertEquals("Restored 12 rounds · 2 rows skipped", formatPullSummary(result))
    }

    private fun fakeRound(index: Int) = SavedRound(
        id = index.toString(),
        finishedAt = index.toLong(),
        round = RoundState(
            courseId = null,
            courseName = "Course",
            players = emptyList(),
            holes = listOf(HoleScore(par = 3, wasLearnedAtStart = true, strokes = emptyMap())),
            currentHole = 1,
        ),
    )
}

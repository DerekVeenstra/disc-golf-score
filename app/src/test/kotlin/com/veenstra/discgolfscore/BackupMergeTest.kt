package com.veenstra.discgolfscore

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * `mergeBackup`'s rules, `CLOUD_SAVES.md` section 2: ids present on both sides take the sheet's
 * (`remote`'s) version, ids only local are kept untouched, ids only remote are added, courses merge
 * their `layouts` one level deeper under the same rule, and the merged round history re-sorts by
 * `finishedAt` descending. Round-trip/decode coverage lives in [CloudBackupCodecTest].
 */
class BackupMergeTest {

    // ---- Players: id merge, sheet wins, nothing local deleted --------------------------------------

    @Test
    fun `a player id present on both sides takes the remote (sheet) version`() {
        val local = Player(id = "1", name = "Derek", color = 0xFF1E6FD9)
        val remote = Player(id = "1", name = "Derek Veenstra", color = 0xFFD32F2F)
        val merged = mergeBackup(BackupData(listOf(local), emptyList(), emptyList()), BackupData(listOf(remote), emptyList(), emptyList()))
        assertEquals(listOf(remote), merged.players)
    }

    @Test
    fun `a player only on the watch is kept, not deleted, when the sheet doesn't have it`() {
        val localOnly = Player(id = "1", name = "Derek")
        val merged = mergeBackup(BackupData(listOf(localOnly), emptyList(), emptyList()), BackupData(emptyList(), emptyList(), emptyList()))
        assertEquals(listOf(localOnly), merged.players)
    }

    @Test
    fun `a player only in the sheet is added`() {
        val remoteOnly = Player(id = "2", name = "Sam")
        val merged = mergeBackup(BackupData(emptyList(), emptyList(), emptyList()), BackupData(listOf(remoteOnly), emptyList(), emptyList()))
        assertEquals(listOf(remoteOnly), merged.players)
    }

    @Test
    fun `players merge preserves local-only players plus sheet-only additions plus updates shared ones`() {
        val localA = Player(id = "1", name = "Derek")
        val localB = Player(id = "2", name = "Sam")
        val remoteB = Player(id = "2", name = "Samuel") // renamed in the sheet
        val remoteC = Player(id = "3", name = "Alex") // new in the sheet
        val merged = mergeBackup(
            BackupData(listOf(localA, localB), emptyList(), emptyList()),
            BackupData(listOf(remoteB, remoteC), emptyList(), emptyList()),
        )
        assertEquals(listOf(localA, remoteB, remoteC), merged.players)
    }

    // ---- Courses: id merge, sheet wins on the course's own fields -----------------------------------

    private fun layout(id: String, name: String, holeCount: Int = 9) =
        Layout(id = id, name = name, holeCount = holeCount, pars = List(holeCount) { 0 })

    @Test
    fun `a course id present on both sides takes the sheet's name`() {
        val local = Course(id = "c1", name = "Columbia Lake", layouts = listOf(layout("l1", "18 holes")))
        val remote = Course(id = "c1", name = "Columbia Lake Park", layouts = listOf(layout("l1", "18 holes")))
        val merged = mergeBackup(BackupData(emptyList(), listOf(local), emptyList()), BackupData(emptyList(), listOf(remote), emptyList()))
        assertEquals("Columbia Lake Park", merged.courses.single().name)
    }

    @Test
    fun `a course only on the watch is kept untouched`() {
        val localOnly = Course(id = "c1", name = "Riverside", layouts = listOf(layout("l1", "9 holes")))
        val merged = mergeBackup(BackupData(emptyList(), listOf(localOnly), emptyList()), BackupData(emptyList(), emptyList(), emptyList()))
        assertEquals(listOf(localOnly), merged.courses)
    }

    @Test
    fun `a course only in the sheet is added wholesale, layouts included`() {
        val remoteOnly = Course(id = "c1", name = "Riverside", layouts = listOf(layout("l1", "9 holes"), layout("l2", "18 holes", 18)))
        val merged = mergeBackup(BackupData(emptyList(), emptyList(), emptyList()), BackupData(emptyList(), listOf(remoteOnly), emptyList()))
        assertEquals(listOf(remoteOnly), merged.courses)
    }

    // ---- Layouts merge one level deeper, under the same rule -----------------------------------------

    @Test
    fun `a layout added on the watch after the last push survives merging, not deleted by the sheet's older copy`() {
        // The sheet's copy of the course only has the layout that existed at the last push.
        val nineHoleLayout = layout("l1", "9 short reds")
        val eighteenHoleLayoutAddedLocally = layout("l2", "18 long blues", 18)
        val local = Course(id = "c1", name = "Columbia Lake", layouts = listOf(nineHoleLayout, eighteenHoleLayoutAddedLocally))
        val remote = Course(id = "c1", name = "Columbia Lake", layouts = listOf(nineHoleLayout))

        val merged = mergeBackup(BackupData(emptyList(), listOf(local), emptyList()), BackupData(emptyList(), listOf(remote), emptyList()))

        val mergedCourse = merged.courses.single()
        assertEquals(setOf(nineHoleLayout.id, eighteenHoleLayoutAddedLocally.id), mergedCourse.layouts.map { it.id }.toSet())
    }

    @Test
    fun `a layout id present in both a course's local and remote copies takes the sheet's fields`() {
        val localLayout = layout("l1", "18 holes").copy(pars = List(9) { 3 })
        val remoteLayout = localLayout.copy(name = "18 long blues (fixed par)", recordToPar = -4, recordHolderNames = listOf("Derek"))
        val local = Course(id = "c1", name = "Columbia Lake", layouts = listOf(localLayout))
        val remote = Course(id = "c1", name = "Columbia Lake", layouts = listOf(remoteLayout))

        val merged = mergeBackup(BackupData(emptyList(), listOf(local), emptyList()), BackupData(emptyList(), listOf(remote), emptyList()))

        assertEquals(listOf(remoteLayout), merged.courses.single().layouts)
    }

    @Test
    fun `a layout only in the sheet's copy of a shared course is added`() {
        val sharedLayout = layout("l1", "9 holes")
        val sheetOnlyLayout = layout("l2", "18 holes", 18)
        val local = Course(id = "c1", name = "Columbia Lake", layouts = listOf(sharedLayout))
        val remote = Course(id = "c1", name = "Columbia Lake", layouts = listOf(sharedLayout, sheetOnlyLayout))

        val merged = mergeBackup(BackupData(emptyList(), listOf(local), emptyList()), BackupData(emptyList(), listOf(remote), emptyList()))

        assertEquals(listOf(sharedLayout, sheetOnlyLayout), merged.courses.single().layouts)
    }

    // ---- Rounds: id merge, sheet wins, re-sorted newest first afterward -------------------------------

    private val course = Course(id = "c1", name = "Columbia Lake", layouts = listOf(layout("l1", "18 holes", 1)))

    private fun round(id: String, finishedAt: Long, currentHole: Int = 1) = SavedRound(
        id = id,
        finishedAt = finishedAt,
        round = RoundState(
            courseId = course.id,
            courseName = course.name,
            players = emptyList(),
            holes = listOf(HoleScore(par = 3, wasLearnedAtStart = true, strokes = emptyMap())),
            currentHole = currentHole,
            finished = true,
        ),
    )

    @Test
    fun `a round id present on both sides takes the sheet's version entirely, not a field-by-field merge`() {
        val local = round(id = "r1", finishedAt = 100L, currentHole = 1)
        val remote = round(id = "r1", finishedAt = 100L, currentHole = 1).copy(finishedAt = 200L)
        val merged = mergeBackup(BackupData(emptyList(), emptyList(), listOf(local)), BackupData(emptyList(), emptyList(), listOf(remote)))
        assertEquals(listOf(remote), merged.rounds)
    }

    @Test
    fun `a round only on the watch (never pushed, or pushed then deleted in the sheet) is kept`() {
        val localOnly = round(id = "r1", finishedAt = 100L)
        val merged = mergeBackup(BackupData(emptyList(), emptyList(), listOf(localOnly)), BackupData(emptyList(), emptyList(), emptyList()))
        assertEquals(listOf(localOnly), merged.rounds)
    }

    @Test
    fun `a round only in the sheet (lost from the watch, or edited there) is restored`() {
        val remoteOnly = round(id = "r1", finishedAt = 100L)
        val merged = mergeBackup(BackupData(emptyList(), emptyList(), emptyList()), BackupData(emptyList(), emptyList(), listOf(remoteOnly)))
        assertEquals(listOf(remoteOnly), merged.rounds)
    }

    @Test
    fun `merged history is re-sorted by finishedAt descending, not left in id-merge order`() {
        val oldest = round(id = "r1", finishedAt = 100L)
        val middle = round(id = "r2", finishedAt = 200L)
        val newest = round(id = "r3", finishedAt = 300L)
        // Deliberately out of chronological order in both inputs, so a passing test can't be an
        // accident of merge order happening to already be sorted.
        val local = BackupData(emptyList(), emptyList(), listOf(oldest, newest))
        val remote = BackupData(emptyList(), emptyList(), listOf(middle))

        val merged = mergeBackup(local, remote)

        assertEquals(listOf(newest, middle, oldest), merged.rounds)
    }

    @Test
    fun `deleting a round on the watch and then restoring brings it back -- push never deletes`() {
        // Documents CLOUD_SAVES.md section 2's stated, accepted cost of "push is upsert, never
        // delete": a round removed locally reappears on the next restore because it's still in
        // the sheet. Nothing for mergeBackup itself to special-case -- this is just "a round only
        // in the sheet is restored" from the watch's point of view after a local delete.
        val deletedLocallyButStillInSheet = round(id = "r1", finishedAt = 100L)
        val merged = mergeBackup(
            local = BackupData(emptyList(), emptyList(), emptyList()),
            remote = BackupData(emptyList(), emptyList(), listOf(deletedLocallyButStillInSheet)),
        )
        assertEquals(listOf(deletedLocallyButStillInSheet), merged.rounds)
    }
}

package com.treko.core.common

import com.treko.core.common.data.AlertSnapshotStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AlertSnapshotStoreTest {

    private fun entry(name: String, size: Long, modified: Long) =
        AlertSnapshotStore.Entry("/s/$name", size, modified, AlertSnapshotStore.groupOf(name))

    @Test
    fun sampleSizeKeepsAtLeastTheTargetResolution() {
        assertEquals(1, AlertSnapshotStore.sampleSizeFor(1280, 720, 1280))
        assertEquals(1, AlertSnapshotStore.sampleSizeFor(1920, 1080, 1280))
        assertEquals(2, AlertSnapshotStore.sampleSizeFor(2560, 1440, 1280))
        assertEquals(2, AlertSnapshotStore.sampleSizeFor(1440, 3840, 1280))
        assertEquals(1, AlertSnapshotStore.sampleSizeFor(640, 480, 1280))
    }

    @Test
    fun scaledSizeFitsTheLongSideAndNeverUpscales() {
        assertEquals(1280 to 720, AlertSnapshotStore.scaledSize(1920, 1080, 1280))
        assertEquals(180 to 320, AlertSnapshotStore.scaledSize(1080, 1920, 320))
        assertEquals(640 to 480, AlertSnapshotStore.scaledSize(640, 480, 1280))
    }

    @Test
    fun fileNamesShareTheAlertGroup() {
        assertEquals("1234", AlertSnapshotStore.groupOf("1234.jpg"))
        assertEquals("1234", AlertSnapshotStore.groupOf("1234_t.jpg"))
    }

    @Test
    fun evictsWholeAlertsOldestFirstButNeverTheNewPicture() {
        val entries = listOf(
            entry("1.jpg", 40, 100), entry("1_t.jpg", 10, 100),
            entry("2.jpg", 40, 200), entry("2_t.jpg", 10, 200),
            entry("3.jpg", 40, 50), entry("3_t.jpg", 10, 50) // protected even though oldest
        )
        assertTrue(AlertSnapshotStore.pickEvictions(entries, capBytes = 150, protectedGroup = "3").isEmpty())
        val victims = AlertSnapshotStore.pickEvictions(entries, capBytes = 100, protectedGroup = "3")
        assertEquals(listOf("/s/1.jpg", "/s/1_t.jpg"), victims.map { it.path })
        val more = AlertSnapshotStore.pickEvictions(entries, capBytes = 10, protectedGroup = "3")
        assertEquals(setOf("1", "2"), more.map { it.group }.toSet())
    }
}

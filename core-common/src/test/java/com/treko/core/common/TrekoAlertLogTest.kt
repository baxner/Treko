package com.treko.core.common

import com.treko.core.common.data.AlertPersistence
import com.treko.core.common.data.TrekoAlertLog
import com.treko.core.common.model.AlertEvent
import com.treko.core.common.model.AlertFeedback
import com.treko.core.common.model.AlertLevel
import com.treko.core.common.model.AlertSource
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class TrekoAlertLogTest {

    private class MemoryPersistence(var stored: List<AlertEvent> = emptyList()) : AlertPersistence {
        var saves = 0
        override fun save(events: List<AlertEvent>) { stored = events; saves++ }
        override fun load(): List<AlertEvent> = stored
    }

    private val day = 24L * 60 * 60_000L
    private var now = 100L * day
    private val deleted = mutableListOf<String>()

    private fun attach(store: MemoryPersistence = MemoryPersistence()): MemoryPersistence {
        TrekoAlertLog.attach(store, imageDeleter = { deleted += it }, writer = { it() })
        return store
    }

    private fun alert(title: String = "Hay alguien", ts: Long = now, level: AlertLevel = AlertLevel.ALERT) =
        AlertEvent(timestampMs = ts, source = AlertSource.WATCH, level = level, title = title)

    @Before
    fun setUp() {
        TrekoAlertLog.resetForTests()
        TrekoAlertLog.clock = { now }
    }

    @After
    fun tearDown() = TrekoAlertLog.resetForTests()

    @Test
    fun recordAssignsIncreasingIdsNewestFirstAndCountsUnread() {
        val store = attach()
        val a = TrekoAlertLog.record(alert("a"))
        val b = TrekoAlertLog.record(alert("b"))
        TrekoAlertLog.record(alert("check", level = AlertLevel.NO_NEWS))
        assertTrue(b > a)
        assertEquals("check", TrekoAlertLog.events.value.first().title)
        // "No news" never counts as unread.
        assertEquals(2, TrekoAlertLog.unreadCount.value)
        assertEquals(3, store.stored.size)

        assertTrue(TrekoAlertLog.markRead(a))
        assertEquals(1, TrekoAlertLog.unreadCount.value)
        assertTrue(store.stored.first { it.id == a }.read)
    }

    @Test
    fun updateAddsDescriptionAndPictureLater() {
        attach()
        val id = TrekoAlertLog.record(alert())
        val updated = TrekoAlertLog.update(id) {
            it.copy(visionDescription = "Una persona con una caja", snapshotPath = "/x/1.jpg", thumbPath = "/x/1_t.jpg", id = 999)
        }
        assertEquals(id, updated?.id) // the id cannot change
        assertEquals("Una persona con una caja", TrekoAlertLog.get(id)?.visionDescription)
        assertEquals("/x/1.jpg", TrekoAlertLog.get(id)?.snapshotPath)
        assertNull(TrekoAlertLog.update(12345) { it.copy(title = "nope") })

        // Replacing the picture deletes the old files.
        TrekoAlertLog.update(id) { it.copy(snapshotPath = "/x/2.jpg", thumbPath = null) }
        assertEquals(listOf("/x/1.jpg", "/x/1_t.jpg"), deleted)
    }

    @Test
    fun noNewsNeverKeepsPictures() {
        attach()
        val id = TrekoAlertLog.record(alert(level = AlertLevel.NO_NEWS).copy(snapshotPath = "/x/a.jpg"))
        assertNull(TrekoAlertLog.get(id)?.snapshotPath)
        assertEquals(listOf("/x/a.jpg"), deleted)
        TrekoAlertLog.update(id) { it.copy(thumbPath = "/x/b.jpg") }
        assertNull(TrekoAlertLog.get(id)?.thumbPath)
        assertEquals(listOf("/x/a.jpg", "/x/b.jpg"), deleted)
    }

    @Test
    fun retentionDropsOldEntriesAndShortLivedNoNewsAndTheirPictures() {
        val store = MemoryPersistence(
            listOf(
                alert("old", ts = now - 15 * day).copy(id = 1, snapshotPath = "/x/old.jpg"),
                alert("recent", ts = now - 13 * day).copy(id = 2),
                alert("old check", ts = now - day - 1, level = AlertLevel.NO_NEWS).copy(id = 3),
                alert("new check", ts = now - day + 1, level = AlertLevel.NO_NEWS).copy(id = 4)
            )
        )
        attach(store)
        assertEquals(listOf("new check", "recent"), TrekoAlertLog.events.value.map { it.title })
        assertEquals(listOf("/x/old.jpg"), deleted)
        // The pruned log was written back.
        assertEquals(2, store.stored.size)

        now += day // "new check" is now older than 24 h; "recent" is exactly 14 days old
        assertEquals(1, TrekoAlertLog.prune())
        assertEquals(listOf("recent"), TrekoAlertLog.events.value.map { it.title })
    }

    @Test
    fun overTheCapNoNewsGoesFirstThenOldestAlerts() {
        val many = (1..TrekoAlertLog.MAX_EVENTS).map { i ->
            alert("a$i", ts = now - 10_000L + i).copy(id = i.toLong(), thumbPath = "/x/$i.jpg")
        }
        val check = alert("check", ts = now - 20_000L, level = AlertLevel.NO_NEWS).copy(id = 9_000L)
        attach(MemoryPersistence(many + check))
        // 501 entries: the "no news" check goes even though it is not the oldest alert.
        assertEquals(TrekoAlertLog.MAX_EVENTS, TrekoAlertLog.events.value.size)
        assertTrue(TrekoAlertLog.events.value.none { it.level == AlertLevel.NO_NEWS })

        TrekoAlertLog.record(alert("newest"))
        val titles = TrekoAlertLog.events.value.map { it.title }
        assertEquals(TrekoAlertLog.MAX_EVENTS, titles.size)
        assertEquals("newest", titles.first())
        assertFalse("a1" in titles)
        assertEquals(listOf("/x/1.jpg"), deleted)
    }

    @Test
    fun feedbackDeleteAndForgottenImages() {
        attach()
        val id = TrekoAlertLog.record(alert().copy(snapshotPath = "/x/f.jpg", thumbPath = "/x/f_t.jpg"))
        assertTrue(TrekoAlertLog.setFeedback(id, AlertFeedback.FALSE_ALARM))
        assertEquals(AlertFeedback.FALSE_ALARM, TrekoAlertLog.get(id)?.feedback)

        // The picture store evicted the full picture: the entry keeps only the thumbnail.
        TrekoAlertLog.forgetImages(listOf("/x/f.jpg"))
        assertNull(TrekoAlertLog.get(id)?.snapshotPath)
        assertEquals("/x/f_t.jpg", TrekoAlertLog.get(id)?.thumbPath)
        assertTrue(deleted.isEmpty())

        assertTrue(TrekoAlertLog.delete(id))
        assertEquals(listOf("/x/f_t.jpg"), deleted)
        assertNull(TrekoAlertLog.get(id))
        assertFalse(TrekoAlertLog.delete(id))
    }

    @Test
    fun survivesRestartWithoutReusingIds() {
        val store = attach()
        val first = TrekoAlertLog.record(alert("a"))
        TrekoAlertLog.resetForTests()
        TrekoAlertLog.clock = { now } // same clock: ids must still grow
        attach(store)
        assertEquals(listOf("a"), TrekoAlertLog.events.value.map { it.title })
        assertTrue(TrekoAlertLog.record(alert("b")) > first)
        assertEquals(2, TrekoAlertLog.markAllRead())
        assertEquals(0, TrekoAlertLog.unreadCount.value)
    }

    @Test
    fun writesAreCoalescedUntilTheWriterRuns() {
        val store = MemoryPersistence()
        val queued = mutableListOf<() -> Unit>()
        TrekoAlertLog.attach(store, writer = { queued += it })
        TrekoAlertLog.record(alert("a"))
        TrekoAlertLog.record(alert("b"))
        assertEquals(1, queued.size)
        queued.forEach { it() }
        assertEquals(1, store.saves)
        assertEquals(2, store.stored.size)

        // flush() writes at once; the queued write then has nothing left to do.
        TrekoAlertLog.record(alert("c"))
        TrekoAlertLog.flush()
        assertEquals(2, store.saves)
        queued.drop(1).forEach { it() }
        assertEquals(2, store.saves)
        assertEquals(3, store.stored.size)
    }

    @Test
    fun flushWithNothingNewDoesNotWriteAgain() {
        val store = MemoryPersistence()
        TrekoAlertLog.attach(store, writer = { })
        TrekoAlertLog.record(alert("a"))
        TrekoAlertLog.flush()
        TrekoAlertLog.flush()
        assertEquals(1, store.saves)
    }

    @Test
    fun aSlowBackgroundSaveNeverRunsAlongsideOrAfterANewerFlush() {
        val firstSaveStarted = CountDownLatch(1)
        val releaseFirstSave = CountDownLatch(1)
        val inSave = AtomicInteger(0)
        val overlapped = AtomicInteger(0)
        val store = object : AlertPersistence {
            @Volatile var stored: List<AlertEvent> = emptyList()
            val saves = AtomicInteger(0)
            override fun save(events: List<AlertEvent>) {
                if (inSave.incrementAndGet() > 1) overlapped.incrementAndGet()
                try {
                    if (saves.getAndIncrement() == 0) {
                        firstSaveStarted.countDown()
                        releaseFirstSave.await(5, TimeUnit.SECONDS)
                    }
                    stored = events
                } finally {
                    inSave.decrementAndGet()
                }
            }
            override fun load(): List<AlertEvent> = stored
        }
        val workers = Collections.synchronizedList(mutableListOf<Thread>())
        TrekoAlertLog.attach(store, writer = { task -> Thread(task).also { workers += it; it.start() } })
        // The background save of [a] starts and stalls on the disk.
        TrekoAlertLog.record(alert("a"))
        assertTrue(firstSaveStarted.await(5, TimeUnit.SECONDS))
        // Meanwhile a new entry is recorded and flushed (a timer receiver).
        TrekoAlertLog.record(alert("b"))
        val flusher = Thread { TrekoAlertLog.flush() }.also { it.start() }
        releaseFirstSave.countDown()
        flusher.join(5_000)
        synchronized(workers) { workers.toList() }.forEach { it.join(5_000) }
        assertEquals(0, overlapped.get())
        assertEquals(listOf("b", "a"), store.stored.map { it.title })
    }
}

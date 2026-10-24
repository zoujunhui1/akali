package com.zjh.akali

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import com.zjh.akali.data.AppDatabase
import com.zjh.akali.data.MarkRepository
import com.zjh.akali.data.MarkState
import com.zjh.akali.data.RemoteMark
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException

class MarkSyncTest {
    private lateinit var db: AppDatabase
    private lateinit var server: FakeServer
    private lateinit var repo: MarkRepository

    @Before
    fun setUp() {
        val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
        db = AppDatabase.create(context, null)
        server = FakeServer()
        repo = MarkRepository(db.markDao(), server)
    }

    @After
    fun tearDown() = db.close()

    private suspend fun local() = repo.marks.first().associate { (it.code to it.entryKey) to it.markState }

    @Test
    fun aMarkShowsAtOnceAndIsUploadedOnSync() = runBlocking {
        repo.set("V01-17", "31-1", MarkState.FAVORITE)
        assertEquals(MarkState.FAVORITE, local().getValue("V01-17" to "31-1"))
        assertTrue(db.markDao().find("V01-17", "31-1")!!.pending)
        assertTrue("nothing is sent before a sync", server.pushed.isEmpty())

        val rev = repo.sync(0)
        assertEquals(server.marksRev, rev)
        assertEquals(listOf(RemoteMark("V01-17", "31-1", "favorite")), server.pushed.single())
        assertFalse(db.markDao().find("V01-17", "31-1")!!.pending)

        repo.sync(rev) // nothing new to send the second time
        assertEquals(1, server.pushed.size)
    }

    @Test
    fun offlineMarksStayOnThePhoneAndGoUpLater() = runBlocking {
        repo.set("V01-17", "31-1", MarkState.MASTERED)
        server.failMarks = true
        try {
            repo.sync(0)
            org.junit.Assert.fail("sync should fail while offline")
        } catch (e: IOException) {
            // expected
        }
        assertEquals(MarkState.MASTERED, local().getValue("V01-17" to "31-1"))
        assertTrue(db.markDao().find("V01-17", "31-1")!!.pending)

        server.failMarks = false
        repo.sync(0)
        assertEquals("mastered", server.serverMarks.getValue("V01-17" to "31-1"))
        assertFalse(db.markDao().find("V01-17", "31-1")!!.pending)
    }

    @Test
    fun marksFromAnotherDeviceArriveAndRemovalsApply() = runBlocking {
        server.serverMarks["V01-17" to "31-2"] = "favorite"
        server.serverMarks["V01-17" to "31-3"] = "mastered"
        repo.sync(0)
        assertEquals(MarkState.FAVORITE, local().getValue("V01-17" to "31-2"))
        assertEquals(MarkState.MASTERED, local().getValue("V01-17" to "31-3"))

        // the other device un-favorites one; this phone follows
        server.serverMarks["V01-17" to "31-2"] = "none"
        repo.sync(1)
        assertEquals(MarkState.NONE, local().getValue("V01-17" to "31-2"))

        // switching a mark on this phone replaces the old state and is sent as a change
        repo.set("V01-17", "31-3", MarkState.FAVORITE)
        repo.sync(2)
        assertEquals("favorite", server.serverMarks.getValue("V01-17" to "31-3"))
        assertEquals(MarkState.FAVORITE, local().getValue("V01-17" to "31-3"))
    }
}

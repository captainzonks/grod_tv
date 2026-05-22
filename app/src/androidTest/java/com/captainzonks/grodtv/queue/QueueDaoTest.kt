package com.captainzonks.grodtv.queue

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Integration tests against a real (in-memory) Room database. Per repo policy
 * we avoid mocking the DB — past prod migrations have masked breakage when
 * tests ran against mocks. The in-memory variant still exercises Room's
 * codegen and the SQLite engine; the only difference is durability.
 */
@RunWith(AndroidJUnit4::class)
class QueueDaoTest {

    private lateinit var db: GrodTvDatabase
    private lateinit var dao: QueueDao
    private lateinit var npDao: NowPlayingDao

    @Before
    fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = GrodTvDatabase.buildInMemory(ctx)
        dao = db.queueDao()
        npDao = db.nowPlayingDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun pushAtEnd_returnsMonotonicallyIncreasing1BasedPositions() = runTest {
        assertEquals(1, dao.pushAtEnd("a", "A"))
        assertEquals(2, dao.pushAtEnd("b", "B"))
        assertEquals(3, dao.pushAtEnd("c", "C"))
        assertEquals(3, dao.size())
        val all = dao.list().map { it.videoId to it.position }
        assertEquals(listOf("a" to 0, "b" to 1, "c" to 2), all)
    }

    @Test
    fun popHead_returnsFifoAndReindexes() = runTest {
        dao.pushAtEnd("a", "A")
        dao.pushAtEnd("b", "B")
        dao.pushAtEnd("c", "C")

        val head = dao.popHead()
        assertNotNull(head)
        assertEquals("a", head!!.videoId)

        val after = dao.list().map { it.videoId to it.position }
        assertEquals(listOf("b" to 0, "c" to 1), after)
        assertEquals(2, dao.size())
    }

    @Test
    fun popHead_onEmpty_returnsNull() = runTest {
        assertNull(dao.popHead())
        assertEquals(0, dao.size())
    }

    @Test
    fun removeAt1Based_middleEntry_reindexesTrailing() = runTest {
        dao.pushAtEnd("a", "A")
        dao.pushAtEnd("b", "B")
        dao.pushAtEnd("c", "C")
        dao.pushAtEnd("d", "D")

        val removed = dao.removeAt1Based(2)
        assertNotNull(removed)
        assertEquals("b", removed!!.videoId)

        val after = dao.list().map { it.videoId to it.position }
        assertEquals(listOf("a" to 0, "c" to 1, "d" to 2), after)
    }

    @Test
    fun removeAt1Based_outOfRange_returnsNullAndDoesNotMutate() = runTest {
        dao.pushAtEnd("a", "A")
        dao.pushAtEnd("b", "B")

        assertNull(dao.removeAt1Based(99))
        assertNull(dao.removeAt1Based(0))
        assertEquals(2, dao.size())
    }

    @Test
    fun clear_removesAllRows() = runTest {
        dao.pushAtEnd("a", "A")
        dao.pushAtEnd("b", "B")
        dao.clear()
        assertEquals(0, dao.size())
        assertNull(dao.popHead())
    }

    @Test
    fun pushPopRepeated_keepsPositionsTight() = runTest {
        repeat(5) { i -> dao.pushAtEnd("v$i", "V$i") }
        dao.popHead()
        dao.popHead()
        dao.pushAtEnd("v5", "V5")

        val after = dao.list().map { it.videoId to it.position }
        assertEquals(listOf("v2" to 0, "v3" to 1, "v4" to 2, "v5" to 3), after)
    }

    @Test
    fun nowPlaying_setAndClear() = runTest {
        assertNull(npDao.get())
        npDao.set(NowPlayingEntity(videoId = "id1", title = "T1"))
        val got = npDao.get()
        assertNotNull(got)
        assertEquals("id1", got!!.videoId)
        assertEquals("T1", got.title)

        // Singleton: second set replaces, not appends.
        npDao.set(NowPlayingEntity(videoId = "id2", title = "T2"))
        val got2 = npDao.get()
        assertEquals("id2", got2!!.videoId)

        npDao.clear()
        assertNull(npDao.get())
    }
}

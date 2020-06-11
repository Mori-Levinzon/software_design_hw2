package il.ac.technion.cs.softwaredesign

import com.github.kittinunf.fuel.Fuel
import com.google.inject.Guice
import com.natpryce.hamkrest.*
import com.natpryce.hamkrest.assertion.assertThat
import dev.misfitlabs.kotlinguice4.getInstance
import il.ac.technion.cs.softwaredesign.il.ac.technion.cs.softwaredesign.PieceIndexStats
import io.github.vjames19.futures.jdk8.ImmediateFuture
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.unmockkObject
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.lang.IllegalArgumentException
import java.lang.IllegalStateException

class CourseTorrentHW0Test {
    private val injector = Guice.createInjector(CourseTorrentModule())
    private var torrent = injector.getInstance<CourseTorrent>()
    private var inMemoryDB = HashMap<String, ByteArray>()
    private val debian = this::class.java.getResource("/debian-10.3.0-amd64-netinst.iso.torrent").readBytes()
    private val ubuntu = this::class.java.getResource("/ubuntu-18.04.4-desktop-amd64.iso.torrent").readBytes()
    private var torrentsStorage = HashMap<String, ByteArray>()
    private var peersStorage = HashMap<String, ByteArray>()
    private var trackerStatsStorage = HashMap<String, ByteArray>()
    private var announcesStorage = HashMap<String, ByteArray>()
    private var piecesStatsStorage = HashMap<String, ByteArray>()
    private var indexedPieceStorage = HashMap<String, ByteArray>()//TODO: not really like this

    @BeforeEach
    fun `initialize CourseTorrent with mocked DB`() {
        val memoryDB = mockk<SimpleDB>()
        var key = slot<String>()
        var indexedKey = slot<Long>()
        var announcesValue = slot<List<List<String>>>()
        var peersValue = slot<List<Map<String, String>>>()
        var statsValue = slot<Map<String, Map<String, Any>>>()
        var torrentsValue = slot<Map<String, Any>>()
        var piecesStatsValue = slot<Map<Long, PieceIndexStats>>()
        var indexPieceValue = slot<ByteArray>()


        every { memoryDB.announcesCreate(capture(key), capture(announcesValue)) } answers {
            if(announcesStorage.containsKey(key.captured)) throw IllegalStateException()
            announcesStorage[key.captured] = Ben.encodeStr(announcesValue.captured).toByteArray()
            ImmediateFuture{Unit}
        }
        every { memoryDB.peersCreate(capture(key), capture(peersValue)) } answers {
            if(peersStorage.containsKey(key.captured)) throw IllegalStateException()
            peersStorage[key.captured] = Ben.encodeStr(peersValue.captured).toByteArray()
            ImmediateFuture{Unit}
        }
        every { memoryDB.torrentsCreate(capture(key), capture(torrentsValue)) } answers {
            if(torrentsStorage.containsKey(key.captured)) throw IllegalStateException()
            torrentsStorage[key.captured] = Ben.encodeStr(torrentsValue.captured).toByteArray()
            ImmediateFuture{Unit}
        }
        every { memoryDB.trackersStatsCreate(capture(key), capture(statsValue)) } answers {
            if(trackerStatsStorage.containsKey(key.captured)) throw IllegalStateException()
            trackerStatsStorage[key.captured] = Ben.encodeStr(statsValue.captured).toByteArray()
            ImmediateFuture{Unit}
        }
        every { memoryDB.piecesStatsCreate(capture(key), capture(piecesStatsValue)) } answers {
            if(piecesStatsStorage.containsKey(key.captured)) throw IllegalStateException()
            piecesStatsStorage[key.captured] = Ben.encodeStr(piecesStatsValue.captured).toByteArray()
            ImmediateFuture{Unit}
        }
        every { memoryDB.indexedPieceCreate(capture(key), capture(indexedKey), capture(indexPieceValue)) } answers {
            if(indexedPieceStorage.containsKey(key.captured+indexedKey.captured.toString())) throw IllegalStateException()
            indexedPieceStorage[key.captured+indexedKey.captured.toString()] = Ben.encodeStr(piecesStatsValue.captured).toByteArray()
            ImmediateFuture{Unit}
        }


        every { memoryDB.torrentsUpdate(capture(key), capture(torrentsValue)) } answers {
            if(!torrentsStorage.containsKey(key.captured)) throw IllegalArgumentException()
            torrentsStorage[key.captured] = Ben.encodeStr(torrentsValue.captured).toByteArray()
            ImmediateFuture{Unit}
        }
        every { memoryDB.announcesUpdate(capture(key), capture(announcesValue)) } answers {
            if(!torrentsStorage.containsKey(key.captured)) throw IllegalArgumentException()
            announcesStorage[key.captured] = Ben.encodeStr(torrentsValue.captured).toByteArray()
            ImmediateFuture{Unit}
        }
        every { memoryDB.peersUpdate(capture(key), capture(peersValue)) } answers {
            if(!peersStorage.containsKey(key.captured)) throw IllegalArgumentException()
            peersStorage[key.captured] = Ben.encodeStr(peersValue.captured).toByteArray()
            ImmediateFuture{Unit}
        }
        every { memoryDB.trackersStatsUpdate(capture(key), capture(statsValue)) } answers {
            if(!trackerStatsStorage.containsKey(key.captured)) throw IllegalArgumentException()
            trackerStatsStorage[key.captured] = Ben.encodeStr(statsValue.captured).toByteArray()
            ImmediateFuture{Unit}
        }
        every { memoryDB.piecesStatsUpdate(capture(key), capture(piecesStatsValue)) } answers {
            if(!piecesStatsStorage.containsKey(key.captured)) throw IllegalArgumentException()
            piecesStatsStorage[key.captured] = Ben.encodeStr(statsValue.captured).toByteArray()
            ImmediateFuture{Unit}
        }
        every { memoryDB.indexedPieceUpdate(capture(key), capture(indexedKey), capture(indexPieceValue)) } answers {
            if(!indexedPieceStorage.containsKey(key.captured+indexedKey.captured.toString())) throw IllegalArgumentException()
            indexedPieceStorage[key.captured+indexedKey.captured.toString()] = Ben.encodeStr(statsValue.captured).toByteArray()
            ImmediateFuture{Unit}
        }

        every { memoryDB.torrentsRead(capture(key)) } answers {
            if(!torrentsStorage.containsKey(key.captured)) throw IllegalArgumentException()
            Ben(torrentsStorage[key.captured] as ByteArray).decode() as? List<List<String>>? ?: throw IllegalArgumentException()
            ImmediateFuture{Ben(torrentsStorage[key.captured] as ByteArray).decode() as Map<String, Any>}
        }
        every { memoryDB.announcesRead(capture(key)) } answers {
            if(!announcesStorage.containsKey(key.captured)) throw IllegalArgumentException()
            Ben(announcesStorage[key.captured] as ByteArray).decode() as? List<List<String>>? ?: throw IllegalArgumentException()
            ImmediateFuture{Ben(announcesStorage[key.captured] as ByteArray).decode() as List<List<String>>}
        }
        every { memoryDB.peersRead(capture(key)) } answers {
            if(!peersStorage.containsKey(key.captured)) throw IllegalArgumentException()
            ImmediateFuture{Ben(peersStorage[key.captured] as ByteArray).decode() as? List<Map<String, String>> ?: throw IllegalArgumentException()}
        }
        every { memoryDB.trackersStatsRead(capture(key)) } answers {
            if(!trackerStatsStorage.containsKey(key.captured)) throw IllegalArgumentException()
            ImmediateFuture{Ben(trackerStatsStorage[key.captured] as ByteArray).decode() as? Map<String, Map<String, Any>> ?: throw IllegalArgumentException()}
        }
        every { memoryDB.piecesStatsRead(capture(key)) } answers {
            if(!piecesStatsStorage.containsKey(key.captured)) throw IllegalArgumentException()
            ImmediateFuture{Ben(piecesStatsStorage[key.captured] as ByteArray).decode() as? Map<Long, PieceIndexStats> ?: throw IllegalArgumentException()}
        }
        every { memoryDB.indexedPieceRead(capture(key),capture(indexedKey)) } answers {
            if(!indexedPieceStorage.containsKey(key.captured+indexedKey.captured.toString())) throw IllegalArgumentException()
            ImmediateFuture{indexedPieceStorage[key.captured+indexedKey.captured.toString()]  ?: throw IllegalArgumentException()}
        }

        every { memoryDB.torrentsDelete(capture(key)) } answers {
            torrentsStorage.remove(key.captured) ?: throw IllegalArgumentException()
            ImmediateFuture{Unit}
        }
        every { memoryDB.announcesDelete(capture(key)) } answers {
            announcesStorage.remove(key.captured) ?: throw IllegalArgumentException()
            ImmediateFuture{Unit}
        }
        every { memoryDB.peersDelete(capture(key)) } answers {
            peersStorage.remove(key.captured) ?: throw IllegalArgumentException()
            ImmediateFuture{Unit}
        }
        every { memoryDB.trackersStatsDelete(capture(key)) } answers {
            trackerStatsStorage.remove(key.captured) ?: throw IllegalArgumentException()
            ImmediateFuture{Unit}
        }
        every { memoryDB.piecesStatsDelete(capture(key)) } answers {
            piecesStatsStorage.remove(key.captured) ?: throw IllegalArgumentException()
            ImmediateFuture{Unit}
        }
        every { memoryDB.indexedPieceDelete(capture(key),capture(indexedKey)) } answers {
            piecesStatsStorage.remove(key.captured+indexedKey.captured.toString()) ?: throw IllegalArgumentException()
            ImmediateFuture{Unit}
        }
        every { memoryDB.indexedPieceDelete(capture(key),capture(indexedKey)) } answers {
            piecesStatsStorage.clear()
            ImmediateFuture{Unit}
        }
        torrent = CourseTorrent(memoryDB)
        unmockkObject(Fuel)
    }

    @Test
    fun `after load, infohash calculated correctly`() {
        val infohash = torrent.load(debian).join()

        assertThat(infohash, equalTo("5a8062c076fa85e8056451c0d9aa04349ae27909"))
    }

    @Test
    fun `load rejects invalid file`() {
        assertThrows<IllegalArgumentException> { torrent.load("invalid metainfo file".toByteArray(Charsets.UTF_8)) }
    }

    @Test
    fun `after load, can't load again`() {
        torrent.load(debian).get()

        assertThrows<IllegalStateException> { torrent.load(debian) }
    }

    @Test
    fun `after unload, can load again`() {
        val infohash = torrent.load(debian).get()

        torrent.unload(infohash).get()

        assertThat(torrent.load(debian).get(), equalTo(infohash))
    }

    @Test
    fun `can't unload a new file`() {
        assertThrows<IllegalArgumentException> { torrent.unload("infohash").get() }
    }

    @Test
    fun `can't unload a file twice`() {
        val infohash = torrent.load(ubuntu).get()

        torrent.unload(infohash)

        assertThrows<IllegalArgumentException> { torrent.unload(infohash).get() }
    }

    @Test
    fun `torrent with announce-list works correctly`() {
        val infohash = torrent.load(ubuntu).get()

        val announces = torrent.announces(infohash).join()

        assertThat(announces, allElements(hasSize(equalTo(1))))
        assertThat(announces, hasSize(equalTo(2)))
        assertThat(announces[0][0], equalTo("https://torrent.ubuntu.com/announce"))
        assertThat(announces[1][0], equalTo("https://ipv6.torrent.ubuntu.com/announce"))
    }

    @Test
    fun `torrent with announce (not list) works correctly`() {
        val infohash = torrent.load(debian).get()

        val announces = torrent.announces(infohash).join()

        assertThat(announces, allElements(hasSize(equalTo(1))))
        assertThat(announces, hasSize(equalTo(1)))
        assertThat(announces, allElements(hasElement("http://bttracker.debian.org:6969/announce")))
    }

    @Test
    fun `announces rejects unloaded torrents`() {
        val infohash = torrent.load(ubuntu).get()
        torrent.unload(infohash).get()

        assertThrows<IllegalArgumentException> { torrent.announces(infohash).join() }
    }

    @Test
    fun `announces rejects unrecognized torrents`() {
        assertThrows<IllegalArgumentException> { torrent.announces("new infohash").join() }
    }

}
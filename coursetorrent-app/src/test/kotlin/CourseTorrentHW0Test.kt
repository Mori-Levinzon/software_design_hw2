package il.ac.technion.cs.softwaredesign

import com.google.inject.Guice
import com.natpryce.hamkrest.*
import com.natpryce.hamkrest.assertion.assertThat
import dev.misfitlabs.kotlinguice4.getInstance
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
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
    private var statsStorage = HashMap<String, ByteArray>()

    @BeforeEach
    fun `initialize CourseTorrent with mocked DB`() {
        val memoryDB = mockk<SimpleDB>()
        var key = slot<String>()
        var torrentsValue = slot<List<List<String>>>()
        var peersValue = slot<List<Map<String, String>>>()
        var statsValue = slot<Map<String, Map<String, Any>>>()


        every { memoryDB.torrentsCreate(capture(key), capture(torrentsValue)) } answers {
            if(torrentsStorage.containsKey(key.captured)) throw IllegalStateException()
            torrentsStorage[key.captured] = Ben.encodeStr(torrentsValue.captured).toByteArray()
        }
        every { memoryDB.peersCreate(capture(key), capture(peersValue)) } answers {
            if(peersStorage.containsKey(key.captured)) throw IllegalStateException()
            peersStorage[key.captured] = Ben.encodeStr(peersValue.captured).toByteArray()
        }
        every { memoryDB.statsCreate(capture(key), capture(statsValue)) } answers {
            if(statsStorage.containsKey(key.captured)) throw IllegalStateException()
            statsStorage[key.captured] = Ben.encodeStr(statsValue.captured).toByteArray()
        }

        every { memoryDB.torrentsUpdate(capture(key), capture(torrentsValue)) } answers {
            if(!torrentsStorage.containsKey(key.captured)) throw IllegalArgumentException()
            torrentsStorage[key.captured] = Ben.encodeStr(torrentsValue.captured).toByteArray()
        }
        every { memoryDB.peersUpdate(capture(key), capture(peersValue)) } answers {
            if(!peersStorage.containsKey(key.captured)) throw IllegalArgumentException()
            peersStorage[key.captured] = Ben.encodeStr(peersValue.captured).toByteArray()
        }
        every { memoryDB.statsUpdate(capture(key), capture(statsValue)) } answers {
            if(!statsStorage.containsKey(key.captured)) throw IllegalArgumentException()
            statsStorage[key.captured] = Ben.encodeStr(statsValue.captured).toByteArray()
        }

        every { memoryDB.torrentsRead(capture(key)) } answers {
            if(!statsStorage.containsKey(key.captured)) throw IllegalArgumentException()
            Ben(torrentsStorage[key.captured] as ByteArray).decode() as List<List<String>>? ?: throw IllegalArgumentException()
        }
        every { memoryDB.peersRead(capture(key)) } answers {
            if(!statsStorage.containsKey(key.captured)) throw IllegalArgumentException()
            Ben(peersStorage[key.captured] as ByteArray).decode() as List<Map<String, String>>? ?: throw IllegalArgumentException()
        }
        every { memoryDB.statsRead(capture(key)) } answers {
            if(!statsStorage.containsKey(key.captured)) throw IllegalArgumentException()
            Ben(statsStorage[key.captured] as ByteArray).decode() as Map<String, Map<String, Any>>? ?: throw IllegalArgumentException()
        }

        every { memoryDB.torrentsDelete(capture(key)) } answers {
            torrentsStorage.remove(key.captured) ?: throw IllegalArgumentException()
        }
        every { memoryDB.peersDelete(capture(key)) } answers {
            peersStorage.remove(key.captured) ?: throw IllegalArgumentException()
        }
        every { memoryDB.statsDelete(capture(key)) } answers {
            statsStorage.remove(key.captured) ?: throw IllegalArgumentException()
        }
        torrent = CourseTorrent(memoryDB)
    }

    @Test
    fun `after load, infohash calculated correctly`() {
        val infohash = torrent.load(debian)

        assertThat(infohash, equalTo("5a8062c076fa85e8056451c0d9aa04349ae27909"))
    }

    @Test
    fun `load rejects invalid file`() {
        assertThrows<IllegalArgumentException> { torrent.load("invalid metainfo file".toByteArray(Charsets.UTF_8)) }
    }

    @Test
    fun `after load, can't load again`() {
        torrent.load(debian)

        assertThrows<IllegalStateException> { torrent.load(debian) }
    }

    @Test
    fun `after unload, can load again`() {
        val infohash = torrent.load(debian)

        torrent.unload(infohash)

        assertThat(torrent.load(debian), equalTo(infohash))
    }

    @Test
    fun `can't unload a new file`() {
        assertThrows<IllegalArgumentException> { torrent.unload("infohash") }
    }

    @Test
    fun `can't unload a file twice`() {
        val infohash = torrent.load(ubuntu)

        torrent.unload(infohash)

        assertThrows<IllegalArgumentException> { torrent.unload(infohash) }
    }

    @Test
    fun `torrent with announce-list works correctly`() {
        val infohash = torrent.load(ubuntu)

        val announces = torrent.announces(infohash)

        assertThat(announces, allElements(hasSize(equalTo(1))))
        assertThat(announces, hasSize(equalTo(2)))
        assertThat(announces[0][0], equalTo("https://torrent.ubuntu.com/announce"))
        assertThat(announces[1][0], equalTo("https://ipv6.torrent.ubuntu.com/announce"))
    }

    @Test
    fun `torrent with announce (not list) works correctly`() {
        val infohash = torrent.load(debian)

        val announces = torrent.announces(infohash)

        assertThat(announces, allElements(hasSize(equalTo(1))))
        assertThat(announces, hasSize(equalTo(1)))
        assertThat(announces, allElements(hasElement("http://bttracker.debian.org:6969/announce")))
    }

    @Test
    fun `announces rejects unloaded torrents`() {
        val infohash = torrent.load(ubuntu)
        torrent.unload(infohash)

        assertThrows<IllegalArgumentException> { torrent.announces(infohash) }
    }

    @Test
    fun `announces rejects unrecognized torrents`() {
        assertThrows<IllegalArgumentException> { torrent.announces("new infohash") }
    }

}
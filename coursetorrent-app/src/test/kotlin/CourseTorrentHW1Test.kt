package il.ac.technion.cs.softwaredesign

import com.github.kittinunf.fuel.Fuel
import com.github.kittinunf.fuel.core.Request
import com.github.kittinunf.fuel.core.Response
import com.github.kittinunf.fuel.core.ResponseResultOf
import com.github.kittinunf.fuel.httpGet
import com.github.kittinunf.result.Result
import com.google.inject.Guice
import com.natpryce.hamkrest.*
import com.natpryce.hamkrest.assertion.assertThat
import dev.misfitlabs.kotlinguice4.getInstance
import il.ac.technion.cs.softwaredesign.exceptions.TrackerException
import io.mockk.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.assertThrows
import java.time.Duration
import kotlin.or

class CourseTorrentHW1Test {
    private val injector = Guice.createInjector(CourseTorrentModule())
    private var torrent = injector.getInstance<CourseTorrent>()
    private val debian = this::class.java.getResource("/debian-10.3.0-amd64-netinst.iso.torrent").readBytes()
    private val ubuntu = this::class.java.getResource("/ubuntu-18.04.4-desktop-amd64.iso.torrent").readBytes()
    private val lame = this::class.java.getResource("/lame.torrent").readBytes()
    private val lame_big_list = this::class.java.getResource("/lame_big_list.torrent").readBytes()

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

        unmockkObject(Fuel)
    }

    private fun mockHttp(response: Any) {
        mockkObject(Fuel)
        val myReq = mockk<Request>()
        val res = Ben.encodeByteArray(response)
        every { myReq.response() } returns
                ResponseResultOf<ByteArray>(myReq, Response.error(), Result.success(res))
        every { any<String>().httpGet() } returns myReq
    }

    private fun mockHttpStringStartsWith(prefixes: List<Pair<String, Any>>) {
        mockkObject(Fuel)
        var stringSlot = slot<String>()
        every { capture(stringSlot).httpGet() } answers {
            val defaultReq = mockk<Request>()
            val defaultRes = Ben.encodeByteArray(mapOf("failure reason" to "Connection failed"))
            every { defaultReq.response() } returns
                    ResponseResultOf<ByteArray>(defaultReq, Response.error(), Result.success(defaultRes))
            var finalRequest = defaultReq
            for((prefix, response) in prefixes) {
                val myReq = mockk<Request>()
                val res = Ben.encodeByteArray(response)
                every { myReq.response() } returns
                        ResponseResultOf<ByteArray>(myReq, Response.error(), Result.success(res))
                if(stringSlot.captured.startsWith(prefix)) {
                    finalRequest = myReq
                }
            }
            finalRequest
        }
    }

    @Test
    fun `announce call returns an exception for wrong infohash`() {
        assertThrows<java.lang.IllegalArgumentException> {
            runWithTimeout(Duration.ofSeconds(10)){
            torrent.announce("invalid metainfo file", TorrentEvent.STARTED, 0, 0, 0) }}
    }

    @Test
    fun `announce call returns an exception for negative values for request params`() {
        val infohash = torrent.load(debian)
        mockHttp(mapOf("failure reason" to "Negative parameters"))
        assertThrows<TrackerException> {
            runWithTimeout(Duration.ofSeconds(10)){
                torrent.announce(infohash, TorrentEvent.STARTED, -1, 0, 0) }
        }
        assertThrows<TrackerException> {
            runWithTimeout(Duration.ofSeconds(10)){
                torrent.announce(infohash, TorrentEvent.STARTED, -1, -1, 0)
            }
        }
        assertThrows<TrackerException> {
            runWithTimeout(Duration.ofSeconds(10)){
                torrent.announce(infohash, TorrentEvent.STARTED, -1, 0, -1)
            }
        }
    }

    @Test
    fun `failed announce call still updates the stats DB`() {
        val infohash = torrent.load(lame) //this torrent has a bad tracker
        assertThrows<TrackerException> {
            runWithTimeout(Duration.ofSeconds(10)){
                torrent.announce(infohash, TorrentEvent.STARTED, 0, 0, 0)
            }
        }
        val stats = torrent.trackerStats(infohash)
        assert(stats.isNotEmpty())
    }

    @Test
    fun `announce list is shuffled after announce call`() {
        //with a big list, there is almost no chance the shuffled list will be the same
        //the exact probability that this test will fail is 1 / (length of list)! = 1 / (24!) = 1.6e-24
        mockHttp(mapOf("failure reason" to "Service unavailable"))
        val infohash = torrent.load(lame_big_list)
        val annouceListBefore = torrent.announces(infohash)

        assertThrows<TrackerException> {
            torrent.announce(infohash, TorrentEvent.STARTED, 0, 0, 0)
        }

        val annouceListAfter = torrent.announces(infohash)
        assertThat(annouceListBefore, equalTo(annouceListAfter).not())
        /* Assertion to verify the the announce list was shuffled */
    }

    @Test
    fun `announce request updates the peers DB`() {
        val infohash = torrent.load(debian)

        val interval = torrent.announce(infohash, TorrentEvent.STARTED, 0, 0, 0)

        val peers = torrent.knownPeers(infohash)
        assert(peers.isNotEmpty())
        /* Assertion to verify the peers list is not empty */
    }

    @Test
    fun `client announces to tracker debian`() {
        val infohash = torrent.load(debian)

        /* interval is 360 */
        val interval = torrent.announce(infohash, TorrentEvent.STARTED, 0, 0, 0)

        assertThat(interval, equalTo(900))
        /* Assertion to verify that the tracker was actually called */
    }

    @Test
    fun `correct announces updates the stats DB`() {
        val infohash = torrent.load(ubuntu)

        torrent.announce(infohash, TorrentEvent.STARTED, 0, 0, 0)

        val stats = torrent.trackerStats(infohash)
        assert(stats.isNotEmpty())
        /* Assertion to verify that the tracker was actually called */
    }

    @Test
    fun `wrong announce change stats data from Scrape type to Failure type`() {
        mockHttp(mapOf("files" to mapOf("myinfohash" to mapOf("complete" to 0, "incomplete" to 0, "downloaded" to 0))))
        val infohash = torrent.load(lame)

        /* Tracker has infohash, 0 complete, 0 downloaded, 0 incomplete, no name key */
        assertDoesNotThrow { torrent.scrape(infohash) }

        runWithTimeout(Duration.ofSeconds(10)){
            assertThat(
                torrent.trackerStats(infohash),
                equalTo(mapOf(Pair("https://127.0.0.1:8082/announce", Scrape(0, 0, 0, null) as ScrapeData)))
            )
        }

        mockHttp(mapOf("failure reason" to "invalid parameters"))

        assertThrows<TrackerException> { torrent.announce(infohash, TorrentEvent.STARTED, -1, 0, 0) }


        assert(
            torrent.trackerStats(infohash).get("https://127.0.0.1:8082/announce") is Failure
        )

        /* Assertion to verify that the tracker was actually called */    }

    @Test
    fun `after announce, client has up-to-date peer list`() {
        mockHttp(mapOf("interval" to 360, "complete" to 0, "incomplete" to 0, "tracker id" to "1234",
                "peers" to ubyteArrayOf(127u, 0u, 0u, 22u, 26u, 231u, 127u, 0u, 0u, 21u, 26u, 233u).toByteArray()))

        val infohash = torrent.load(lame)

        /* Returned peer list is: [("127.0.0.22", 6887)] */
        torrent.announce(infohash, TorrentEvent.STARTED, 0, 0, 2703360)
        /* Returned peer list is: [("127.0.0.22", 6887), ("127.0.0.21", 6889)] */
        torrent.announce(infohash, TorrentEvent.REGULAR, 0, 81920, 2621440)

        runWithTimeout(Duration.ofSeconds(10)) {
            assertThat(
                    torrent.knownPeers(infohash),
                    anyElement(has(KnownPeer::ip, equalTo("127.0.0.22")) and has(KnownPeer::port, equalTo(6887)))
            )
        }
        runWithTimeout(Duration.ofSeconds(10)) {
            assertThat(
                    torrent.knownPeers(infohash),
                    anyElement(has(KnownPeer::ip, equalTo("127.0.0.21")) and has(KnownPeer::port, equalTo(6889)))
            )
        }

        runWithTimeout(Duration.ofSeconds(10)) {
            assertThat(
                    torrent.knownPeers(infohash), equalTo(torrent.knownPeers(infohash).distinct())
            )
        }
    }

    @Test
    fun `announce handles non compact responses correctly`() {
        mockHttp(mapOf("interval" to 360, "complete" to 0, "incomplete" to 0, "tracker id" to "1234",
                "peers" to listOf(
                        mapOf("peer id" to "3", "ip" to "127.0.0.3", "port" to "133"),
                        mapOf("peer id" to "2", "ip" to "127.0.0.2", "port" to "122"),
                        mapOf("peer id" to "4", "ip" to "127.0.0.4", "port" to "144")
                )))

        val infohash = torrent.load(lame)

        torrent.announce(infohash, TorrentEvent.STARTED, 0, 0, 2703360)

        runWithTimeout(Duration.ofSeconds(10)) {
            assertThat(
                    torrent.knownPeers(infohash),
                    equalTo(listOf(
                            KnownPeer("127.0.0.2", 122, "2"),
                            KnownPeer("127.0.0.3", 133, "3"),
                            KnownPeer("127.0.0.4", 144, "4")
                    ))
            )
        }
    }

    @Test
    fun `announce updates complete and incomplete`() {
        mockHttp(mapOf("interval" to 360, "tracker id" to "1234",
                "complete" to 12,
                "incomplete" to 24,
                "peers" to listOf(
                        mapOf("peer id" to "3", "ip" to "127.0.0.3", "port" to "133")
                )))

        val infohash = torrent.load(lame)

        torrent.announce(infohash, TorrentEvent.STARTED, 0, 0, 2703360)

        runWithTimeout(Duration.ofSeconds(10)) {
            assertThat(
                    torrent.trackerStats(infohash),
                    equalTo(mapOf<String, ScrapeData>(
                            "https://127.0.0.1:8082/announce" to Scrape(12, 0, 24, null)
                    ))
            )
        }
    }

    @Test
    fun `multiple announce calls merge peers lists`() {

        val infohash = torrent.load(lame)

        mockHttp(mapOf("interval" to 360, "complete" to 0, "incomplete" to 0, "tracker id" to "1234",
                "peers" to listOf(
                        mapOf("peer id" to "3", "ip" to "127.0.0.3", "port" to "133"),
                        mapOf("peer id" to "2", "ip" to "127.0.0.2", "port" to "122"),
                        mapOf("peer id" to "4", "ip" to "127.0.0.4", "port" to "144")
                )))
        torrent.announce(infohash, TorrentEvent.STARTED, 0, 0, 2703360)

        mockHttp(mapOf("interval" to 360, "complete" to 0, "incomplete" to 0, "tracker id" to "1234",
                "peers" to listOf(
                        mapOf("peer id" to "1", "ip" to "127.0.0.1", "port" to "111"),
                        mapOf("peer id" to "2", "ip" to "127.0.0.2", "port" to "122"),
                        mapOf("peer id" to "4", "ip" to "127.0.0.4", "port" to "144")
                )))
        torrent.announce(infohash, TorrentEvent.STARTED, 0, 0, 2703360)

        runWithTimeout(Duration.ofSeconds(10)) {
            assertThat(
                    torrent.knownPeers(infohash),
                    equalTo(listOf(
                            KnownPeer("127.0.0.1", 111, "1"),
                            KnownPeer("127.0.0.2", 122, "2"),
                            KnownPeer("127.0.0.3", 133, "3"),
                            KnownPeer("127.0.0.4", 144, "4")
                    ))
            )
        }
    }

    /////////////////////////////////////

    @Test
    fun `client scrapes tracker and updates statistics`() {
        val infohash = torrent.load(ubuntu)

        /* Tracker has infohash, 0 complete, 0 downloaded, 0 incomplete, no name key */
        assertDoesNotThrow { torrent.scrape(infohash) }

        runWithTimeout(Duration.ofSeconds(10)){
            val stats = torrent.trackerStats(infohash)
            assertThat(stats.size, equalTo(2))
            assert({
                var found = false
                for (value in stats.values) {
                    found = found || value is Scrape
                }
                found
            }.invoke())
        }
        /* Assertion to verify that the tracker was actually called */
    }

    @Test
    fun `client scrapes invalid info hash throws exception`() {
        /* Tracker has infohash, 0 complete, 0 downloaded, 0 incomplete, no name key */
        assertThrows<java.lang.IllegalArgumentException> {
            runWithTimeout(Duration.ofSeconds(10)){
                torrent.scrape("wrong infohash")
            }
        }

        /* Assertion to verify that the tracker was actually called */
    }

    @Test
    fun `peers are invalidated correctly`() {
        mockHttp(mapOf("interval" to 360, "complete" to 0, "incomplete" to 0, "tracker id" to "1234",
                "peers" to ubyteArrayOf(127u, 0u, 0u, 22u, 26u, 231u, 127u, 0u, 0u, 21u, 26u, 233u).toByteArray()))

        val infohash = torrent.load(lame)
        /* Returned peer list is: [("127.0.0.22", 6887)] */
        torrent.announce(infohash, TorrentEvent.STARTED, 0, 0, 2703360)

        /* Returned peer list is: [("127.0.0.22", 6887), ("127.0.0.21", 6889)] */
        torrent.announce(infohash, TorrentEvent.REGULAR, 0, 81920, 2621440)

        torrent.invalidatePeer(infohash, KnownPeer("127.0.0.22", 6887, null))

        runWithTimeout(Duration.ofSeconds(10)) {
            assertThat(
                    torrent.knownPeers(infohash),
                    anyElement(has(KnownPeer::ip, equalTo("127.0.0.22")) and has(KnownPeer::port, equalTo(6887))).not()
            )
        }
    }

    @Test
    fun `invalidate peer will throw exception for wrong infohash`() {
        assertThrows<java.lang.IllegalArgumentException> {
            runWithTimeout(Duration.ofSeconds(10)) {
                torrent.invalidatePeer("wrong infohash", KnownPeer("127.1.1.23", 6887, null))
            }
        }
    }

    @Test
    fun `peer will not be invalidated if it's not in the peers list`() {
        mockHttp(mapOf("interval" to 360, "complete" to 0, "incomplete" to 0, "tracker id" to "1234",
                "peers" to ubyteArrayOf(127u, 0u, 0u, 22u, 26u, 231u, 127u, 0u, 0u, 21u, 26u, 233u).toByteArray()))

        val infohash = torrent.load(lame)
        /* Returned peer list is: [("127.0.0.22", 6887)] */
        torrent.announce(infohash, TorrentEvent.STARTED, 0, 0, 2703360)
        /* Returned peer list is: [("127.0.0.22", 6887), ("127.0.0.21", 6889)] */
        torrent.announce(infohash, TorrentEvent.REGULAR, 0, 81920, 2621440)
        /* nothing should happend to the list */
        torrent.invalidatePeer(infohash, KnownPeer("127.1.1.23", 6887, null))

        assertThat(
            torrent.knownPeers(infohash),
            anyElement(has(KnownPeer::port, equalTo(6887)) or has(KnownPeer::port, equalTo(6889)))
        )
    }

    @Test
    fun `trackerStats call throws exception for wrong infohash`() {
        assertThrows<java.lang.IllegalArgumentException> { torrent.trackerStats("wrong infohash") }
    }

    @Test
    fun `trackerStats are stored correctly`() {
        val infohash = torrent.load(ubuntu)

        mockHttpStringStartsWith(listOf("https://torrent.ubuntu.com/announce" to mapOf("interval" to 360,
                "complete" to 100,
                "incomplete" to 50,
                "tracker id" to "1234",
                "peers" to listOf(
                        mapOf("peer id" to "3", "ip" to "127.0.0.3", "port" to "133")
                ))))
        torrent.announce(infohash, TorrentEvent.REGULAR, 0, 0, 2703360)

        mockHttpStringStartsWith(listOf(
                "https://torrent.ubuntu.com/scrape" to mapOf("failure reason" to "dude i failed"),
                "https://ipv6.torrent.ubuntu.com/scrape" to mapOf("files" to mapOf("myinfohash" to
                mapOf("complete" to 123, "incomplete" to 12, "downloaded" to 1234, "name" to "ubuntu")))))
        torrent.scrape(infohash)

        assertThat(
                torrent.trackerStats(infohash),
                equalTo(mapOf(
                        "https://torrent.ubuntu.com/announce" to Failure("dude i failed"),
                        "https://ipv6.torrent.ubuntu.com/announce" to Scrape(123, 1234, 12, "ubuntu")
                ))
        )
    }

}
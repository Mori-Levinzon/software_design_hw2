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
import il.ac.technion.cs.softwaredesign.Utils.Companion.toMap
import il.ac.technion.cs.softwaredesign.Utils.Companion.toPieceIndexStats
import io.github.vjames19.futures.jdk8.ImmediateFuture
import io.mockk.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.assertThrows
import java.lang.IllegalArgumentException
import java.lang.IllegalStateException
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.ExecutionException

class CourseTorrentHW1Test {
    private val injector = Guice.createInjector(CourseTorrentModule())
    private var torrent = injector.getInstance<CourseTorrent>()
    private val debian = this::class.java.getResource("/debian-10.3.0-amd64-netinst.iso.torrent").readBytes()
    private val ubuntu = this::class.java.getResource("/ubuntu-18.04.4-desktop-amd64.iso.torrent").readBytes()
    private val lame = this::class.java.getResource("/lame.torrent").readBytes()
    private val lame_big_list = this::class.java.getResource("/lame_big_list.torrent").readBytes()

    private var torrentsStorage = HashMap<String, ByteArray>()
    private var peersStorage = HashMap<String, ByteArray>()
    private var trackerStatsStorage = HashMap<String, ByteArray>()
    private var announcesStorage = HashMap<String, ByteArray>()
    private var piecesStatsStorage = HashMap<String, ByteArray>()
    private var indexedPieceStorage = HashMap<String, ByteArray>()


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
        var numOfPieces = slot<Long>()


        every { memoryDB.announcesCreate(capture(key), capture(announcesValue)) } answers {
            ImmediateFuture {
                if(announcesStorage.containsKey(key.captured)) throw IllegalStateException()
                announcesStorage[key.captured] = Ben.encodeStr(announcesValue.captured).toByteArray()
                Unit
            }
        }
        every { memoryDB.peersCreate(capture(key), capture(peersValue)) } answers {
            ImmediateFuture {
                if (peersStorage.containsKey(key.captured)) throw IllegalStateException()
                peersStorage[key.captured] = Ben.encodeStr(peersValue.captured).toByteArray()
                Unit
            }
        }
        every { memoryDB.torrentsCreate(capture(key), capture(torrentsValue)) } answers {
            ImmediateFuture {
                if(torrentsStorage.containsKey(key.captured)) throw IllegalStateException()
                torrentsStorage[key.captured] = Ben.encodeByteArray(torrentsValue.captured)
                Unit
            }
        }
        every { memoryDB.trackersStatsCreate(capture(key), capture(statsValue)) } answers {
            ImmediateFuture {
                if(trackerStatsStorage.containsKey(key.captured)) throw IllegalStateException()
                trackerStatsStorage[key.captured] = Ben.encodeStr(statsValue.captured).toByteArray()
                Unit
            }
        }
        every { memoryDB.piecesStatsCreate(capture(key), capture(piecesStatsValue)) } answers {
            ImmediateFuture {
                if(piecesStatsStorage.containsKey(key.captured)) throw IllegalStateException()
                piecesStatsStorage[key.captured] = Ben.encodeStr(piecesStatsValue.captured.mapValues { pair -> pair.value.toMap() }.mapKeys { it.key.toString() }).toByteArray()
                Unit
            }
        }
        every { memoryDB.indexedPieceCreate(capture(key), capture(indexedKey), capture(indexPieceValue)) } answers {
            ImmediateFuture {
                if(indexedPieceStorage.containsKey(key.captured+indexedKey.captured.toString())) throw IllegalStateException()
                indexedPieceStorage[key.captured+indexedKey.captured.toString()] = ByteArray(0)
                Unit
            }
        }
        every { memoryDB.allpiecesCreate(capture(key), capture(numOfPieces)) } answers {
            ImmediateFuture {
                for (i in 0 until numOfPieces.captured) {
                    if(indexedPieceStorage.containsKey(key.captured+i.toString())) throw IllegalStateException()
                    indexedPieceStorage[key.captured+i.toString()] = ByteArray(0)
                }
                Unit
            }
        }


        every { memoryDB.torrentsUpdate(capture(key), capture(torrentsValue)) } answers {
            ImmediateFuture {
                if(!torrentsStorage.containsKey(key.captured)) throw IllegalArgumentException()
                torrentsStorage[key.captured] = Ben.encodeByteArray(torrentsValue.captured)
                Unit
            }
        }
        every { memoryDB.announcesUpdate(capture(key), capture(announcesValue)) } answers {
            ImmediateFuture {
                if(!announcesStorage.containsKey(key.captured)) throw IllegalArgumentException()
                announcesStorage[key.captured] = Ben.encodeStr(announcesValue.captured).toByteArray()
                Unit
            }
        }
        every { memoryDB.peersUpdate(capture(key), capture(peersValue)) } answers {
            ImmediateFuture {
                if(!peersStorage.containsKey(key.captured)) throw IllegalArgumentException()
                peersStorage[key.captured] = Ben.encodeStr(peersValue.captured).toByteArray()
                Unit
            }
        }
        every { memoryDB.trackersStatsUpdate(capture(key), capture(statsValue)) } answers {
            ImmediateFuture {
                if(!trackerStatsStorage.containsKey(key.captured)) throw IllegalArgumentException()
                trackerStatsStorage[key.captured] = Ben.encodeStr(statsValue.captured).toByteArray()
                Unit
            }
        }
        every { memoryDB.piecesStatsUpdate(capture(key), capture(piecesStatsValue)) } answers {
            ImmediateFuture {
                if(!piecesStatsStorage.containsKey(key.captured)) throw IllegalArgumentException()
                piecesStatsStorage[key.captured] = Ben.encodeStr(piecesStatsValue.captured.mapValues { it.value.toMap() }.mapKeys { it.key.toString() }).toByteArray()
                Unit
            }
        }
        every { memoryDB.indexedPieceUpdate(capture(key), capture(indexedKey), capture(indexPieceValue)) } answers {
            ImmediateFuture {
                if(!indexedPieceStorage.containsKey(key.captured+indexedKey.captured.toString())) throw IllegalArgumentException()
                indexedPieceStorage[key.captured+indexedKey.captured.toString()] = Ben.encodeStr(statsValue.captured).toByteArray()
                Unit
            }
        }

        every { memoryDB.torrentsRead(capture(key)) } answers {
            ImmediateFuture {
                if(!torrentsStorage.containsKey(key.captured)) throw IllegalArgumentException()
                Ben(torrentsStorage[key.captured] as ByteArray).decode() as? Map<String,Any>? ?: throw IllegalArgumentException()
            }
        }
        every { memoryDB.announcesRead(capture(key)) } answers {
            ImmediateFuture {
                if(!announcesStorage.containsKey(key.captured)) throw IllegalArgumentException()
                Ben(announcesStorage[key.captured] as ByteArray).decode() as? List<List<String>>? ?: throw IllegalArgumentException()
            }
        }
        every { memoryDB.peersRead(capture(key)) } answers {
            ImmediateFuture {
                if(!peersStorage.containsKey(key.captured)) throw IllegalArgumentException()
                Ben(peersStorage[key.captured] as ByteArray).decode() as? List<Map<String, String>> ?: throw IllegalArgumentException()
            }
        }
        every { memoryDB.trackersStatsRead(capture(key)) } answers {
            ImmediateFuture {
                if(!trackerStatsStorage.containsKey(key.captured)) throw IllegalArgumentException()
                Ben(trackerStatsStorage[key.captured] as ByteArray).decode() as? Map<String, Map<String, Any>> ?: throw IllegalArgumentException()
            }
        }
        every { memoryDB.piecesStatsRead(capture(key)) } answers {
            ImmediateFuture {
                if(!piecesStatsStorage.containsKey(key.captured)) throw IllegalArgumentException()
                (Ben(piecesStatsStorage[key.captured] as ByteArray).decode() as? Map<String, Map<String, Any>>)?.
                mapValues { it -> it.value.toPieceIndexStats() }?.mapKeys { it.key.toLong() }  ?: throw IllegalArgumentException()
            }
        }
        every { memoryDB.indexedPieceRead(capture(key),capture(indexedKey)) } answers {
            ImmediateFuture {
                if(!indexedPieceStorage.containsKey(key.captured+indexedKey.captured.toString())) throw IllegalArgumentException()
                indexedPieceStorage[key.captured+indexedKey.captured.toString()]  ?: throw IllegalArgumentException()
            }
        }

        every { memoryDB.torrentsDelete(capture(key)) } answers {
            ImmediateFuture {
                torrentsStorage.remove(key.captured) ?: throw IllegalArgumentException()
                Unit
            }
        }
        every { memoryDB.announcesDelete(capture(key)) } answers {
            ImmediateFuture {
                announcesStorage.remove(key.captured) ?: throw IllegalArgumentException()
                Unit
            }
        }
        every { memoryDB.peersDelete(capture(key)) } answers {
            ImmediateFuture {
                peersStorage.remove(key.captured) ?: throw IllegalArgumentException()
                Unit
            }
        }
        every { memoryDB.trackersStatsDelete(capture(key)) } answers {
            ImmediateFuture {
                trackerStatsStorage.remove(key.captured) ?: throw IllegalArgumentException()
                Unit
            }
        }
        every { memoryDB.piecesStatsDelete(capture(key)) } answers {
            ImmediateFuture {
                piecesStatsStorage.remove(key.captured) ?: throw IllegalArgumentException()
                Unit
            }
        }
        every { memoryDB.indexedPieceDelete(capture(key),capture(indexedKey)) } answers {
            ImmediateFuture {
                indexedPieceStorage.remove(key.captured+indexedKey.captured.toString()) ?: throw IllegalArgumentException()
                Unit
            }
        }
        every { memoryDB.allpiecesDelete(capture(key),capture(indexedKey)) } answers {
            ImmediateFuture {
                indexedPieceStorage.clear()
                Unit
            }
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
        val throwable = assertThrows<ExecutionException> {
            runWithTimeout(Duration.ofSeconds(10)){
            torrent.announce("invalid metainfo file", TorrentEvent.STARTED, 0, 0, 0).get() }}
        assertThat(throwable.cause!!, isA<IllegalArgumentException>())
    }

    @Test
    fun `announce call returns an exception for negative values for request params`() {
        val infohash = torrent.load(debian).get()
        mockHttp(mapOf("failure reason" to "Negative parameters"))
//        assertThrows<TrackerException> {
        assertThrows<ExecutionException> {
            runWithTimeout(Duration.ofSeconds(10)){
                torrent.announce(infohash, TorrentEvent.STARTED, -1, 0, 0).get() }
        }
//        assertThrows<TrackerException> {
        assertThrows<ExecutionException> {
            runWithTimeout(Duration.ofSeconds(10)){
                torrent.announce(infohash, TorrentEvent.STARTED, -1, -1, 0).get()
            }
        }
//        assertThrows<TrackerException> {
        assertThrows<ExecutionException> {
            runWithTimeout(Duration.ofSeconds(10)){
                torrent.announce(infohash, TorrentEvent.STARTED, -1, 0, -1).get()
            }
        }
    }

    @Test
    fun `failed announce call still updates the stats DB`() {
        val infohash = torrent.load(lame).get() //this torrent has a bad tracker
//        assertThrows<TrackerException> {
        assertThrows<ExecutionException> {
            runWithTimeout(Duration.ofSeconds(10)){
                torrent.announce(infohash, TorrentEvent.STARTED, 0, 0, 0).get()
            }
        }
        val stats = torrent.trackerStats(infohash).get()
        assert(stats.isNotEmpty())
    }

    @Test
    fun `announce list is shuffled after announce call`() {
        //with a big list, there is almost no chance the shuffled list will be the same
        //the exact probability that this test will fail is 1 / (length of list)! = 1 / (24!) = 1.6e-24
        mockHttp(mapOf("failure reason" to "Service unavailable"))
        val infohash = torrent.load(lame_big_list).get()
        val annouceListBefore = torrent.announces(infohash).join()

//        assertThrows<TrackerException> {
        assertThrows<ExecutionException> {
            torrent.announce(infohash, TorrentEvent.STARTED, 0, 0, 0).get()
        }

        val annouceListAfter = torrent.announces(infohash).join()

        assertThat(annouceListBefore, equalTo(annouceListAfter).not())
        /* Assertion to verify the the announce list was shuffled */
    }

    @Test
    fun `announce request updates the peers DB`() {
        val infohash = torrent.load(debian).get()

        val interval = torrent.announce(infohash, TorrentEvent.STARTED, 0, 0, 0).get()

        val peers = torrent.knownPeers(infohash).get()
        assert(peers.isNotEmpty())
        /* Assertion to verify the peers list is not empty */
    }

    @Test
    fun `client announces to tracker debian`() {
        val infohash = torrent.load(debian).get()

        /* interval is 360 */
        val interval = torrent.announce(infohash, TorrentEvent.STARTED, 0, 0, 0).get()

        assertThat(interval, equalTo(900))
        /* Assertion to verify that the tracker was actually called */
    }

    @Test
    fun `correct announces updates the stats DB`() {
        val infohash = torrent.load(ubuntu).get()

        torrent.announce(infohash, TorrentEvent.STARTED, 0, 0, 0).get()

        val stats = torrent.trackerStats(infohash).get()
        assert(stats.isNotEmpty())
        /* Assertion to verify that the tracker was actually called */
    }

    @Test
    fun `wrong announce change stats data from Scrape type to Failure type`() {
        mockHttp(mapOf("files" to mapOf("myinfohash" to mapOf("complete" to 0, "incomplete" to 0, "downloaded" to 0))))
        val infohash = torrent.load(lame).get()

        /* Tracker has infohash, 0 complete, 0 downloaded, 0 incomplete, no name key */
        assertDoesNotThrow { torrent.scrape(infohash).join() }

        runWithTimeout(Duration.ofSeconds(10)){
            assertThat(
                torrent.trackerStats(infohash).get(),
                equalTo(mapOf(Pair("https://127.0.0.1:8082/announce", Scrape(0, 0, 0, null) as ScrapeData)))
            )
        }

        mockHttp(mapOf("failure reason" to "invalid parameters"))

//        assertThrows<TrackerException> { torrent.announce(infohash, TorrentEvent.STARTED, -1, 0, 0).get() }
        assertThrows<ExecutionException> { torrent.announce(infohash, TorrentEvent.STARTED, -1, 0, 0).get() }


        assert(
            torrent.trackerStats(infohash).get().get("https://127.0.0.1:8082/announce") is Failure
        )

        /* Assertion to verify that the tracker was actually called */    }

    @Test
    fun `after announce, client has up-to-date peer list`() {
        mockHttp(mapOf("interval" to 360, "complete" to 0, "incomplete" to 0, "tracker id" to "1234",
                "peers" to ubyteArrayOf(127u, 0u, 0u, 22u, 26u, 231u, 127u, 0u, 0u, 21u, 26u, 233u).toByteArray()))

        val infohash = torrent.load(lame).get()

        /* Returned peer list is: [("127.0.0.22", 6887)] */
        torrent.announce(infohash, TorrentEvent.STARTED, 0, 0, 2703360).join()
        /* Returned peer list is: [("127.0.0.22", 6887), ("127.0.0.21", 6889)] */
        torrent.announce(infohash, TorrentEvent.REGULAR, 0, 81920, 2621440).join()

        runWithTimeout(Duration.ofSeconds(10)) {
            assertThat(
                    torrent.knownPeers(infohash).get(),
                    anyElement(has(KnownPeer::ip, equalTo("127.0.0.22")) and has(KnownPeer::port, equalTo(6887)))
            )
        }
        runWithTimeout(Duration.ofSeconds(10)) {
            assertThat(
                    torrent.knownPeers(infohash).get(),
                    anyElement(has(KnownPeer::ip, equalTo("127.0.0.21")) and has(KnownPeer::port, equalTo(6889)))
            )
        }

        runWithTimeout(Duration.ofSeconds(10)) {
            assertThat(
                    torrent.knownPeers(infohash).get(), equalTo(torrent.knownPeers(infohash).get().distinct())
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

        val infohash = torrent.load(lame).get()

        torrent.announce(infohash, TorrentEvent.STARTED, 0, 0, 2703360).get()

        runWithTimeout(Duration.ofSeconds(10)) {
            assertThat(
                    torrent.knownPeers(infohash).get(),
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

        val infohash = torrent.load(lame).get()

        torrent.announce(infohash, TorrentEvent.STARTED, 0, 0, 2703360).get()

        runWithTimeout(Duration.ofSeconds(10)) {
            assertThat(
                    torrent.trackerStats(infohash).get(),
                    equalTo(mapOf<String, ScrapeData>(
                            "https://127.0.0.1:8082/announce" to Scrape(12, 0, 24, null)
                    ))
            )
        }
    }

    @Test
    fun `multiple announce calls merge peers lists`() {

        val infohash = torrent.load(lame).get()

        mockHttp(mapOf("interval" to 360, "complete" to 0, "incomplete" to 0, "tracker id" to "1234",
                "peers" to listOf(
                        mapOf("peer id" to "3", "ip" to "127.0.0.3", "port" to "133"),
                        mapOf("peer id" to "2", "ip" to "127.0.0.2", "port" to "122"),
                        mapOf("peer id" to "4", "ip" to "127.0.0.4", "port" to "144")
                )))
        torrent.announce(infohash, TorrentEvent.STARTED, 0, 0, 2703360).get()

        mockHttp(mapOf("interval" to 360, "complete" to 0, "incomplete" to 0, "tracker id" to "1234",
                "peers" to listOf(
                        mapOf("peer id" to "1", "ip" to "127.0.0.1", "port" to "111"),
                        mapOf("peer id" to "2", "ip" to "127.0.0.2", "port" to "122"),
                        mapOf("peer id" to "4", "ip" to "127.0.0.4", "port" to "144")
                )))
        torrent.announce(infohash, TorrentEvent.STARTED, 0, 0, 2703360).get()

        runWithTimeout(Duration.ofSeconds(10)) {
            assertThat(
                    torrent.knownPeers(infohash).get(),
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
        val infohash = torrent.load(ubuntu).get()

        /* Tracker has infohash, 0 complete, 0 downloaded, 0 incomplete, no name key */
        assertDoesNotThrow { torrent.scrape(infohash).join() }

        runWithTimeout(Duration.ofSeconds(10)){
            val stats = torrent.trackerStats(infohash).get()
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
        val throwable = assertThrows<CompletionException> {
            runWithTimeout(Duration.ofSeconds(10)){
                torrent.scrape("wrong infohash").join()
            }
        }
        assertThat(throwable.cause!!, isA<IllegalArgumentException>())

        /* Assertion to verify that the tracker was actually called */
    }

    @Test
    fun `peers are invalidated correctly`() {
        mockHttp(mapOf("interval" to 360, "complete" to 0, "incomplete" to 0, "tracker id" to "1234",
                "peers" to ubyteArrayOf(127u, 0u, 0u, 22u, 26u, 231u, 127u, 0u, 0u, 21u, 26u, 233u).toByteArray()))

        val infohash = torrent.load(lame).get()
        /* Returned peer list is: [("127.0.0.22", 6887)] */
        torrent.announce(infohash, TorrentEvent.STARTED, 0, 0, 2703360).get()

        /* Returned peer list is: [("127.0.0.22", 6887), ("127.0.0.21", 6889)] */
        torrent.announce(infohash, TorrentEvent.REGULAR, 0, 81920, 2621440).get()

        torrent.invalidatePeer(infohash, KnownPeer("127.0.0.22", 6887, null)).get()

        runWithTimeout(Duration.ofSeconds(10)) {
            assertThat(
                    torrent.knownPeers(infohash).get(),
                    anyElement(has(KnownPeer::ip, equalTo("127.0.0.22")) and has(KnownPeer::port, equalTo(6887))).not()
            )
        }
    }

    @Test
    fun `invalidate peer will throw exception for wrong infohash`() {
        val throwable = assertThrows<ExecutionException> {
            runWithTimeout(Duration.ofSeconds(10)) {
                torrent.invalidatePeer("wrong infohash", KnownPeer("127.1.1.23", 6887, null)).get()
            }
        }
        assertThat(throwable.cause!!, isA<IllegalArgumentException>())
    }

    @Test
    fun `peer will not be invalidated if it's not in the peers list`() {
        mockHttp(mapOf("interval" to 360, "complete" to 0, "incomplete" to 0, "tracker id" to "1234",
                "peers" to ubyteArrayOf(127u, 0u, 0u, 22u, 26u, 231u, 127u, 0u, 0u, 21u, 26u, 233u).toByteArray()))

        val infohash = torrent.load(lame).get()
        /* Returned peer list is: [("127.0.0.22", 6887)] */
        torrent.announce(infohash, TorrentEvent.STARTED, 0, 0, 2703360).get()
        /* Returned peer list is: [("127.0.0.22", 6887), ("127.0.0.21", 6889)] */
        torrent.announce(infohash, TorrentEvent.REGULAR, 0, 81920, 2621440).get()
        /* nothing should happend to the list */
        torrent.invalidatePeer(infohash, KnownPeer("127.1.1.23", 6887, null)).get()

        assertThat(
            torrent.knownPeers(infohash).get(),
            anyElement(has(KnownPeer::port, equalTo(6887)) or has(KnownPeer::port, equalTo(6889)))
        )
    }

    @Test
    fun `trackerStats call throws exception for wrong infohash`() {
        val throwable = assertThrows<CompletionException> { torrent.trackerStats("wrong infohash").join() }
        assertThat(throwable.cause!!, isA<IllegalArgumentException>())
    }

    @Test
    fun `trackerStats are stored correctly`() {
        val infohash = torrent.load(ubuntu).get()

        mockHttpStringStartsWith(listOf("https://torrent.ubuntu.com/announce" to mapOf("interval" to 360,
                "complete" to 100,
                "incomplete" to 50,
                "tracker id" to "1234",
                "peers" to listOf(
                        mapOf("peer id" to "3", "ip" to "127.0.0.3", "port" to "133")
                ))))
        torrent.announce(infohash, TorrentEvent.REGULAR, 0, 0, 2703360).get()

        mockHttpStringStartsWith(listOf(
                "https://torrent.ubuntu.com/scrape" to mapOf("failure reason" to "dude i failed"),
                "https://ipv6.torrent.ubuntu.com/scrape" to mapOf("files" to mapOf("myinfohash" to
                mapOf("complete" to 123, "incomplete" to 12, "downloaded" to 1234, "name" to "ubuntu")))))
        torrent.scrape(infohash).join()

        assertThat(
                torrent.trackerStats(infohash).get(),
                equalTo(mapOf(
                        "https://torrent.ubuntu.com/announce" to Failure("dude i failed"),
                        "https://ipv6.torrent.ubuntu.com/announce" to Scrape(123, 1234, 12, "ubuntu")
                ))
        )
    }

}
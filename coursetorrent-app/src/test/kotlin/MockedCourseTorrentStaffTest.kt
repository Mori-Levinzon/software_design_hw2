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
import io.mockk.*
import org.junit.jupiter.api.*
import java.lang.IllegalArgumentException
import java.lang.IllegalStateException
import java.util.concurrent.CompletionException
import io.github.vjames19.futures.jdk8.ImmediateFuture
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.assertThrows
import java.net.Socket
import java.util.concurrent.CompletableFuture

class MockedCourseTorrentStaffTest {
    private val injector = Guice.createInjector(CourseTorrentModule())
    private var torrent = injector.getInstance<CourseTorrent>()
    private val debian = this::class.java.getResource("/debian-10.3.0-amd64-netinst.iso.torrent").readBytes()
    private val lame = this::class.java.getResource("/lame.torrent").readBytes()
    private var torrentsStorage = HashMap<String, ByteArray>()
    private var peersStorage = HashMap<String, ByteArray>()
    private var trackerStatsStorage = HashMap<String, ByteArray>()
    private var announcesStorage = HashMap<String, ByteArray>()
    private var piecesStatsStorage = HashMap<String, ByteArray>()
    private var indexedPieceStorage = HashMap<String, ByteArray>()//TODO: not really like this

    private val lameExe = this::class.java.getResource("/lame.exe")
    private val lameEnc = this::class.java.getResource("/lame_enc.dll")
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
                piecesStatsStorage[key.captured] = Ben.encodeStr(piecesStatsValue.captured).toByteArray()
                Unit
            }
        }
        every { memoryDB.indexedPieceCreate(capture(key), capture(indexedKey), capture(indexPieceValue)) } answers {
            ImmediateFuture {
                if(indexedPieceStorage.containsKey(key.captured+indexedKey.captured.toString())) throw IllegalStateException()
                indexedPieceStorage[key.captured+indexedKey.captured.toString()] = Ben.encodeStr(piecesStatsValue.captured).toByteArray()
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
                piecesStatsStorage[key.captured] = Ben.encodeStr(statsValue.captured).toByteArray()
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
                Ben(piecesStatsStorage[key.captured] as ByteArray).decode() as? Map<Long, PieceIndexStats> ?: throw IllegalArgumentException()
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
                piecesStatsStorage.remove(key.captured+indexedKey.captured.toString()) ?: throw IllegalArgumentException()
                Unit
            }
        }
        every { memoryDB.indexedPieceDelete(capture(key),capture(indexedKey)) } answers {
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
    fun `after load, infohash calculated correctly`() {
        val infohash = torrent.load(debian).get()

        assertThat(infohash, equalTo("5a8062c076fa85e8056451c0d9aa04349ae27909"))
    }

    @Test
    fun `after load, announce is correct`() {
        val infohash = torrent.load(debian).get()

        val announces = assertDoesNotThrow { torrent.announces(infohash).join() }

        assertThat(announces, allElements(hasSize(equalTo(1))))
        assertThat(announces, hasSize(equalTo(1)))
        assertThat(announces, allElements(hasElement("http://bttracker.debian.org:6969/announce")))
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
    fun `client scrapes tracker and updates statistics`() {
        mockHttpStringStartsWith(listOf(
                "https://127.0.0.1:8082/scrape" to mapOf("files" to mapOf("myinfohash" to
                        mapOf("complete" to 0, "incomplete" to 0, "downloaded" to 0)))))
        val infohash = torrent.load(lame).get()

        /* Tracker has infohash, 0 complete, 0 downloaded, 0 incomplete, no name key */
        assertDoesNotThrow { torrent.scrape(infohash).join() }

        assertThat(
                torrent.trackerStats(infohash).get(),
                equalTo(mapOf(Pair("https://127.0.0.1:8082/announce", Scrape(0, 0, 0, null) as ScrapeData)))
        )
        /* Assertion to verify that the tracker was actually called */
    }

    @Test
    fun `after announce, client has up-to-date peer list`() {
        mockHttp(mapOf("interval" to 360, "complete" to 0, "incomplete" to 0, "tracker id" to "1234",
                "peers" to ubyteArrayOf(127u, 0u, 0u, 22u, 26u, 231u, 127u, 0u, 0u, 21u, 26u, 233u).toByteArray()))

        val infohash = torrent.load(lame).get()

        /* Returned peer list is: [("127.0.0.22", 6887)] */
        torrent.announce(infohash, TorrentEvent.STARTED, 0, 0, 2703360).join()
        /* Returned peer list is: [("127.0.0.22", 6887), ("127.0.0.21", 6889)] */
        torrent.announce(infohash, TorrentEvent.REGULAR, 0, 81920, 2621440).join()

        val knownPeers = torrent.knownPeers(infohash).join()

        assertThat(
                knownPeers,
                anyElement(has(KnownPeer::ip, equalTo("127.0.0.22")) and has(KnownPeer::port, equalTo(6887)))
        )
        assertThat(
                knownPeers,
                anyElement(has(KnownPeer::ip, equalTo("127.0.0.21")) and has(KnownPeer::port, equalTo(6889)))
        )
        assertThat(knownPeers, equalTo(knownPeers.distinct()))
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

        val knownPeers = torrent.knownPeers(infohash).get()

        assertThat(
                knownPeers,
                anyElement(has(KnownPeer::ip, equalTo("127.0.0.22")) and has(KnownPeer::port, equalTo(6887))).not()
        )
    }

    @Test
    fun `exceptions are thrown inside the CompletableFuture`() {
        val future = assertDoesNotThrow { torrent.knownPeers("this is not a valid infohash") }
        val throwable = assertThrows<CompletionException> { future.join() }

        checkNotNull(throwable.cause)
        assertThat(throwable.cause!!, isA<IllegalArgumentException>())
    }

    @Test
    fun `lame torrent is loaded, file data is loaded, and recheck returns true`() {
        val infohash = torrent.load(lame).get()

        val done = torrent.loadFiles(
                infohash,
                mapOf("lame.exe" to lameExe.readBytes(), "lame_enc.dll" to lameEnc.readBytes())
        )
                .thenCompose { torrent.recheck(infohash) }.get()

        Assertions.assertTrue(done)
    }

    @Test
    fun `lame torrent is loaded, wrong file data is loaded, and recheck returns false`() {
        val infohash = torrent.load(lame).get()

        val done = torrent.loadFiles(
                infohash,
                mapOf("lame.exe" to "wrong data".toByteArray(), "lame_enc.dll" to "wrongest data".toByteArray())
        )
                .thenCompose { torrent.recheck(infohash) }.get()

        Assertions.assertFalse(done)
    }

    @Test
    fun `starts listening and responds to connection and handshake`() {
        val infohash = torrent.load(lame).get()

        val sock = initiateRemotePeer(infohash)

        torrent.stop().get()
        sock.close()
    }

//    @Test
//    fun `lists remotely connected peer in known and connected peers`() {
//        val infohash = torrent.load(lame).get()
//
//        val sock = initiateRemotePeer(infohash)
//
//        val knownPeers = torrent.knownPeers(infohash).get()
//        val connectedPeers = torrent.connectedPeers(infohash).get()
//
//        assertThat(connectedPeers.size, equalTo(1))
//
//        torrent.stop().get()
//        sock.close()
//    }

//    @Test
//    fun `sends choke command to peer`() {
//        val infohash = torrent.load(lame).get()
//        val sock = initiateRemotePeer(infohash)
//
//        torrent.connectedPeers(infohash).thenApply {
//            it.asSequence().map(ConnectedPeer::knownPeer).first() }
//                .thenAccept { torrent.choke(infohash, it) }
//
//        val message = StaffWireProtocolDecoder.decode(sock.inputStream.readNBytes(5), 0)
//
//        assertThat(message.messageId, equalTo(0.toByte()))
//
//        torrent.stop().get()
//        sock.close()
//    }

//    @Test
//    fun `sends unchoke command to peer`() {
//        val infohash = torrent.load(lame).get()
//        val sock = initiateRemotePeer(infohash)
//
//        torrent.connectedPeers(infohash).thenApply {
//            it.asSequence().map(ConnectedPeer::knownPeer).first() }
//                .thenAccept { torrent.unchoke(infohash, it) }
//
//        val message = StaffWireProtocolDecoder.decode(sock.inputStream.readNBytes(5), 0)
//
//        assertThat(message.messageId, equalTo(1.toByte()))
//
//        torrent.stop().get()
//        sock.close()
//    }

//    @Test
//    fun `after receiving have message, a piece is marked as available`() {
//        val infohash = torrent.load(lame).get()
//        val sock = initiateRemotePeer(infohash)
//        sock.outputStream.write(StaffWireProtocolEncoder.encode(4, 0))
//        sock.outputStream.flush()
//
//        val pieces = assertDoesNotThrow {
//            torrent.handleSmallMessages().get()
//            torrent.availablePieces(infohash, 10, 0).get()
//        }
//
//        assertThat(pieces.keys, hasSize(equalTo(1)))
//        assertThat(pieces.values.first(), hasElement(0L))
//
//        torrent.stop().get()
//        sock.close()
//    }

//    @Test
//    fun `sends interested message to peer after receiving a have message`() {
//        val infohash = torrent.load(lame).get()
//        val sock = initiateRemotePeer(infohash)
//        sock.outputStream.write(StaffWireProtocolEncoder.encode(4, 0))
//        sock.outputStream.flush()
//
//        assertDoesNotThrow { torrent.handleSmallMessages().get() }
//
//        val message = StaffWireProtocolDecoder.decode(sock.inputStream.readNBytes(5), 0)
//
//        assertThat(message.messageId, equalTo(2.toByte()))
//
//        torrent.stop().get()
//        sock.close()
//    }

    private fun initiateRemotePeer(infohash: String): Socket {
        mockHttp(mapOf("interval" to 360, "complete" to 0, "incomplete" to 0, "tracker id" to "1234",
                "peers" to ubyteArrayOf(127u, 0u, 0u, 22u, 26u, 231u, 127u, 0u, 0u, 21u, 26u, 233u).toByteArray()))
        torrent.torrentStats(infohash).thenCompose {
            torrent.announce(
                    infohash,
                    TorrentEvent.STARTED,
                    uploaded = it.uploaded,
                    downloaded = it.downloaded,
                    left = it.left
            )
        }.join()

        val port: Int = 6881

        assertDoesNotThrow { torrent.start().join() }

        val sock = assertDoesNotThrow { Socket("127.0.0.1", port) }
        sock.outputStream.write(
                WireProtocolEncoder.handshake(
                        hexStringToByteArray(infohash),
                        hexStringToByteArray(infohash.reversed())
                )
        )

        assertDoesNotThrow { torrent.handleSmallMessages().join() }

        val output = sock.inputStream.readNBytes(68)

        val (otherInfohash, otherPeerId) = WireProtocolDecoder.handshake(output)

        Assertions.assertTrue(otherInfohash.contentEquals(hexStringToByteArray(infohash)))

        return sock
    }
}

fun hexStringToByteArray(input: String) = input.chunked(2).map { it.toUpperCase().toInt(16).toByte() }.toByteArray()
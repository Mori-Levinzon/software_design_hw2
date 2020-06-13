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
import il.ac.technion.cs.softwaredesign.exceptions.PeerConnectException
import io.mockk.*
import org.junit.jupiter.api.*
import java.lang.IllegalArgumentException
import java.lang.IllegalStateException
import java.util.concurrent.CompletionException
import io.github.vjames19.futures.jdk8.ImmediateFuture
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.assertThrows
import java.net.ServerSocket
import java.net.Socket
import java.time.Duration
import java.util.concurrent.CompletableFuture

class CourseTorrentHW2Test {
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
    fun `torrentStats throws exception for the wrong infohash `() {
        val throwable = assertThrows<CompletionException> {
            runWithTimeout(Duration.ofSeconds(10)){
                torrent.torrentStats("wrong infohash").get()
            }
        }
        assertThat(throwable.cause!!, isA<IllegalArgumentException>())
    }

    @Test
    fun `torrentStats changed through the load calls`() {
        val infohash = torrent.load(lame).get()

        val stats = torrent.torrentStats(infohash).join()

        assertThat(stats.havePieces,equalTo(0))
        assertThat(stats.pieces, equalTo(165))

        torrent.loadFiles(
                infohash,
                mapOf("lame.exe" to "wrong data".toByteArray(), "lame_enc.dll" to "wrongest data".toByteArray())
        ).join()

        assertThat(stats.havePieces,equalTo(165))
    }

    @Test
    fun `torrentStats changed through the requestPiece`() {
        val infohash = torrent.load(lame).get()

        val stats = torrent.torrentStats(infohash).join()

        assertThat(stats.havePieces,equalTo(0))
        assertThat(stats.pieces, equalTo(165))

        val secondPeer = TODO("COMPLETE ME")
        torrent.requestPiece(infohash,secondPeer,1)
        .join()
        val firstpieceSize = TODO("COMPLETE ME")
        assertThat(stats.downloaded,equalTo(firstpieceSize))

        torrent.sendPiece(infohash,secondPeer,2)
        val secondPieceSize = TODO("COMPLETE ME")

        assertThat(stats.uploaded,equalTo(secondPieceSize))

    }

    @Test
    fun `start throws exception for listening twice  `() {
        val throwable = assertThrows<CompletionException> {
            runWithTimeout(Duration.ofSeconds(10)){
                torrent.start().thenCompose { torrent.start() }.get()
            }
        }
        assertThat(throwable.cause!!, isA<IllegalArgumentException>())
    }

    @Test
    fun `stop throws exception for calling without listening  `() {
        val throwable = assertThrows<CompletionException> {
            runWithTimeout(Duration.ofSeconds(10)){
                torrent.stop().get()
            }
        }
        assertThat(throwable.cause!!, isA<IllegalArgumentException>())
    }

    @Test
    fun `connect throws exception for wrong torrent and peer`() {
        val infohash = torrent.load(lame).get()
        val throwable = assertThrows<CompletionException> {
            runWithTimeout(Duration.ofSeconds(10)){
                torrent.start().thenCompose {torrent.connect(infohash,KnownPeer("false",777,null))}.join()
            }
        }
        assertThat(throwable.cause!!, isA<IllegalArgumentException>())

        val secondPeer = TODO("COMPLETE ME")
        throwable = assertThrows<CompletionException> {
            runWithTimeout(Duration.ofSeconds(10)){
                torrent.start().thenCompose {torrent.connect("wrong infohash",secondPeer)}.join()
            }
        }
        assertThat(throwable.cause!!, isA<IllegalArgumentException>())

    }

    @Test
    fun `connect throws exception for bad connection`() {
        val infohash = torrent.load(lame).get()
        val secondPeer = TODO("COMPLETE ME")
        val throwable = assertThrows<CompletionException> {
            runWithTimeout(Duration.ofSeconds(10)){
                torrent.start().thenCompose { torrent.stop() }.thenCompose { torrent.connect(infohash,secondPeer) }.join()
            }
        }
        assertThat(throwable.cause!!, isA<PeerConnectException>())
    }

    @Test
    fun `disconnect throws exception for wrong torrent or  unconnected peer`() {
        val infohash = torrent.load(lame).get()
        val throwable = assertThrows<CompletionException> {
            runWithTimeout(Duration.ofSeconds(10)){
                torrent.start().thenCompose {torrent.disconnect(infohash,KnownPeer("false",777,null))}.join()
            }
        }
        assertThat(throwable.cause!!, isA<IllegalArgumentException>())

        val secondPeer = TODO("COMPLETE ME")
        throwable = assertThrows<CompletionException> {
            runWithTimeout(Duration.ofSeconds(10)){
                torrent.start().thenCompose {torrent.connect("wrong infohash",secondPeer)}.join()
            }
        }
        assertThat(throwable.cause!!, isA<IllegalArgumentException>())

    }

    @Test
    fun `connectedPeers throws exception for the wrong infohash `() {
        val throwable = assertThrows<CompletionException> {
            runWithTimeout(Duration.ofSeconds(10)){
                torrent.connectedPeers("wrong infohash").get()
            }
        }
        assertThat(throwable.cause!!, isA<IllegalArgumentException>())
    }

    @Test
    fun `choke throws exception for the wrong infohash or unconnectedPeer`() {
        val infohash = torrent.load(lame).get()
        val unconnectedPeer = TODO("COMPLETE ME")
        val throwable = assertThrows<CompletionException> {
            runWithTimeout(Duration.ofSeconds(10)){
                torrent.start().thenCompose {torrent.choke(infohash,unconnectedPeer)}.join()
            }
        }
        assertThat(throwable.cause!!, isA<IllegalArgumentException>())

        val secondPeer = TODO("COMPLETE ME")
        throwable = assertThrows<CompletionException> {
            runWithTimeout(Duration.ofSeconds(10)){
                torrent.start().thenCompose {torrent.choke("wrong infohash",secondPeer)}.join()
            }
        }
        assertThat(throwable.cause!!, isA<IllegalArgumentException>())
    }

    @Test
    fun `unchoke throws exception for the wrong infohash or unconnectedPeer`() {
        val infohash = torrent.load(lame).get()
        val unconnectedPeer = TODO("COMPLETE ME")
        val throwable = assertThrows<CompletionException> {
            runWithTimeout(Duration.ofSeconds(10)){
                torrent.start().thenCompose {torrent.unchoke(infohash,unconnectedPeer)}.join()
            }
        }
        assertThat(throwable.cause!!, isA<IllegalArgumentException>())

        val secondPeer = TODO("COMPLETE ME")
        throwable = assertThrows<CompletionException> {
            runWithTimeout(Duration.ofSeconds(10)){
                torrent.start().thenCompose {torrent.unchoke("wrong infohash",secondPeer)}.join()
            }
        }
        assertThat(throwable.cause!!, isA<IllegalArgumentException>())
    }

    @Test
    fun `requestPiece throws exception for the wrong infohash or unconnectedPeer`() {
        val infohash = torrent.load(lame).get()
        val unconnectedPeer = TODO("COMPLETE ME")
        val throwable = assertThrows<CompletionException> {
            runWithTimeout(Duration.ofSeconds(10)){
                torrent.start().thenCompose {torrent.requestPiece(infohash,unconnectedPeer,0)}.join()
            }
        }
        assertThat(throwable.cause!!, isA<IllegalArgumentException>())

        val secondPeer = TODO("COMPLETE ME")
        throwable = assertThrows<CompletionException> {
            runWithTimeout(Duration.ofSeconds(10)){
                torrent.start().thenCompose {torrent.requestPiece("wrong infohash",secondPeer,0)}.join()
            }
        }
        assertThat(throwable.cause!!, isA<IllegalArgumentException>())


        throwable = assertThrows<CompletionException> {
            runWithTimeout(Duration.ofSeconds(10)){
                torrent.start().thenCompose {torrent.requestPiece(infohash,secondPeer,190)}.join()
            }
        }
        assertThat(throwable.cause!!, isA<IllegalArgumentException>())
    }

    @Test
    fun `sendPiece throws exception for the wrong infohash or unconnectedPeer`() {
        val infohash = torrent.load(lame).get()
        val unconnectedPeer = TODO("COMPLETE ME")
        val requestedPiece: Long =1//TODO: create a piece request
        val throwable = assertThrows<CompletionException> {
            runWithTimeout(Duration.ofSeconds(10)){
                torrent.start().thenCompose {torrent.sendPiece(infohash,unconnectedPeer,0)}.join()
            }
        }
        assertThat(throwable.cause!!, isA<IllegalArgumentException>())

        val secondPeer = TODO("COMPLETE ME")
        throwable = assertThrows<CompletionException> {
            runWithTimeout(Duration.ofSeconds(10)){
                torrent.start().thenCompose {torrent.sendPiece("wrong infohash",secondPeer,0)}.join()
            }
        }
        assertThat(throwable.cause!!, isA<IllegalArgumentException>())

        val otherPieceThatWasNotRequested: Long = 2
        throwable = assertThrows<CompletionException> {
            runWithTimeout(Duration.ofSeconds(10)){
                torrent.start().thenCompose {torrent.sendPiece(infohash,secondPeer,otherPieceThatWasNotRequested)}.join()
            }
        }
        assertThat(throwable.cause!!, isA<IllegalArgumentException>())

    }

    @Test
    fun `availablePieces throws exception for the wrong infohash `() {
        val throwable = assertThrows<CompletionException> {
            runWithTimeout(Duration.ofSeconds(10)){
                torrent.availablePieces("wrong infohash",165,0).get()
            }
        }
        assertThat(throwable.cause!!, isA<IllegalArgumentException>())
    }

    @Test
    fun `requestedPieces throws exception for the wrong infohash `() {
        val throwable = assertThrows<CompletionException> {
            runWithTimeout(Duration.ofSeconds(10)){
                torrent.requestedPieces("wrong infohash").get()
            }
        }
        assertThat(throwable.cause!!, isA<IllegalArgumentException>())
    }

    @Test
    fun `files throws exception for the wrong infohash `() {
        val throwable = assertThrows<CompletionException> {
            runWithTimeout(Duration.ofSeconds(10)){
                torrent.files(
                        "wrong infohash")
                        .get()            }
        }
        assertThat(throwable.cause!!, isA<IllegalArgumentException>())
    }

    @Test
    fun `loadFiles throws exception for the wrong infohash `() {
        val throwable = assertThrows<CompletionException> {
            runWithTimeout(Duration.ofSeconds(10)){
                torrent.loadFiles(
                        "wrong infohash",
                        mapOf("lame.exe" to "wrong data".toByteArray(), "lame_enc.dll" to "wrongest data".toByteArray())).get()                 }
        }
        assertThat(throwable.cause!!, isA<IllegalArgumentException>())
    }

    @Test
    fun `recheck throws exception for the wrong infohash `() {
        val throwable = assertThrows<CompletionException> {
            runWithTimeout(Duration.ofSeconds(10)){
                torrent.recheck(
                        "wrong infohash")
                        .get()                  }
        }
        assertThat(throwable.cause!!, isA<IllegalArgumentException>())
    }


    @Test
    fun `connects on command and initiates handshake`() {
        val infohash = torrent.load(lame).get()

        val sock = initiateRemotePeerListener(infohash)

        torrent.disconnect(infohash, KnownPeer("127.0.0.1", 6887, hexStringToByteArray(infohash.reversed()).toString()))
        sock.close()
    }

    @Test
    fun `connect and disconnect update connectedPeers`() {
        val infohash = torrent.load(lame).get()

        val sock = initiateRemotePeerListener(infohash)

        val peersList = assertDoesNotThrow { torrent.connectedPeers(infohash).join() }

        assertThat(peersList.size, equalTo(1))
        assertThat(peersList[0].knownPeer, equalTo(KnownPeer("127.0.0.1", 6887,
                String(hexStringToByteArray(infohash.reversed())))))

        torrent.disconnect(infohash, KnownPeer("127.0.0.1", 6887, String(hexStringToByteArray(infohash.reversed())))).join()
        sock.close()

        val peersList2 = assertDoesNotThrow { torrent.connectedPeers(infohash).join() }
        assertThat(peersList2.size, equalTo(0))
    }


    @Test
    fun `starts listening and responds to connection and handshake`() {
        val infohash = torrent.load(lame).get()

        val sock = initiateRemotePeer(infohash)

        torrent.stop().get()
        sock.close()
    }

    @Test
    fun `lists remotely connected peer in known and connected peers`() {
        val infohash = torrent.load(lame).get()

        val sock = initiateRemotePeer(infohash)

        val knownPeers = torrent.knownPeers(infohash).get()
        val connectedPeers = torrent.connectedPeers(infohash).get()

        assertThat(connectedPeers.size, equalTo(1))

        torrent.stop().get()
        sock.close()
    }

    @Test
    fun `sends choke command to peer`() {
        val infohash = torrent.load(lame).get()
        val sock = initiateRemotePeer(infohash)

        torrent.connectedPeers(infohash).thenApply {
            it.asSequence().map(ConnectedPeer::knownPeer).first() }
                .thenAccept { torrent.choke(infohash, it) }

        val message = WireProtocolDecoder.decode(sock.inputStream.readNBytes(5), 0)

        assertThat(message.messageId, equalTo(0.toByte()))

        torrent.stop().get()
        sock.close()
    }

    @Test
    fun `sends unchoke command to peer`() {
        val infohash = torrent.load(lame).get()
        val sock = initiateRemotePeer(infohash)

        torrent.connectedPeers(infohash).thenApply {
            it.asSequence().map(ConnectedPeer::knownPeer).first() }
                .thenAccept { torrent.unchoke(infohash, it) }

        val message = WireProtocolDecoder.decode(sock.inputStream.readNBytes(5), 0)

        assertThat(message.messageId, equalTo(1.toByte()))

        torrent.stop().get()
        sock.close()
    }

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
                        hexStringToByteArrayHW2Test(infohash),
                        hexStringToByteArrayHW2Test(infohash.reversed())
                )
        )

        assertDoesNotThrow { torrent.handleSmallMessages().join() }

        val output = sock.inputStream.readNBytes(68)

        val (otherInfohash, otherPeerId) = WireProtocolDecoder.handshake(output)

        Assertions.assertTrue(otherInfohash.contentEquals(hexStringToByteArrayHW2Test(infohash)))

        return sock
    }

    private fun initiateRemotePeerListener(infohash: String): Socket {
        mockHttp(mapOf("interval" to 360, "complete" to 0, "incomplete" to 0, "tracker id" to "1234",
                "peers" to ubyteArrayOf(127u, 0u, 0u, 1u, 26u, 231u, 127u, 0u, 0u, 21u, 26u, 233u).toByteArray()))
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

        val peerFuture = CompletableFuture.supplyAsync {
            val sock = assertDoesNotThrow { ServerSocket(6887) }
            val socket = assertDoesNotThrow { sock.accept() }
            socket.outputStream.write(
                    WireProtocolEncoder.handshake(
                            hexStringToByteArray(infohash),
                            hexStringToByteArray(infohash.reversed())
                    )
            )
            socket
        }

        val connectFuture = torrent.connect(infohash, KnownPeer("127.0.0.1", 6887, null))

        assertDoesNotThrow { connectFuture.join() }

        val socket = assertDoesNotThrow { peerFuture.join() }

        val output = socket.inputStream.readNBytes(68)

        val (otherInfohash, otherPeerId) = WireProtocolDecoder.handshake(output)

        Assertions.assertTrue(otherInfohash.contentEquals(hexStringToByteArrayHW2Test(infohash)))
        assertThat(String(otherPeerId), startsWith("-CS1000-"))

        return socket
    }
}

fun hexStringToByteArrayHW2Test(input: String) = input.chunked(2).map { it.toUpperCase().toInt(16).toByte() }.toByteArray()
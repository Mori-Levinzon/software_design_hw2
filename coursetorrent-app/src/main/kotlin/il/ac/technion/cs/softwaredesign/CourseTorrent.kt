@file:Suppress("UNUSED_PARAMETER")

package il.ac.technion.cs.softwaredesign

import com.google.inject.Inject
import il.ac.technion.cs.softwaredesign.Utils.Companion.byteArrayToInfohash
import il.ac.technion.cs.softwaredesign.Utils.Companion.infohashToByteArray
import il.ac.technion.cs.softwaredesign.Utils.Companion.sha1hash
import il.ac.technion.cs.softwaredesign.exceptions.PeerChokedException
import il.ac.technion.cs.softwaredesign.exceptions.PeerConnectException
import il.ac.technion.cs.softwaredesign.exceptions.PieceHashException
import il.ac.technion.cs.softwaredesign.exceptions.TrackerException
import java.lang.Math.ceil
import java.lang.Thread.sleep
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import kotlin.experimental.or
import kotlin.streams.toList


/**
 * This is the class implementing CourseTorrent, a BitTorrent client.
 *
 * Currently specified:
 * + Parsing torrent metainfo files (".torrent" files)
 * + Communication with trackers (announce, scrape).
 * + Communication with peers (downloading! uploading!)
 */
class CourseTorrent @Inject constructor(private val database: SimpleDB) {
    private val alphaNumericID : String = Utils.getRandomChars(6)
    private var serverSocket: ServerSocket? = null
    private val connectedPeers : MutableMap<String, MutableList<ConnectedPeerManager>> = mutableMapOf()
    private var lastTimeKeptAlive = System.currentTimeMillis()
    /**
     * Load in the torrent metainfo file from [torrent]. The specification for these files can be found here:
     * [Metainfo File Structure](https://wiki.theory.org/index.php/BitTorrentSpecification#Metainfo_File_Structure).
     *
     * After loading a torrent, it will be available in the system, and queries on it will succeed.
     *
     * This is a *create* command.
     *
     * @throws IllegalArgumentException If [torrent] is not a valid metainfo file.
     * @throws IllegalStateException If the infohash of [torrent] is already loaded.
     * @return The infohash of the torrent, i.e., the SHA-1 of the `info` key of [torrent].
     */
    fun load(torrent: ByteArray): CompletableFuture<String> {
        return CompletableFuture.supplyAsync {
            TorrentFile.deserialize(torrent)
        }.thenCompose { torrentData ->
            val infohash = torrentData.infohash
            database.announcesCreate(infohash, torrentData.announceList).thenApply {
                try {
                    database.peersCreate(infohash, listOf()).join()
                    database.trackersStatsCreate(infohash, mapOf()).join()

                    val metaInfo = Ben(torrent).decode() as Map<String, Any>
                    val numOfPieces = ((metaInfo["info"] as Map<String,Any>)["pieces"] as ByteArray).size /20
                    val pieceLength = ((metaInfo["info"] as Map<String,Any>)["piece length"] as Long)
                    val lastPieceLength = getTorrentSize(metaInfo) - (numOfPieces - 1) * pieceLength
                    val piecesMap : MutableMap<Long,PieceIndexStats> = mutableMapOf()
                    for (i in 0 until numOfPieces-1){
                        piecesMap[i.toLong()] = PieceIndexStats(0,0, pieceLength,0,false, Duration.ZERO, Duration.ZERO)
                    }
                    piecesMap[numOfPieces.toLong()-1] = PieceIndexStats(0,0, lastPieceLength,0,false, Duration.ZERO, Duration.ZERO)
                    database.piecesStatsCreate(infohash, piecesMap.toMap() ).join()
//                    database.piecesStatsCreate(infohash, mapOf() )

                    database.torrentsCreate(infohash, Ben(torrent).decode() as Map<String, Any>).join()
                    database.allpiecesCreate(infohash, numOfPieces.toLong()).join()
                }catch (e: CompletionException) {
                    throw IllegalStateException("Same infohash already loaded")
                }
                infohash
            }
        }
    }

    /**
     * Remove the torrent identified by [infohash] from the system.
     *
     * This is a *delete* command.
     *
     * @throws IllegalArgumentException If [infohash] is not loaded.
     */
    fun unload(infohash: String): CompletableFuture<Unit> {
        return database.torrentsRead(infohash).thenApply {torrent ->
                    val numOfPieces = ((torrent["info"] as Map<String,Any>)["pieces"] as ByteArray).size /20
                    database.allpiecesDelete(infohash,numOfPieces.toLong()).join()
                }.thenApply {
            try {
                database.announcesDelete(infohash).join()
                this.connectedPeers.remove(infohash)
                database.peersDelete(infohash).join()
                database.trackersStatsDelete(infohash).join()
                database.piecesStatsDelete(infohash).join()
                database.torrentsDelete(infohash).join()
            } catch (e: Exception) {
                throw IllegalArgumentException("Infohash doesn't exist")
            }
            Unit
        }
    }

    /**
     * Return the announce URLs for the loaded torrent identified by [infohash].
     *
     * See [BEP 12](http://bittorrent.org/beps/bep_0012.html) for more information. This method behaves as follows:
     * * If the "announce-list" key exists, it will be used as the source for announce URLs.
     * * If "announce-list" does not exist, "announce" will be used, and the URL it contains will be in tier 1.
     * * The announce URLs should *not* be shuffled.
     *
     * This is a *read* command.
     *
     * @throws IllegalArgumentException If [infohash] is not loaded.
     * @return Tier lists of announce URLs.
     */
    fun announces(infohash: String): CompletableFuture<List<List<String>>> {
        return database.announcesRead(infohash).thenApply { itList ->
            val torrentData = TorrentFile(infohash, itList)//throws IllegalArgumentException
            torrentData.announceList
        }
    }

    /**
     * Send an "announce" HTTP request to a single tracker of the torrent identified by [infohash], and update the
     * internal state according to the response. The specification for these requests can be found here:
     * [Tracker Protocol](https://wiki.theory.org/index.php/BitTorrentSpecification#Tracker_HTTP.2FHTTPS_Protocol).
     *
     * If [event] is [TorrentEvent.STARTED], shuffle the announce-list before selecting a tracker (future calls to
     * [announces] should return the shuffled list). See [BEP 12](http://bittorrent.org/beps/bep_0012.html) for more
     * information on shuffling and selecting a tracker.
     *
     * [event], [uploaded], [downloaded], and [left] should be included in the tracker request.
     *
     * The "compact" parameter in the request should be set to "1", and the implementation should support both compact
     * and non-compact peer lists.
     *
     * Peer ID should be set to "-CS1000-{Student ID}{Random numbers}", where {Student ID} is the first 6 characters
     * from the hex-encoded SHA-1 hash of the student's ID numbers (i.e., `hex(sha1(student1id + student2id))`), and
     * {Random numbers} are 6 random characters in the range [0-9a-zA-Z] generated at instance creation.
     *
     * If the connection to the tracker failed or the tracker returned a failure reason, the next tracker in the list
     * will be contacted and the announce-list will be updated as per
     * [BEP 12](http://bittorrent.org/beps/bep_0012.html).
     * If the final tracker in the announce-list has failed, then a [TrackerException] will be thrown.
     *
     * This is an *update* command.
     *
     * @throws TrackerException If the tracker returned a "failure reason". The failure reason will be the exception
     * message.
     * @throws IllegalArgumentException If [infohash] is not loaded.
     * @return The interval in seconds that the client should wait before announcing again.
     */
    fun announce(
        infohash: String,
        event: TorrentEvent,
        uploaded: Long,
        downloaded: Long,
        left: Long
    ): CompletableFuture<Int> {
        return database.announcesRead(infohash).thenApply { itList ->
            val torrentFile = TorrentFile(infohash, itList)//throws IllegalArgumentException
            if (event == TorrentEvent.STARTED) torrentFile.shuffleAnnounceList()
            torrentFile
        }.thenCompose {torrentFile ->
            val params = listOf(
                    "info_hash" to Utils.urlEncode(infohash),
                    "peer_id" to this.getPeerID(),
                    "port" to "6881", //Matan said to leave it like that, will be changed in future assignments
                    "uploaded" to uploaded.toString(),
                    "downloaded" to downloaded.toString(),
                    "left" to left.toString(),
                    "compact" to "1",
                    "event" to event.asString
            )
            torrentFile.announceTracker(params, database).whenComplete { t, u ->
                database.announcesUpdate(infohash, torrentFile.announceList)
            }.thenApply { response ->
                val peers: List<Map<String, String>> = getPeersFromResponse(response)
                addToPeers(infohash, peers)
                (response["interval"] as Long).toInt()
            }
        }
    }

    /**
     * Scrape all trackers identified by a torrent, and store the statistics provided. The specification for the scrape
     * request can be found here:
     * [Scrape Protocol](https://wiki.theory.org/index.php/BitTorrentSpecification#Tracker_.27scrape.27_Convention).
     *
     * All known trackers for the torrent will be scraped.
     *
     * This is an *update* command.
     *
     * @throws IllegalArgumentException If [infohash] is not loaded.
     */
    fun scrape(infohash: String): CompletableFuture<Unit> {
        return database.announcesRead(infohash).thenApply { itList ->
            val torrentFile = TorrentFile(infohash, itList)//throws IllegalArgumentException
            torrentFile.scrapeTrackers(database)?.thenApply { torrentAllStats->
                database.trackersStatsUpdate(infohash, torrentAllStats)
            }?.join()
            Unit
        }
    }

    /**
     * Invalidate a previously known peer for this torrent.
     *
     * If [peer] is not a known peer for this torrent, do nothing.
     *
     * This is an *update* command.
     *
     * @throws IllegalArgumentException If [infohash] is not loaded.
     */
    fun invalidatePeer(infohash: String, peer: KnownPeer): CompletableFuture<Unit> {
        return database.peersRead(infohash).thenApply { peersList ->
            val newPeerslist = peersList.filter { it->  (it["ip"] != peer.ip)  || (it["port"] != peer.port.toString()) }
            database.peersUpdate(infohash, newPeerslist)
        }
    }

    /**
     * Return all known peers for the torrent identified by [infohash], in sorted order. This list should contain all
     * the peers that the client can attempt to connect to, in ascending numerical order. Note that this is not the
     * lexicographical ordering of the string representation of the IP addresses: i.e., "127.0.0.2" should come before
     * "127.0.0.100".
     *
     * The list contains unique peers, and does not include peers that have been invalidated.
     *
     * This is a *read* command.
     *
     * @throws IllegalArgumentException If [infohash] is not loaded.
     * @return Sorted list of known peers.
     */
    fun knownPeers(infohash: String): CompletableFuture<List<KnownPeer>> {
        return database.peersRead(infohash).thenApply { peersList ->
            val sortedPeers = peersList.stream().map {
                    it -> KnownPeer(it["ip"] as String,it["port"]?.toInt() ?: 0,it["peer id"] as String?)
            }.sorted { o1, o2 -> Utils.compareIPs(o1.ip,o2.ip)}.toList()
            sortedPeers
        }
    }

    /**
     * Return all known statistics from trackers of the torrent identified by [infohash]. The statistics displayed
     * represent the latest information seen from a tracker.
     *
     * The statistics are updated by [announce] and [scrape] calls. If a response from a tracker was never seen, it
     * will not be included in the result. If one of the values of [ScrapeData] was not included in any tracker response
     * (e.g., "downloaded"), it would be set to 0 (but if there was a previous result that did include that value, the
     * previous result would be shown).
     *
     * If the last response from the tracker was a failure, the failure reason would be returned ([ScrapeData] is
     * defined to allow for this). If the failure was a failed connection to the tracker, the reason should be set to
     * "Connection failed".
     *
     * This is a *read* command.
     *
     * @throws IllegalArgumentException If [infohash] is not loaded.
     * @return A mapping from tracker announce URL to statistics.
     */
    fun trackerStats(infohash: String): CompletableFuture<Map<String, ScrapeData>> {
        return database.trackersStatsRead(infohash).thenApply { dbStatsMap ->
            val trackerStatsMap = hashMapOf<String, ScrapeData>()
            for ((trackerUrl, trackerValue) in dbStatsMap) {
                val trackerMap = trackerValue as Map<String, Any>
                if(trackerMap.containsKey("failure reason")) {
                    trackerStatsMap[trackerUrl] = Failure(trackerMap["failure reason"] as String)
                } else {
                    trackerStatsMap[trackerUrl] = Scrape((trackerMap["complete"]as Long).toInt(),
                        (trackerMap["downloaded"]as Long).toInt(),
                        (trackerMap["incomplete"]as Long).toInt(),
                        trackerMap["name"] as? String?)
                }
            }
            trackerStatsMap
        }
    }

    /**
     * Return information about the torrent identified by [infohash]. These statistics represent the current state
     * of the client at the time of querying.
     *
     * See [TorrentStats] for more information about the required data.
     *
     * This is a *read* command.
     *
     * @throws IllegalArgumentException if [infohash] is not loaded.
     * @return Torrent statistics.
     */
    fun torrentStats(infohash: String): CompletableFuture<TorrentStats> {
        var uploaded:Long = 0
        var downloaded:Long = 0
        var left:Long = 0
        var wasted:Long = 0
        var pieces:Long = 0
        var havePieces:Long = 0
        var shareRatio:Double = 0.0
        var leechTime:Duration = Duration.ZERO
        var seedTime:Duration = Duration.ZERO
        var torrent:MutableMap<String, Any> = mutableMapOf()
        return database.torrentsRead(infohash).thenCompose { it ->
            torrent = it.toMutableMap()
            database.piecesStatsRead(infohash)}.thenApply { pieceMap ->
                for ((index, pieceIndexStats) in pieceMap) {
                    uploaded += pieceIndexStats.uploaded
                    downloaded += pieceIndexStats.downloaded
                    left += pieceIndexStats.left
                    wasted += pieceIndexStats.wasted
                    havePieces += if (pieceIndexStats.isValid) 1 else 0
                    leechTime += pieceIndexStats.leechTime
                    seedTime += pieceIndexStats.seedTime
                }
                val havePieces: Long = pieceMap.filter { it-> it.value.isValid }.size.toLong()
                if (downloaded.toInt() == 0) {//haha it would be funny if there were no download time and it was zero
                    shareRatio = uploaded.toDouble()
                } else {
                    shareRatio = uploaded.toDouble() / downloaded.toDouble()
                }
            pieces = ((torrent["info"] as Map<String, Any>)["pieces"] as ByteArray).size.toLong() / 20 //pieces is a string build from 20 byte sha1(piece) * #pieces
            return@thenApply TorrentStats(uploaded, downloaded, left, wasted, shareRatio, pieces, havePieces, leechTime, seedTime)
        }
    }

    /**
     * Start listening for peer connections on a chosen port.
     *
     * The port chosen should be in the range 6881-6889, inclusive. Assume all ports in that range are free.
     *
     * For a given instance of [CourseTorrent], the port sent to the tracker in [announce] and the port chosen here
     * should be the same.
     *
     * This is a *update* command. (maybe)
     *
     * @throws IllegalStateException If already listening.
     */
    fun start(): CompletableFuture<Unit> {
        return CompletableFuture.supplyAsync {
            if(this.serverSocket != null) throw java.lang.IllegalStateException("Already listening")
            this.serverSocket = ServerSocket(6881)
        }
    }

    /**
     * Disconnect from all connected peers, and stop listening for new peer connections
     *
     * You may assume that this method is called before the instance is destroyed, and perform clean-up here.
     *
     * This is an *update* command. (maybe)
     *
     * @throws IllegalStateException If not listening.
     */
    fun stop(): CompletableFuture<Unit> {
        return CompletableFuture.supplyAsync {
            if(this.serverSocket == null) throw java.lang.IllegalStateException("Not listening")
            this.connectedPeers.forEach { (infohash, peers) ->
                peers.forEach { this.internalDisconnect(infohash, it.connectedPeer.knownPeer) }
            }
            this.connectedPeers.clear()
            this.serverSocket?.close()
            this.serverSocket = null
        }
    }

    /**
     * Connect to [peer] using the peer protocol described in [BEP 003](http://bittorrent.org/beps/bep_0003.html).
     * Only connections over TCP are supported. If connecting to the peer failed, an exception is thrown.
     *
     * After connecting, send a handshake message, and receive and process the peer's handshake message. The peer's
     * handshake will contain a "peer_id", and future calls to [knownPeers] should return this peer_id for this peer.
     *
     * If this torrent has anything downloaded, send a bitfield message.
     *
     * Wait 100ms, and in that time handle any bitfield or have messages that are received.
     *
     * In the handshake, the "reserved" field should be set to 0 and the peer_id should be the same as the one that was
     * sent to the tracker.
     *
     * [peer] is equal to (and has the same [hashCode]) an object that was returned by [knownPeers] for [infohash].
     *
     * After a successful connection, this peer should be listed by [connectedPeers]. Peer connections start as choked
     * and not interested for both this client and the peer.
     *
     * This is an *update* command. (maybe)
     *
     * @throws IllegalArgumentException if [infohash] is not loaded or [peer] is not known.
     * @throws PeerConnectException if the connection to [peer] failed (timeout, connection closed after handshake, etc.)
     */
    fun connect(infohash: String, peer: KnownPeer): CompletableFuture<Unit> {
        return this.knownPeers(infohash).thenApply {
            if (!it.contains(peer)) throw java.lang.IllegalArgumentException("Peer unknown")

            try {
                //get infohash as byte array
                val infohashByteArray = Utils.infohashToByteArray(infohash)
                //connect to peer
                val s = Socket(peer.ip, peer.port)
                //send handshake request
                s.getOutputStream().write(WireProtocolEncoder.handshake(infohashByteArray,
                        this.getPeerID().toByteArray()))
                //receive handshake
                val decodedHandshake = receiveHandshake(s)
                if(!decodedHandshake.infohash.contentEquals(infohashByteArray))
                    throw PeerConnectException("Infohashes do not agree")

                val newPeer = KnownPeer(peer.ip, peer.port, String(decodedHandshake.peerId))
                Triple(s, it, newPeer)
            }
            catch (e: Exception) {
                throw PeerConnectException("Peer connection failed")
            }

        }.thenCompose { (s, it, newPeer) ->
            addNewPeer(infohash, s, it, newPeer, peer)
        }
    }
    /**
     * list peer should be listed to the  [connectedPeers]
     **
     * @param infohash : the torrent infohash
     * @param s : socket of the new peer
     * @param kPeers : known peers
     * @param newPeer :peer to be added
     * @param peerToRemove : peer to be removed if it was already existed
     *
     */
    private fun addNewPeer(infohash: String, s: Socket, kPeers : List<KnownPeer>, newPeer: KnownPeer, peerToRemove: KnownPeer?) : CompletableFuture<Unit> {
        //update known peers with peer id
        val listKnownPeers = kPeers.toMutableList()
        if (peerToRemove != null) listKnownPeers.remove(peerToRemove)
        listKnownPeers.add(newPeer)
        database.peersUpdate(infohash, listKnownPeers.map { kPeer ->
            if (kPeer.peerId == null)
                return@map kotlin.collections.mapOf("ip" to kPeer.ip, "port" to kPeer.port.toString())
            else
                return@map kotlin.collections.mapOf("ip" to kPeer.ip, "port" to kPeer.port.toString(), "peer id" to kPeer.peerId)
        })
        //update connectedPeers
        if(!this.connectedPeers.containsKey(infohash))
            this.connectedPeers[infohash] = mutableListOf()

        val connectedPeerMan = ConnectedPeerManager(
                ConnectedPeer(newPeer, true, false, true, false,
                        0.0, 0.0),
                s, listOf<Long>().toMutableList(), listOf<Long>().toMutableList(), mutableMapOf(), 0L, Duration.ofMillis(0))
        this.connectedPeers[infohash]?.add(connectedPeerMan)
        //send bitfield message
        return database.piecesStatsRead(infohash).thenApply { piecesMap ->
            //If this torrent has anything downloaded, send a bitfield message.
            val bitfield = ByteArray(ceil(piecesMap.size / 8.0).toInt(), { 0 })
            val piecesWeHave = piecesMap.filter { it.value.isValid }.keys
            if(piecesWeHave.isNotEmpty()) {
                for (piece in piecesWeHave) {
                    val bytePlace = Math.pow(2.toDouble(), 7 - (piece.toDouble() % 8)).toByte() //1, 2, 4, 8, 16, 32, 64, 128
                    bitfield[piece.toInt() / 8] = bitfield[piece.toInt() / 8] or bytePlace
                }
                s.getOutputStream().write(WireProtocolEncoder.encode(5, bitfield))
            }
            //sleep 100 ms
            Thread.sleep(100)
            //receive bitfield or have messages
            connectedPeerMan.handleIncomingMessages()
        }
    }

    /**
     * Disconnect from [peer] by closing the connection.
     *
     * There is no need to send any messages.
     *
     * This is an *update* command. (maybe)
     *
     * @throws IllegalArgumentException if [infohash] is not loaded or [peer] is not connected.
     */
    fun disconnect(infohash: String, peer: KnownPeer): CompletableFuture<Unit> {
        return CompletableFuture.supplyAsync {
            val connectedPeer = this.connectedPeers[infohash]?.filter {
                connectedPeer -> connectedPeer.connectedPeer.knownPeer == peer
            }?.getOrNull(0) ?: throw java.lang.IllegalArgumentException("infohash or peer invalid")
            internalDisconnect(infohash, peer)
            this.connectedPeers[infohash]?.remove(connectedPeer)
            if(this.connectedPeers[infohash]?.isEmpty() ?: false) this.connectedPeers.remove(infohash)
        }
    }

    /**
     * Disconnect from [peer] by closing the connection, but does not remove the peer from connectedPeers
     * @throws IllegalArgumentException if [infohash] is not loaded or [peer] is not connected.
     */
    private fun internalDisconnect(infohash: String, peer: KnownPeer) {
        val connectedPeer = this.connectedPeers[infohash]?.filter {
            connectedPeer -> connectedPeer.connectedPeer.knownPeer == peer
        }?.getOrNull(0) ?: throw java.lang.IllegalArgumentException("infohash or peer invalid")
        connectedPeer.socket.close()
    }

    /**
     * Return a list of peers that this client is currently connected to, with some statistics.
     *
     * See [ConnectedPeer] for more information.
     *
     * This is a *read* command. (maybe)
     *
     * @throws IllegalArgumentException if [infohash] is not loaded.
     */
    fun connectedPeers(infohash: String): CompletableFuture<List<ConnectedPeer>> {
        return database.torrentsRead(infohash).thenApply { torrent->
            torrent ?: throw IllegalArgumentException()
            val totalBytes = getTorrentSize(torrent)
            this.connectedPeers.values.flatten().map { it ->
                val avgSpeed = if (it.leechTime.isZero) { 0.0 } else { it.downloaded * 1.0 / it.leechTime.seconds }
                it.connectedPeer = it.connectedPeer.copy(completedPercentage = it.downloaded * 1.0 / totalBytes, averageSpeed = avgSpeed)
                it.connectedPeer
            }
        }
    }
    /**
     * sums up all the files sizes
     *
     * @param  torrent: the meta-info Map that hold the torrent data
     *
     * @return the sum of the files sizes in the torrent
     */

    private fun getTorrentSize(torrent: Map<String, Any>): Long {
        val filesList = (torrent["info"] as Map<String, Any>)["files"] as? ArrayList<Map<String, Any>> ?: arrayListOf()

        if (filesList.isEmpty()) {
            val fileName = (torrent["info"] as Map<String, Any>)["name"] as String
            val fileLength = (torrent["info"] as Map<String, Any>)["length"] as Long
            filesList.add(mapOf("path" to listOf(fileName), "length" to fileLength))
        }

        val totalBytes = filesList.map { it -> it["length"] as Long }.sum()
        return totalBytes
    }


    /**
     * Send a choke message to [peer], which is currently connected. Future calls to [connectedPeers] should show that
     * this peer is choked.
     *
     * This is an *update* command. (maybe)
     *
     * @throws IllegalArgumentException if [infohash] is not loaded or [peer] is not connected.
     */
    fun choke(infohash: String, peer: KnownPeer): CompletableFuture<Unit> {
        return CompletableFuture.supplyAsync {
            val connectedPeer = this.connectedPeers[infohash]?.filter {
                connectedPeer -> connectedPeer.connectedPeer.knownPeer == peer
            }?.getOrNull(0) ?: throw java.lang.IllegalArgumentException("infohash or peer invalid")
            connectedPeer.socket.getOutputStream().write(WireProtocolEncoder.encode(0))
            connectedPeer.connectedPeer = connectedPeer.connectedPeer.copy(amChoking = true)
        }
    }

    /**
     * Send an unchoke message to [peer], which is currently connected. Future calls to [connectedPeers] should show
     * that this peer is not choked.
     *
     * This is an *update* command. (maybe)
     *
     * @throws IllegalArgumentException if [infohash] is not loaded or [peer] is not connected.
     */
    fun unchoke(infohash: String, peer: KnownPeer): CompletableFuture<Unit> {
        return CompletableFuture.supplyAsync {
            val connectedPeer = this.connectedPeers[infohash]?.filter {
                connectedPeer -> connectedPeer.connectedPeer.knownPeer == peer
            }?.getOrNull(0) ?: throw java.lang.IllegalArgumentException("infohash or peer invalid")
            connectedPeer.socket.getOutputStream().write(WireProtocolEncoder.encode(1))
            connectedPeer.connectedPeer = connectedPeer.connectedPeer.copy(amChoking = false)
        }
    }

    /**
     * Handle any messages that peers have sent, and send keep-alives if needed, as well as interested/not interested
     * messages.
     *
     * Messages to receive and handle from peers:
     *
     * 1. keep-alive: Do nothing.
     * 2. unchoke: Mark this peer as not choking in future calls to [connectedPeers].
     * 3. choke: Mark this peer as choking in future calls to [connectedPeers].
     * 4. have: Update the internal state of which pieces this client has, as seen in future calls to [availablePieces]
     * and [connectedPeers].
     * 5. request: Mark the peer as requesting a piece, as seen in future calls to [requestedPieces]. Ignore if the peer
     * is choked.
     * 6. handshake: When a new peer connects and performs a handshake, future calls to [knownPeers] and
     * [connectedPeers] should return it.
     *
     * Messages to send to each peer:
     *
     * 1. keep-alive: If it has been more than one minute since we sent a keep-alive message (it is OK to keep a global
     * count)
     * 2. interested: If the peer has a piece we don't, and we're currently not interested, send this message and mark
     * the client as interested in future calls to [connectedPeers].
     * 3. not interested: If the peer does not have any pieces we don't, and we're currently interested, send this
     * message and mark the client as not interested in future calls to [connectedPeers].
     *
     * These messages can also be handled by different parts of the code, as desired. In that case this method can do
     * less, or even nothing. It is guaranteed that this method will be called reasonably often.
     *
     * This is an *update* command. (maybe)
     */
    fun handleSmallMessages(): CompletableFuture<Unit> {
        return CompletableFuture.supplyAsync {
            //receive messages
            fun newPeerAcceptor() {
                serverSocket?.soTimeout = 1;
                val socket = try {
                    serverSocket?.accept()
                } catch(ex : SocketTimeoutException) {
                    null
                }
                if(socket != null) {
                    val decodedHandshake = this.receiveHandshake(socket)
                    val newInfohash = byteArrayToInfohash(decodedHandshake.infohash)
                    this.knownPeers(newInfohash).exceptionally { exception ->
                        listOf()
                    }.thenApply { it ->
                        if (it.isEmpty()) { //torrent does not exist
                            socket.close()
                            throw PeerConnectException("No such infohash")
                        } else { //torrent exists
                            val newPeer = KnownPeer(socket.inetAddress.hostAddress, socket.port,
                                    String(decodedHandshake.peerId))
                            socket.getOutputStream().write(WireProtocolEncoder.handshake(decodedHandshake.infohash,
                                    this.getPeerID().toByteArray()))
                            addNewPeer(newInfohash, socket, it, newPeer, null).join()
                        }
                        newPeerAcceptor()
                    }.join()
                }
            }
            newPeerAcceptor()
            connectedPeers.forEach { infohash, lst ->
                lst.forEach { peerManager -> peerManager.handleIncomingMessages() }
            }
            //send messages
            val now = System.currentTimeMillis()
            val timeElapsed = now - lastTimeKeptAlive
            if (timeElapsed > 60 * 1000) {
                connectedPeers.forEach { infohash, lst ->
                    lst.forEach { peerManager -> peerManager.sendKeepAlive() }
                }
                lastTimeKeptAlive = now
            }
            connectedPeers.forEach { infohash, lst ->
                database.piecesStatsRead(infohash).thenApply { piecesStatsWeHaveMap ->
                    val piecesWeHaveThatNotDamaged = piecesStatsWeHaveMap.filter { it -> it.value.isValid == true }
                    lst.forEach { peerManager -> peerManager.decideIfInterested(piecesWeHaveThatNotDamaged) }
                }.join()
            }
        }
    }


    /**
     * Download piece number [pieceIndex] of the torrent identified by [infohash].
     *
     * Attempt to download a complete piece by sending a series of request messages and receiving piece messages in
     * response. This method finishes successfully (i.e., the [CompletableFuture] is completed) once an entire piece has
     * been received, or an error.
     *
     * Requests should be of piece subsets of length 16KB (2^14 bytes). If only a part of the piece is downloaded, an
     * exception is thrown. It is unspecified whether partial downloads are kept between two calls to requestPiece:
     * i.e., on failure, you can either keep the partially downloaded data or discard it.
     *
     * After a complete piece has been downloaded, its SHA-1 hash will be compared to the appropriate SHA-1 has from the
     * torrent meta-info file (see 'pieces' in the 'info' dictionary), and in case of a mis-match an exception is
     * thrown and the downloaded data is discarded.
     *
     * This is an *update* command.
     *
     * @throws PeerChokedException if the peer choked the client before a complete piece has been downloaded.
     * @throws PeerConnectException if the peer disconnected before a complete piece has been downloaded.
     * @throws PieceHashException if the piece SHA-1 hash does not match the hash from the meta-info file.
     * @throws IllegalArgumentException if [infohash] is not loaded, [peer] is not known, or [peer] does not have [pieceIndex].
     */
    fun requestPiece(infohash: String, peer: KnownPeer, pieceIndex: Long): CompletableFuture<Unit> {
        return database.torrentsRead(infohash).thenApply {torrent->
                torrent ?: throw IllegalArgumentException("torrent does not exist")
                val info = torrent["info"] as Map<String, Any>
                val pieces = info?.get("pieces") as ByteArray

                //20 byte for each piece
                val piecesLen = pieces.size/20
                if (pieceIndex >= piecesLen) throw java.lang.IllegalArgumentException("wrongs piece index")

                val connectedPeer = this.connectedPeers[infohash]?.filter {
                    connectedPeer1 -> connectedPeer1.connectedPeer.knownPeer == peer
                }?.getOrNull(0) ?: throw java.lang.IllegalArgumentException("infohash or peer invalid")

                if (connectedPeer.connectedPeer.peerChoking) throw PeerChokedException("peer choked the client")

                try{
                    var peerMessage = PeerMessage(0,0,-1, byteArrayOf(0),-1)
                    var expectedBlockBeginOffset : Long = 0
                    val startTime = System.currentTimeMillis()

                    while(peerMessage.messageResult == -1){
                        expectedBlockBeginOffset = sendRequestMessage(info, pieceIndex, connectedPeer)

                        peerMessage = getPieceMessage(connectedPeer, info)//when the block is not empty the result from the read is number of bytes read otherwise it's -1

                        if (connectedPeer.connectedPeer.peerChoking) throw PeerChokedException("peer choked the client")
                    }

                    val elapsedTime = System.currentTimeMillis() - startTime
                    if (peerMessage.index != pieceIndex ||
                            peerMessage.begin != expectedBlockBeginOffset ||
                            ! infohashToByteArray(sha1hash(peerMessage.block)).contentEquals(pieces.sliceArray(IntRange((pieceIndex*20).toInt(),(pieceIndex*20).toInt()+19))) )
                        throw PieceHashException("piece does not match the hash from the meta-info file")

                    //update the stats db
                    database.piecesStatsRead(infohash).thenApply { immutabletorrentStats->
                        var torrentStats = updatePieceStatsFromSuccessfulPieceRequest(immutabletorrentStats, pieceIndex, peerMessage, elapsedTime)

                        connectedPeer.downloaded += (peerMessage.block.size)
                        connectedPeer.leechTime = connectedPeer.leechTime.plusMillis(elapsedTime)
                        torrentStats
                    }.thenApply { updatedTorrentStats ->
                        database.piecesStatsUpdate(infohash, updatedTorrentStats)
                        database.indexedPieceUpdate(infohash,pieceIndex, peerMessage.block)

                    }.join()
                }
                catch (e: Exception) {
                    throw PeerConnectException("Peer connection failed")
                }
            Unit
        }
    }
    /**
     * update stats about the piece in the stats DB
     *
     * @param  immutabletorrentStats : the meta-info Map that hold the torrent data
     * @param  pieceIndex : index of the piece sent
     * @param  peerMessage : data class that hold the data recieved fro mthe peer that sent the message
     * @param  elapsedTime : time the piece download took (milliseconds)
     *
     * @return torrentStats: a dictionary that hold all the pieces stats
     */

    private fun updatePieceStatsFromSuccessfulPieceRequest(immutabletorrentStats: Map<Long, PieceIndexStats>, pieceIndex: Long, peerMessage: PeerMessage, elapsedTime: Long): MutableMap<Long, PieceIndexStats> {
        var torrentStats = immutabletorrentStats.toMutableMap()
        var pieceStats = torrentStats[pieceIndex]!!
        pieceStats.downloaded = pieceStats.downloaded + (peerMessage.block.size)
        pieceStats.left = pieceStats.left - (peerMessage.block.size)
        pieceStats.leechTime = pieceStats.leechTime.plusMillis(elapsedTime)
        pieceStats.isValid = true
        torrentStats[pieceIndex] = pieceStats
        return torrentStats
    }
    /**
     * update stats about the piece in the stats DB
     *
     * @param  immutabletorrentStats : the meta-info Map that hold the torrent data
     * @param  pieceIndex : index of the piece sent
     * @param  peerMessage : struct that hold the data recieved fro mthe peer that sent the message
     * @param  elapsedTime : time the piece download took (milliseconds)
     *
     * @return PeerMessage: data class that hold the data recieved from the peer
     */
    private fun getPieceMessage(connectedPeer: ConnectedPeerManager, info: Map<String, Any>): PeerMessage {
        val inputStream = connectedPeer.socket.getInputStream()
        //excpect for this length
        val expectedPieceLength = info?.get("piece length") as Long ?: 16000
        if (inputStream.available() < 13 + expectedPieceLength) {
            return  PeerMessage(-1, -1, -1, byteArrayOf(0), -1)

        }
        val message = inputStream.readNBytes(13 + expectedPieceLength.toInt())
        val messageDecoded = WireProtocolDecoder.decode(message, 2)
        var recievedPieceIndex = messageDecoded.ints[0].toLong()
        var recievedBlockBeginOffset = messageDecoded.ints[1].toLong()
        //excpect for this length
        var recievedPieceBlock = messageDecoded.contents
        return  PeerMessage(recievedPieceIndex, recievedBlockBeginOffset, recievedPieceBlock.size.toLong(), recievedPieceBlock, 1)
    }
    /**
     * send request message to the peer
     *
     * @param  info : the meta-info Map that hold the torrent data
     * @param  pieceIndex : index of the piece sent
     * @param  connectedPeer : the peer that we send the message
     *
     * @return torrentStats: a dictionary that hold all the pieces stats
     */
    private fun sendRequestMessage(info: Map<String, Any>?, pieceIndex: Long, connectedPeer: ConnectedPeerManager): Long{
        val requestedPieceLength = info?.get("piece length") as Long

        val buffer = ByteBuffer.allocate(17)
        //message len which should be 13
        buffer.putInt(13)
        // message id
        buffer.put(6)
        //index: integer specifying the zero-based piece index
        buffer.putInt(pieceIndex.toInt())
        // begin: integer specifying the zero-based byte offset within the piece. all the pieces except the last one are the same size so the calculation is #pices*pieceSize
        val expectedBlockbegin = (0 + pieceIndex.toInt() * requestedPieceLength.toInt())
        buffer.putInt(expectedBlockbegin.toInt())
        //length: integer specifying the requested length. Requests should be of piece subsets of length 16KB (2^14 bytes) which is 16000 b
        buffer.putInt(requestedPieceLength.toInt())

        connectedPeer.socket.getOutputStream().write(buffer.array())

        return expectedBlockbegin.toLong()
    }

    /**
     * request: <len=0013><id=6><index><begin><length>
     */


    /**
     * Send piece number [pieceIndex] of the [infohash] torrent to [peer].
     *
     * Upload a complete piece (as much as possible) by sending a series of piece messages. This method finishes
     * successfully (i.e., the [CompletableFuture] is completed) if [peer] hasn't requested another subset of the piece
     * in 100ms.
     *
     * This is an *update* command. (maybe)
     *
     * @throws IllegalArgumentException if [infohash] is not loaded, [peer] is not known, or [peer] did not request [pieceIndex].
     */
    fun sendPiece(infohash: String, peer: KnownPeer, pieceIndex: Long): CompletableFuture<Unit> {
        return database.torrentsRead(infohash).thenCompose { torrent ->
                torrent ?: throw IllegalStateException("torrent does not exist")
                val info = torrent["info"] as Map<String, Any>
                database.indexedPieceRead(infohash,pieceIndex).thenApply { selectedPieceBlock->
                    val connectedPeer = this.connectedPeers[infohash]?.filter {
                        connectedPeer1 -> connectedPeer1.connectedPeer.knownPeer == peer
                    }?.getOrNull(0) ?: throw java.lang.IllegalArgumentException("infohash or peer invalid")

                    val startTime = System.currentTimeMillis()

                    var sent = 0

                    if (!connectedPeer.requestedPieces.contains(pieceIndex)){
                        throw java.lang.IllegalArgumentException("wrong piece index requested")

                    }
                    while (connectedPeer.requestedPieces.contains(pieceIndex)){
                        sent = sendPieceMessage(info, selectedPieceBlock, pieceIndex, connectedPeer)
                        sleep (100)
                        connectedPeer.handleIncomingMessages()
                    }

                    if (sent > 0) {
                        val elapsedTime = System.currentTimeMillis() - startTime

                        database.piecesStatsRead(infohash).thenApply { piecesMap ->
                            var torrentStats = piecesMap.toMutableMap()
                            var pieceStats  = torrentStats[pieceIndex]!!
                            pieceStats.uploaded = pieceStats.uploaded + sent
                            pieceStats.seedTime = pieceStats.seedTime.plusMinutes(elapsedTime)
                            torrentStats[pieceIndex] = pieceStats
                            torrentStats
                        }.thenApply {
                            database.piecesStatsUpdate(infohash,it)
                        }.join()
                    }

                }
        }
    }
    /**
     * Send piece number [pieceIndex] of the torrent to connectedPeer.
     *
     * @param  info : the meta-info Map that hold the torrent data
     * @param  selectedPieceBlock : the piece sent
     * @param  pieceIndex : index of the piece sent
     * @param  connectedPeer : the peer that we send the message
     *
     * @return torrentStats: the length of the message recieved from the peer
     */

    private fun sendPieceMessage(info: Map<String, Any>, selectedPieceBlock: ByteArray, pieceIndex: Long, connectedPeer: ConnectedPeerManager) : Int {
        val request = connectedPeer.requestedPiecesDetails[pieceIndex] ?: throw IllegalStateException("no request")
        val buffer = ByteBuffer.allocate(13 + request.length.toInt())
        buffer.putInt(9 + request.length.toInt()) //message length
        val msg_id = 7
        buffer.put(msg_id.toByte()) //message id
        buffer.putInt(pieceIndex.toInt()) //index
        // begin: integer specifying the zero-based byte offset within the piece. all the pieces except the last one are the same size so the calculation is #pices*pieceSize
        val blockbegin = request.begin.toInt()
        buffer.putInt(blockbegin) //begin
        buffer.put(selectedPieceBlock.sliceArray(IntRange(blockbegin, blockbegin + request.length.toInt() - 1)))
        connectedPeer.socket.getOutputStream().write(buffer.array())
        connectedPeer.requestedPieces.remove(pieceIndex)
        connectedPeer.requestedPiecesDetails.remove(pieceIndex)
        return request.length.toInt()
    }


    /**
     * List pieces that are currently available for download immediately.
     *
     * That is, pieces that:
     * 1. We don't have yet,
     * 2. A peer we're connected to does have,
     * 3. That peer is not choking us.
     *
     * Returns a mapping from connected, unchoking, interesting peer to a list of maximum length [perPeer] of pieces
     * that meet the above criteria. The lists may overlap (contain the same piece indices). The pieces in the list
     * should begin at [startIndex] and continue sequentially in a cyclical manner up to `[startIndex]-1`.
     *
     * For example, there are 3 pieces, we don't have any of them, and we are connected to PeerA that has piece 1 and
     * 2 and is not choking us. So, `availablePieces(infohash, 3, 2) => {PeerA: [2, 1]}`.
     *
     * This is a *read* command. (maybe)
     *
     * @throws IllegalArgumentException if [infohash] is not loaded.
     * @return Mapping from peer to a list of [perPeer] pieces that can be downloaded from it, starting at [startIndex].
     */
    fun availablePieces(
        infohash: String,
        perPeer: Long,
        startIndex: Long
    ): CompletableFuture<Map<KnownPeer, List<Long>>> {
        val res = mutableMapOf<KnownPeer, List<Long>>()
        return database.piecesStatsRead(infohash).thenApply { piecesStatesWeHaveMap ->//instead of loading the pieces whic hare heavy sized we get through their stats
                piecesStatesWeHaveMap ?: throw IllegalStateException("torrent does not exist")
                var mutablePiecesWehaveMap = piecesStatesWeHaveMap.toMutableMap().filter { it -> it.value.isValid }
                this.connectedPeers[infohash]?.filter { connectedPeer -> !connectedPeer.connectedPeer.peerChoking }?.forEach { unchokedPeer ->
                    val piecesWeWantFromPeer = unchokedPeer.availablePieces.filter { index -> !mutablePiecesWehaveMap.containsKey(index) }
                    val indexBiggerOrEqualThanIndex = piecesWeWantFromPeer.filter { index -> index >= startIndex }.map { it -> it.toLong() as Long}.toMutableList()
                    val indexSmallerThanIndex = piecesWeWantFromPeer.filter { index -> index < startIndex }.map { it -> it.toLong()  as Long}.toMutableList()
                    indexBiggerOrEqualThanIndex.addAll(indexSmallerThanIndex)
                    val peerAvailableList = indexBiggerOrEqualThanIndex.stream().limit(perPeer).toList()
                    res[unchokedPeer.connectedPeer.knownPeer] = peerAvailableList
                }
            res
            }
    }

    /**
     * List pieces that have been requested by (unchoked) peers.
     *
     * If a a peer sent us a request message for a subset of a piece (possibly more than one), that piece will be listed
     * here.
     *
     * @throws IllegalArgumentException if [infohash] is not loaded.
     * @return Mapping from peer to a list of unique pieces that it has requested.
     */
    fun requestedPieces(
        infohash: String
    ): CompletableFuture<Map<KnownPeer, List<Long>>> {
        val res = mutableMapOf<KnownPeer, List<Long>>()
        return database.announcesRead(infohash).thenApply {
            it ?: throw IllegalStateException("torrent does not exist")
            this.connectedPeers[infohash]?.filter { connectedPeer -> !connectedPeer.connectedPeer.amChoking }?.forEach{ peer->
                res[peer.connectedPeer.knownPeer] = peer.requestedPieces.map{ it -> it.toLong()}.toList()
            }
            res
        }
    }

    /**
     * Return the downloaded files for torrent [infohash].
     *
     * Partially downloaded files are allowed. Bytes that haven't been downloaded yet are zeroed.
     * File names are given including path separators, e.g., "foo/bar/file.txt".
     *
     * This is a *read* command.
     *
     * @throws IllegalArgumentException if [infohash] is not loaded.
     * @return Mapping from file name to file contents.
     */
    fun files(infohash: String): CompletableFuture<Map<String, ByteArray>> {
        val res = mutableMapOf<String, ByteArray>()
        return database.piecesStatsRead(infohash).thenApply {
            val piecesStatsThatAlreadyBeenDownloadedMap= it.toMutableMap()
            val indexesWeHave =piecesStatsThatAlreadyBeenDownloadedMap.filter{ it -> it.value.isValid }.map { it -> it.key }
            database.torrentsRead(infohash).thenApply {
                //val pieces = it["pieces"] as String

                val pieces = ((it["info"] as Map<String, Any>)["pieces"] as ByteArray).size
                val piecelength = ((it["info"] as Map<String, Any>)["piece length"] as Long)


                val files = (it["info"] as Map<String,Any>)["files"] as? ArrayList<Map<String,Any>> ?: arrayListOf()

                if(files.isEmpty()) {
                    val fileName = (it["info"] as Map<String, Any>)["name"] as String
                    val fileLength = (it["info"] as Map<String, Any>)["length"] as Long
                    files.add(mapOf("path" to listOf(fileName), "length" to fileLength))
                }

                var fileOffset = 0

                val numOfPieces = pieces/20
                var entireByteArray = byteArrayOf()
                for( i in 0.until(numOfPieces)){
                    if (i.toLong() in indexesWeHave){
                        database.indexedPieceRead(infohash,i.toLong()).thenAccept { pieceContent -> entireByteArray += pieceContent }.get()
                    }else{
                        entireByteArray += ByteArray(piecelength.toInt(), { 0 })
                    }
                }
                for (fileMap in files){
                    var fileLength = fileMap["length"] as Long
                    if (indexesWeHave.filter { index -> index* piecelength in fileOffset..(fileOffset+fileLength) }.isNotEmpty()){
                        var currentfileEntireByteArray = entireByteArray.copyOfRange(fileOffset,fileOffset+ fileLength.toInt())//if we add more zero bye array than we should
                        var filePath = fileMap["path"] as List<String>
                        val fileNameAndPath: String = "" + filePath.joinToString("/") //the path as required in the doc
                        res[fileNameAndPath] = currentfileEntireByteArray
                    }
                    fileOffset += fileLength.toInt()
                }
            }.join()
            res
        }
    }

    /**
     * Load files into the client.
     *
     * If [files] has extra files, they are ignored. If it is missing a file, it is treated as all zeroes. If file
     * contents are too short, the file is padded with zeroes. If the file contents are too long, they are truncated.
     *
     * @param files A mapping from filename to file contents.
     * @throws IllegalArgumentException if [infohash] is not loaded,
     */
    fun loadFiles(infohash: String, files: Map<String, ByteArray>): CompletableFuture<Unit> {
        var allfilesPieces : ByteArray = byteArrayOf()
        var pieceLength : Long = 0
        var pieces: ByteArray = ByteArray(0)
        return database.torrentsRead(infohash).thenApply { torrent->
            torrent ?: throw IllegalStateException("torrent does not exist")

            allfilesPieces = joinAllFilesToOneByteArray(torrent, allfilesPieces, files)

            pieces = (torrent["info"] as Map<String, Any>)["pieces"] as ByteArray
            pieceLength = (torrent["info"] as Map<String, Any>)["piece length"] as Long

            return@thenApply pieces
        }.thenCompose { pieces ->
            database.piecesStatsRead(infohash)
        }.thenApply {immutablepiecesStatsMap->
                        var piecesStatsMap = immutablepiecesStatsMap.toMutableMap()
                        var allfilesPiecesIndex = 0
                        var indexMap =0
                        var stopIterating =false
                        for (i in pieces.indices step 20){
                            var endOfCopyRange = allfilesPiecesIndex+pieceLength.toInt()
                            if (allfilesPiecesIndex+pieceLength.toInt() > allfilesPieces.size){
                                endOfCopyRange = allfilesPieces.size
                                stopIterating = true
                            }
                            val valueToStoreInThatPieceStorage = allfilesPieces.copyOfRange(allfilesPiecesIndex,endOfCopyRange)
                            allfilesPiecesIndex += pieceLength.toInt()
                            database.indexedPieceUpdate(infohash, indexMap.toLong(), valueToStoreInThatPieceStorage)
                            piecesStatsMap[indexMap.toLong()]?.isValid  = true
                            indexMap++
                             if (stopIterating) break
                        }
            database.piecesStatsUpdate(infohash, piecesStatsMap)
            }

    }
    /**
     * concatinate  all the files to one bytearray
     *
     * @param  torrent : the meta-info Map that hold the torrent data
     * @param  allfilesPieces : the meta-info Map that hold the torrent data
     * @param  files : the piece sent

     *
     * @return allfilesPieces1: bytearray the concatinate  all the files to one file
     */

    private fun joinAllFilesToOneByteArray(torrent: Map<String, Any>, allfilesPieces: ByteArray, files: Map<String, ByteArray>): ByteArray {
        var allfilesPieces1 = allfilesPieces
        val torrentFiles = (torrent["info"] as Map<String, Any>)["files"] as? ArrayList<Map<String, *>> ?: arrayListOf()
        if (torrentFiles.isNotEmpty()) {// multiple  file mode
            //get the file order
            var filesList: ArrayList<Map<String, *>> = torrentFiles
            for (fileMap: Map<String, *> in filesList) {
                val filePathList = fileMap["path"] as List<String>
                var fileName = filePathList[filePathList.lastIndex]

                allfilesPieces1 += files[fileName] as ByteArray
            }

        } else {// single  file mode
            val fileName = (torrent["info"] as Map<String, Any>)["name"] as String
            allfilesPieces1 = files[fileName] as ByteArray
        }
        return allfilesPieces1
    }

    /**
     * Compare SHA-1 hash for the loaded pieces of torrent [infohash] against the meta-info file. If a piece fails hash
     * checking, it is zeroed and marked as not downloaded.
     *
     * @throws IllegalArgumentException if [infohash] is not loaded.
     * @return True if all the pieces have been downloaded and passed hash checking, false otherwise.
     */
    fun recheck(infohash: String): CompletableFuture<Boolean> {
        var isAllPiecesOk = true
        return database.torrentsRead(infohash).thenApply { torrent ->
            torrent ?: throw IllegalStateException("torrent does not exist")
            val pieces = (torrent["info"] as Map<String, Any>)["pieces"] as ByteArray
            return@thenApply pieces
        }.thenCompose { pieces ->
            var i = 0
            database.piecesStatsRead(infohash).thenApply { loadedPiecesMap ->
                val mutableloadedPiecesMap = loadedPiecesMap.toMutableMap()
                for (index in mutableloadedPiecesMap.keys.sorted()) {
                    database.indexedPieceRead(infohash,index).thenAccept { pieceContent->
                        if (! infohashToByteArray(sha1hash(pieceContent)).contentEquals(pieces.sliceArray(IntRange(i,i+19)))){
                            isAllPiecesOk = false
                            database.indexedPieceUpdate(infohash,index,ByteArray(0))
                            mutableloadedPiecesMap[index]!!.isValid = false
                        }
                        i +=20
                    }.join()
                }
                database.piecesStatsUpdate(infohash,mutableloadedPiecesMap)
            }
        }.thenApply {
            isAllPiecesOk
        }
    }
    /**
     * Returns the peer ID of the client
     * Peer ID should be set to "-CS1000-{Student ID}{Random numbers}", where {Student ID} is the first 6 characters
     * from the hex-encoded SHA-1 hash of the student's ID numbers (i.e., `hex(sha1(student1id + student2id))`), and
     * {Random numbers} are 6 random characters in the range [0-9a-zA-Z] generated at instance creation.
     */
    private fun getPeerID(): String {
        val studentIDs = "206989105308328467"
        val builder = StringBuilder()
        builder.append("-CS1000-")
        builder.append(Utils.sha1hash(studentIDs.toByteArray()).substring(0, 6))
        builder.append(alphaNumericID)
        return builder.toString()
    }
    /**
     * add peers to the list of
     *
     * @param  infohash : the relevent torrent
     * @param  newPeers : list of the peers to be added connected peers
     *
     */
    private fun addToPeers(infohash : String, newPeers : List<Map<String, String>>) {

        database.peersRead(infohash).thenApply() { it ->
            val currPeers = it.toSet().toMutableSet()
            currPeers.addAll(newPeers) //Matan said that peers with same IP, same port, but different peer id will not be tested
            database.peersUpdate(infohash, currPeers.toList())
        }.join()
    }

    /**
     * If the response is not compact, return string as-is. Otherwise, turn the compact string
     * into non-compact and then return
     */
    private fun getPeersFromResponse(response: Map<String, Any>):List<Map<String, String>> {
        assert(response.containsKey("peers"))
        if(response["peers"] is List<*>) {
            return response["peers"] as List<Map<String, String>>
        }
        else {
            val peersByteArray = response["peers"] as ByteArray
            val peers = mutableListOf<Map<String, String>>()
            var i = 0
            while(i < peersByteArray.size) {
                peers.add(mapOf(
                    "ip" to (peersByteArray[i].toUByte().toInt().toString() + "." + peersByteArray[i+1].toUByte().toInt().toString() + "."
                            + peersByteArray[i+2].toUByte().toInt().toString() + "." + peersByteArray[i+3].toUByte().toInt().toString()),
                    "port" to (peersByteArray[i+4].toUByte().toInt() * 256 + peersByteArray[i+5].toUByte().toInt()).toString()
                ))
                i += 6
            }
            return peers
        }
    }
    /**
     * return a handshake respone from the peer
     *
     * @param  s : the relevent torrent
     *
     * return the decoded handshake
     */
    private fun receiveHandshake(s : Socket) : DecodedHandshake {
        val receivedMessage = ByteArray(68)
        s.getInputStream().read(receivedMessage)
        if(receivedMessage[0] != 19.toByte()) throw PeerConnectException("First byte is not 19")
        val decodedHandshake = WireProtocolDecoder.handshake(receivedMessage)
        return decodedHandshake
    }
}
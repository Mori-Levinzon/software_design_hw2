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
import java.lang.Thread.sleep
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.time.Duration
import java.util.concurrent.CompletableFuture
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
                    database.peersCreate(infohash, listOf())
                    database.trackersStatsCreate(infohash, mapOf())

                    val metaInfo = Ben(torrent).decode() as Map<String, Any>
                    val numOfPieces = ((metaInfo["info"] as Map<String,Any>)["pieces"] as ByteArray).size /20
                    val piecesMap : MutableMap<Long,PieceIndexStats> = mutableMapOf()
                    for (i in 0 until numOfPieces){
                        piecesMap[i.toLong()] = PieceIndexStats(0,0,0,0,false, Duration.ZERO, Duration.ZERO)
                    }
                    database.piecesStatsCreate(infohash, piecesMap.toMap() )
//                    database.piecesStatsCreate(infohash, mapOf() )

                    database.torrentsCreate(infohash, Ben(torrent).decode() as Map<String, Any>)
                    database.allpiecesCreate(infohash, numOfPieces.toLong())
                    //TODO make database for each piece
                }catch (e: IllegalStateException) {
                    e.printStackTrace()
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
                    database.allpiecesDelete(infohash,numOfPieces.toLong())//TODO: create a read for the number of pieces that thier DB need to be destroyed
                }.thenApply {
            try {
                database.announcesDelete(infohash)
                this.connectedPeers.remove(infohash)
                database.peersDelete(infohash)
                database.trackersStatsDelete(infohash)
                database.piecesStatsDelete(infohash)
                database.torrentsDelete(infohash)
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
            }
            val zero =0
            //update the current stats of the torrent file in the stats db
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
                    seedTime += pieceIndexStats.leechTime
                }
                val havePieces: Long = pieceMap.size.toLong()
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
                peers.forEach { this.disconnect(infohash, it.connectedPeer.knownPeer) }
            }
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
                addNewPeer(infohash, s, it, newPeer, peer)
            }
            catch (e: Exception) {
                e.printStackTrace()
                throw PeerConnectException("Peer connection failed")
            }


        }
    }

    private fun addNewPeer(infohash: String, s: Socket, kPeers : List<KnownPeer>, newPeer: KnownPeer, peerToRemove: KnownPeer?) {
        //send bitfield message
        //TODO If this torrent has anything downloaded, send a bitfield message.
        //receive bitfield message
        //Thread.sleep(100) //TODO maybe withTimeout instead of waiting
        //TODO Wait 100ms, and in that time handle any bitfield or have messages that are received.
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

        this.connectedPeers[infohash]?.add(ConnectedPeerManager(
                ConnectedPeer(newPeer, true, false, true, false,
                        0.0, 0.0),
                s, listOf<Long>().toMutableList(), listOf<Long>().toMutableList()))
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
            connectedPeer.socket.close()
            this.connectedPeers[infohash]?.remove(connectedPeer)
            if(this.connectedPeers[infohash]?.isEmpty() ?: false) this.connectedPeers.remove(infohash)
        }
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
        return CompletableFuture.supplyAsync {
            //TODO: perhaps compute average speed here?
            this.connectedPeers.values.flatten().map { it -> it.connectedPeer }
        }
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
                            addNewPeer(newInfohash, socket, it, newPeer, null)
                            socket.getOutputStream().write(WireProtocolEncoder.handshake(decodedHandshake.infohash,
                                    this.getPeerID().toByteArray()))
                        }
                        newPeerAcceptor()
                    }
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
                }
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
                torrent ?: throw IllegalStateException("torrent does not exist")
                val info = torrent["info"] as Map<String, Any>
                val pieces = info?.get("pieces") as String

                //20 byte for each piece
                val piecesLen = pieces.length/20
                if (pieceIndex >= piecesLen) throw java.lang.IllegalArgumentException("wrongs piece index")

                val connectedPeer = this.connectedPeers[infohash]?.filter {
                    connectedPeer1 -> connectedPeer1.connectedPeer.knownPeer == peer
                }?.getOrNull(0) ?: throw java.lang.IllegalArgumentException("infohash or peer invalid")
                if (connectedPeer.connectedPeer.peerChoking) throw PeerChokedException("peer choked the client")

                try{
                    val expectedBlockbegin = sendRequestMessage(info, pieceIndex, connectedPeer)

                    val (recievedPieceIndex, recievedBlockBegin, block) = recievePieceMessage(connectedPeer, info)

                    if (recievedPieceIndex != pieceIndex.toInt() ||
                            recievedBlockBegin != expectedBlockbegin ||
                            sha1hash(block) != pieces[pieceIndex.toInt()].toString()) throw PieceHashException("piece does not match the hash from the meta-info file")

                    //update the stats db
                    database.piecesStatsRead(infohash).thenApply { immutabletorrentStats->
                        var torrentStats = immutabletorrentStats.toMutableMap()
                        var pieceStats  = torrentStats[pieceIndex]!!
                        pieceStats.downloaded = pieceStats.downloaded + (block.size)
                        pieceStats.left = (pieceStats.left ?: (info?.get("pieces") as Long ?: 16000 ) ) - (block.size)
                        pieceStats.isValid = true
                        //TODO: what other data should be save except the two rows above
                        torrentStats[pieceIndex] = pieceStats
                        torrentStats
                    }.thenApply { updatedTorrentStats ->
                        database.piecesStatsUpdate(infohash, updatedTorrentStats)
                    }
                }
                catch (e: Exception) {
                    e.printStackTrace()
                    throw PeerConnectException("Peer connection failed")
                }
            val one =1
        }
    }

    private fun recievePieceMessage(connectedPeer: ConnectedPeerManager, info: Map<String, Any>?): Triple<Int, Int, ByteArray> {
        //length of piece message is 17
        val inputStream = connectedPeer.socket.getInputStream()
        val recievedPieceIndex = inputStream.read()
        val recievedBlockBegin = inputStream.read()
        val expectedPieceLength = info?.get("pieces") as Int ?: 16000
        val block = ByteArray(expectedPieceLength)
        inputStream.read(block)
        return Triple(recievedPieceIndex, recievedBlockBegin, block)
    }

    private fun sendRequestMessage(info: Map<String, Any>?, pieceIndex: Long, connectedPeer: ConnectedPeerManager): Int{
        val requestedPieceLength = info?.get("pieces") as Int ?: 16000

        val buffer = ByteBuffer.allocate(17)
        //message len which should be 13
        buffer.putInt(13)
        // message id
        buffer.put(6)
        //index: integer specifying the zero-based piece index
        buffer.putInt(pieceIndex.toInt())
        // begin: integer specifying the zero-based byte offset within the piece. all the pieces except the last one are the same size so the calculation is #pices*pieceSize
        val expectedBlockbegin = (0 + pieceIndex * requestedPieceLength).toInt()
        buffer.putInt(expectedBlockbegin)
        //length: integer specifying the requested length. Requests should be of piece subsets of length 16KB (2^14 bytes) which is 16000 b
        buffer.putInt(requestedPieceLength)

        connectedPeer.socket.getOutputStream().write(buffer.array())

        return expectedBlockbegin
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
        return database.torrentsRead(infohash).thenApply { torrent ->
                torrent ?: throw IllegalStateException("torrent does not exist")
                val info = torrent["info"] as Map<String, Any>
                val pieces = info?.get("pieces") as String
                database.indexedPieceRead(infohash,pieceIndex).thenApply { selectedPieceBlock->
                    val connectedPeer = this.connectedPeers[infohash]?.filter {
                        connectedPeer1 -> connectedPeer1.connectedPeer.knownPeer == peer
                    }?.getOrNull(0) ?: throw java.lang.IllegalArgumentException("infohash or peer invalid")
                    if (connectedPeer.connectedPeer.peerChoking) throw IllegalArgumentException("peer choked the client")


                    while (checkForPeerPieceRequest(connectedPeer, selectedPieceBlock)){
                        sendPieceMessage(info, selectedPieceBlock, pieceIndex, connectedPeer)

                        sleep (100)
                    }

                }
            val zero =0


        }
    }

    private fun sendPieceMessage(info: Map<String, Any>, selectedPieceBlock: ByteArray, pieceIndex: Long, connectedPeer: ConnectedPeerManager) {
        val requestedPieceLength = info?.get("pieces") as Int ?: 16000
        val buffer = ByteBuffer.allocate(17)
        buffer.putInt(9 + selectedPieceBlock.size)
        val msg_id = 7
        buffer.put(msg_id.toByte())
        buffer.putInt(pieceIndex.toInt())
        // begin: integer specifying the zero-based byte offset within the piece. all the pieces except the last one are the same size so the calculation is #pices*pieceSize
        val blockbegin = (0 + pieceIndex * requestedPieceLength).toInt()
        buffer.putInt(blockbegin)
        buffer.put(selectedPieceBlock)
        connectedPeer.socket.getOutputStream().write(buffer.array())
    }

    private fun checkForPeerPieceRequest(connectedPeer: ConnectedPeerManager, selectedPieceBlock: ByteArray) : Boolean{
        val inputStream = connectedPeer.socket.getInputStream()
        val pieceIndex = inputStream.read()
        val begin = inputStream.read()
        val block = ByteArray(selectedPieceBlock.size)
        inputStream.read(block)
        if (block.isEmpty()){
            throw IllegalArgumentException("peer did not send request")
            return false
        }else{
            return true
        }
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
                val piecelength = ((it["info"] as Map<String, Any>)["piece length"] as Long)
                val files = it["files"] as Map<String,Map<String,Any>> //TODO ["info"]
                var fileOffset = 0
                for ((file, fileMap) in files){
                    var fileEntireByteArray = byteArrayOf()
                    var fileLength = fileMap["length"] as Long
                    var piecesWeHaveOfThisFile = indexesWeHave.filter { index -> index*piecelength  in  fileOffset..(fileOffset + fileLength)}
                    if (piecesWeHaveOfThisFile.isNotEmpty()){
                        for( i in piecesWeHaveOfThisFile.min()!!..piecesWeHaveOfThisFile.max()!!){
                            if (i in indexesWeHave){
                                database.indexedPieceRead(infohash,i).thenAccept { pieceContent -> fileEntireByteArray += pieceContent }
                            }else{
                                fileEntireByteArray += ByteArray(piecelength.toInt())
                            }
                        }
                        fileEntireByteArray = fileEntireByteArray.copyOfRange(0,fileLength.toInt())//if we add more zero bye array than we should
                        var filePath = Ben((fileMap["path"] as String).toByteArray()).decode() as List<String>
                        val fileNameAndPath: String = "" + filePath.joinToString("/") //the path as required in the doc
                        res[fileNameAndPath] = fileEntireByteArray
                    }
                    fileOffset += piecelength.toInt()
                }
            }
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

    private fun joinAllFilesToOneByteArray(torrent: Map<String, Any>, allfilesPieces: ByteArray, files: Map<String, ByteArray>): ByteArray {
        var allfilesPieces1 = allfilesPieces
        val torrentFiles = (torrent["info"] as Map<String, Any>)["files"] as ArrayList<Map<String, *>>
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
                    }.join() //TODO remove
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

    private fun addToPeers(infohash : String, newPeers : List<Map<String, String>>) : CompletableFuture<Unit>{

        return database.peersRead(infohash).thenApply() { it ->
            val currPeers = it.toSet().toMutableSet()
            currPeers.addAll(newPeers) //Matan said that peers with same IP, same port, but different peer id will not be tested
            database.peersUpdate(infohash, currPeers.toList())
        }
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

    private fun receiveHandshake(s : Socket) : DecodedHandshake {
        val receivedMessage = ByteArray(68)
        s.getInputStream().read(receivedMessage) //TODO I assume this is blocking, if it isn't - do busy wait
        if(receivedMessage[0] != 19.toByte()) throw PeerConnectException("First byte is not 19")
        val decodedHandshake = WireProtocolDecoder.handshake(receivedMessage)
        return decodedHandshake
    }
}
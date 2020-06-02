@file:Suppress("UNUSED_PARAMETER")

package il.ac.technion.cs.softwaredesign

import com.google.inject.Inject
import il.ac.technion.cs.softwaredesign.exceptions.PeerChokedException
import il.ac.technion.cs.softwaredesign.exceptions.PeerConnectException
import il.ac.technion.cs.softwaredesign.exceptions.PieceHashException
import il.ac.technion.cs.softwaredesign.exceptions.TrackerException
import java.net.ServerSocket
import java.net.Socket
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
    private var serverSocketSubSocket: Socket? = null
    private val connectedPeers : MutableMap<String, MutableList<ConnectedPeer>> = mutableMapOf()
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
        val torrentData = TorrentFile.deserialize(torrent)
        val infohash = torrentData.infohash
        return database.torrentsCreate(infohash, torrentData.announceList).thenApply { torrentFuture ->
            torrentFuture ?: throw IllegalStateException("Same infohash already loaded")
        }.thenCompose {
            database.peersCreate(infohash, listOf())
        }.thenCompose {
            database.statsCreate(infohash, mapOf())
        }.thenApply {
            infohash
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
        return database.torrentsDelete(infohash)
                .thenApply { torrentsDeleteFuture -> torrentsDeleteFuture ?: throw IllegalArgumentException("Infohash doesn't exist") }
                .thenCompose {database.peersDelete(infohash) }
                .thenApply { peersDeleteFuture -> peersDeleteFuture ?: throw IllegalArgumentException("Infohash doesn't exist") }
                .thenCompose {database.statsDelete(infohash) }
                .thenApply { statsDeleteFuture -> statsDeleteFuture ?: throw IllegalArgumentException("Infohash doesn't exist") }
                .thenApply { this.connectedPeers.remove(infohash) }
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
        return database.torrentsRead(infohash).thenApply { itList ->
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
        return database.torrentsRead(infohash).thenApply { itList ->
            var torrentFile = TorrentFile(infohash, itList)//throws IllegalArgumentException
            if (event == TorrentEvent.STARTED) torrentFile.shuffleAnnounceList()
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

            torrentFile.announceTracker(params, database).thenApply { response ->
                database.torrentsUpdate(infohash, torrentFile.announceList).thenApply { }
                val peers: List<Map<String, String>> = getPeersFromResponse(response)
                addToPeers(infohash, peers)
                response
            }.thenApply { response ->
                (response["interval"] as Long).toInt()
            }.get()
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
        return database.torrentsRead(infohash).thenApply { itList ->
            val torrentFile = TorrentFile(infohash, itList)//throws IllegalArgumentException
            torrentFile.scrapeTrackers(database)?.thenApply { torrentAllStats->
                database.statsUpdate(infohash, torrentAllStats)
            }
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
        return database.statsRead(infohash).thenApply { dbStatsMap ->
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
        var shareRatio:Double = 0.0
        var pieces:Long = 0
        var havePieces:Long = 0
        var leechTime:Duration = Duration.ZERO
        var seedTime:Duration = Duration.ZERO
        return database.statsRead(infohash).thenApply { dbStatsMap ->
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
                uploaded += trackerMap?.get("uploaded") as? Long ?: 0
                downloaded += trackerMap?.get("downloaded") as? Long ?: 0
                left += trackerMap?.get("left") as? Long ?: 0
                wasted += trackerMap?.get("wasted") as? Long ?: 0
                shareRatio += trackerMap?.get("shareRatio") as? Long ?: 0
                pieces += trackerMap?.get("pieces") as? Long ?: 0
                havePieces += trackerMap?.get("havePieces") as? Long ?: 0
                leechTime += trackerMap?.get("leechTime") as? Duration ?: Duration.ZERO
                seedTime += trackerMap?.get("seedTime") as? Duration ?: Duration.ZERO
            }

            return@thenApply TorrentStats(uploaded,downloaded,left,wasted,shareRatio,pieces,havePieces,leechTime,seedTime)
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
            this.serverSocketSubSocket = this.serverSocket?.accept()
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
                peers.forEach { this.disconnect(infohash, it.knownPeer) }
            }
            this.serverSocketSubSocket?.close()
            this.serverSocket?.close()
            this.serverSocket = null
            this.serverSocketSubSocket = null
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
        return CompletableFuture.supplyAsync {
            //TODO
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
                connectedPeer -> connectedPeer.knownPeer == peer
            }?.getOrNull(0) ?: throw java.lang.IllegalArgumentException("infohash or peer invalid")
            //TODO actually close the connection (how?)
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
            this.connectedPeers.values.flatten()
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
            //TODO
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
    fun unchoke(infohash: String, peer: KnownPeer): CompletableFuture<Unit> = TODO("Implement me!")

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
    fun handleSmallMessages(): CompletableFuture<Unit> = TODO("Implement me!")

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
    fun requestPiece(infohash: String, peer: KnownPeer, pieceIndex: Long): CompletableFuture<Unit> =
        TODO("Implement me!")

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
    fun sendPiece(infohash: String, peer: KnownPeer, pieceIndex: Long): CompletableFuture<Unit> = TODO("Implement me!")

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
    ): CompletableFuture<Map<KnownPeer, List<Long>>> = TODO("Implement me!")

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
    ): CompletableFuture<Map<KnownPeer, List<Long>>> = TODO("Implement me!")

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
    fun files(infohash: String): CompletableFuture<Map<String, ByteArray>> = TODO("Implement me!")

    /**
     * Load files into the client.
     *
     * If [files] has extra files, they are ignored. If it is missing a file, it is treated as all zeroes. If file
     * contents are too short, the file is padded with zeroes. If the file contents are too long, they are truncated.
     *
     * @param files A mapping from filename to file contents.
     * @throws IllegalArgumentException if [infohash] is not loaded,
     */
    fun loadFiles(infohash: String, files: Map<String, ByteArray>): CompletableFuture<Unit> = TODO("Implement me!")

    /**
     * Compare SHA-1 hash for the loaded pieces of torrent [infohash] against the meta-info file. If a piece fails hash
     * checking, it is zeroed and marked as not downloaded.
     *
     * @throws IllegalArgumentException if [infohash] is not loaded.
     * @return True if all the pieces have been downloaded and passed hash checking, false otherwise.
     */
    fun recheck(infohash: String): CompletableFuture<Boolean> = TODO("Implement me!")
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

        return database.peersRead(infohash).thenCompose { it ->
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
}
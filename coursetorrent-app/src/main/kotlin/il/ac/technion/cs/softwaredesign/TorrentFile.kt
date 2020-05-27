package il.ac.technion.cs.softwaredesign

import com.github.kittinunf.fuel.httpGet
import com.github.kittinunf.result.Result
import il.ac.technion.cs.softwaredesign.Utils.Companion.withParams
import il.ac.technion.cs.softwaredesign.exceptions.TrackerException
import java.lang.Exception
import java.lang.IllegalArgumentException

class TorrentFile(val infohash : String, immutableList : List<List<String>>) {
    var announceList : MutableList<MutableList<String>>

    companion object {
        /**
         * Initialize torrent file from torrent byte array
         * @throws IllegalArgumentException if the supplied byte array is an not a valid metainfo file
         */
        public fun deserialize(torrent: ByteArray) : TorrentFile {
            val torrentData : Map<String, Any>
            try {
                torrentData = Ben(torrent).decode() as Map<String, Any>
            } catch (e : Exception) {
                throw IllegalArgumentException("Invalid metainfo file")
            }

            if(!torrentData.containsKey("infoEncoded")) {
                throw IllegalArgumentException("Invalid metainfo file")
            }
            val info = torrentData["infoEncoded"] as ByteArray
            val infoHash = Utils.sha1hash(info)

            val announceList : MutableList<MutableList<String>>
            if(torrentData.containsKey("announce-list")) {
                try {
                    announceList = torrentData["announce-list"] as MutableList<MutableList<String>>
                } catch (e : Exception) {
                    throw IllegalArgumentException("Invalid metainfo file")
                }
            }
            else {
                val announce : String = torrentData["announce"] as String
                announceList = arrayListOf(arrayListOf(announce))
            }

            return TorrentFile(infoHash, announceList)
        }
    }

    init {
        announceList = immutableList.map { list -> list.toMutableList() }.toMutableList()
    }

    /**
     * Shuffles the order in each tier of the announcelist
     */
    fun shuffleAnnounceList():Unit {
        announceList.map{ it.shuffle() }
        announceList.shuffle()
    }

    /**
     * -Announces to a tracker, by the order specified in the BitTorrent specification
     * -The trackerid functionality is *not* required in the assignment (Matan said)
     * -This function is also responsible for reordering the trackers if necessary,
     *  as requested by the BitTorrent specification
     * -This function also updates the tracker stats for every tracker it attempts
     *  to get a response from
     * @throws TrackerException if no trackers return a non-failure response
     * @returns the response string from the tracker, de-bencoded
     */
    fun announceTracker(params: List<Pair<String, String>>, database: SimpleDB) : Map<String, Any> {
        val trackerStats = database.statsRead(infohash).toMutableMap()
        var lastErrorMessage = "Empty announce list"
        for(tier in this.announceList) {
            for(trackerURL in tier) {
                val (_, _, result) = trackerURL.withParams(params).httpGet().response()
                if(result is Result.Failure) {
                    lastErrorMessage = "Connection failed"
                    trackerStats[trackerURL] = mapOf("failure reason" to lastErrorMessage)
                    continue
                }
                else {
                    //successful connection
                    val responseMap : Map<String, Any>? = Ben(result.get()).decode() as? Map<String, Any>?
                    if(responseMap != null && responseMap.containsKey("failure reason")) {
                        lastErrorMessage = responseMap["failure reason"] as String
                        trackerStats[trackerURL] = mapOf("failure reason" to lastErrorMessage)
                        continue
                    }
                    if(responseMap == null || !responseMap.containsKey("peers")) {
                        lastErrorMessage = "Connection failed" //response invalid
                        trackerStats[trackerURL] = mapOf("failure reason" to lastErrorMessage)
                        continue
                    }
                    //reorder the tier to have the successful tracker at index 0
                    tier.remove(trackerURL)
                    tier.add(0, trackerURL)
                    //update tracker stats
                    val name = trackerStats[trackerURL]?.get("name") as? String?
                    val newScrapeData = mutableMapOf<String, Any>("complete" to (responseMap["complete"] as? Long? ?: trackerStats[trackerURL]?.get("complete") as? Long ?: 0),
                            "downloaded" to (trackerStats[trackerURL]?.get("downloaded") as? Long ?: 0),
                            "incomplete" to (responseMap["incomplete"] as? Long? ?: trackerStats[trackerURL]?.get("incomplete") as? Long ?: 0))
                    if(name != null) {
                        newScrapeData["name"] = name
                    }
                    trackerStats[trackerURL] = newScrapeData
                    database.statsUpdate(infohash, trackerStats)
                    //return the response map
                    return responseMap
                }
            }
        }
        database.statsUpdate(infohash, trackerStats)
        throw TrackerException(lastErrorMessage)
    }

    /**
     * -Scrapes tracker
     * -This function also updates the tracker stats for every tracker if it can be scraped
     * @throws IllegalArgumentException If [infohash] is not loaded.
     */
    fun scrapeTrackers(database: SimpleDB):  Map<String, Map<String, Any>>{
        val torrentAllStats = database.statsRead(infohash).toMutableMap()
        for (tier in announceList) {
            for (trackerURL in tier) {
                //find the last occurrence of '/' and if it followed by "announce" then change to string to "scrape" and send request
                var lastSlash = trackerURL.lastIndexOf('/')
                ++lastSlash
                if (trackerURL.substring(lastSlash, lastSlash + "announce".length) == "announce") {
                    val newTrackerStatsMap: Map<String, Any> = sendScrapeRequest(infohash, trackerURL, lastSlash)
                    updateCurrentTrackerStats(newTrackerStatsMap, torrentAllStats, trackerURL)
                }
            }
        }
        return torrentAllStats
    }

    /**
     * -Update the tracker stats according to the response map received from the HTTP get request
     */
    private fun updateCurrentTrackerStats(newTrackerStatsMap: Map<String, Any>, torrentAllStats: MutableMap<String, Map<String, Any>>, trackerURL: String) {
        var changedTrackerStats = newTrackerStatsMap
        if (changedTrackerStats.isEmpty()) {
            changedTrackerStats = mapOf("failure reason" to "Connection failed")
        }
        val oldName = torrentAllStats[trackerURL]?.get("name") as? String?
        when {
            changedTrackerStats.containsKey("failure reason") -> {
                torrentAllStats[trackerURL] = mapOf("failure reason" to changedTrackerStats["failure reason"]!!)
            }
            else -> {
                //insert the stats about the current files
                val currTrackerMap = (changedTrackerStats["files"] as? Map<String, Any>?)?.entries?.iterator()?.next()?.value as? Map<String, Any>?
                val newScrapeData : MutableMap<String, Any>
                if(currTrackerMap == null) {
                    newScrapeData = mutableMapOf("failure reason" to "Connection failed")
                }
                else {
                    val name = currTrackerMap.get("name") as? String ?: oldName
                    newScrapeData = mutableMapOf<String, Any>("complete" to (currTrackerMap.get("complete") as? Long ?: 0),
                            "downloaded" to (currTrackerMap.get("downloaded") as? Long ?: 0),
                            "incomplete" to (currTrackerMap.get("incomplete") as? Long ?: 0))
                    if(name != null) {
                        newScrapeData["name"] = name
                    }
                }
                torrentAllStats[trackerURL] = newScrapeData
            }
        }
    }

    /**
     * -Send HTTP ger request and decode the received map
     */
    private fun sendScrapeRequest(infohash: String, trackerURL: String, lastAnnounce: Int): Map<String, Any> {
//        trackerURL.replace(lastAnnounce,lastAnnounce+"/announce".length, "/scrape")
        val afterAnounceString = if (lastAnnounce+"/announce".length >= trackerURL.length) "" else trackerURL.substring(lastAnnounce+"/announce".length)
        val scrapeURL = trackerURL.subSequence(0,lastAnnounce-1).toString() + "/scrape" + afterAnounceString
        val params = listOf("info_hash" to Utils.urlEncode(infohash))

        val (_, _, result) = scrapeURL.withParams(params).httpGet().response()
        var newTrackerStatsMap: Map<String, Any> = try {
            Ben(result.get()).decode() as Map<String, Any>
        } catch (e: Exception) {
            mapOf("failure reason" to "Connection failed")
        }
        return newTrackerStatsMap
    }
}
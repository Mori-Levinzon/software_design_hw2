package il.ac.technion.cs.softwaredesign

import java.time.Duration

data class PieceIndexStats(
        var uploaded: Long, /* Total number of bytes downloaded (can be more than the size of the torrent) */
        var downloaded: Long, /* Total number of bytes uploaded (can be more than the size of the torrent) */
        var left: Long, /* Number of bytes left to download to complete the torrent */
        var wasted: Long, /* Number of bytes downloaded then discarded */
//        var shareRatio: Double, /* total bytes uploaded / total bytes downloaded */
        var isValid: Boolean, /* is the piece currently in hold in the DB is valid (his SHA-1 is equal to the torrent infohash pieces' 20 byte SHA1 string*/
        var leechTime: Duration, /* Amount of time this torrent was loaded, incomplete, and the client was started */
        var seedTime: Duration /* Amount of time this torrent was loaded, complete, and the client was started */
)
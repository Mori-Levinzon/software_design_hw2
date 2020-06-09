package il.ac.technion.cs.softwaredesign.il.ac.technion.cs.softwaredesign

import java.time.Duration

data class PieceIndexStats(
        var uploaded: Long, /* Total number of bytes downloaded (can be more than the size of the torrent) */
        var downloaded: Long, /* Total number of bytes uploaded (can be more than the size of the torrent) */
        var left: Long, /* Number of bytes left to download to complete the torrent *///TODO: this filed depands weather we allow a download of a part of piece i.e download that went bad
        var wasted: Long, /* Number of bytes downloaded then discarded */
//        var shareRatio: Double, /* total bytes uploaded / total bytes downloaded */

        var leechTime: Duration, /* Amount of time this torrent was loaded, incomplete, and the client was started */
        var seedTime: Duration /* Amount of time this torrent was loaded, complete, and the client was started */
)
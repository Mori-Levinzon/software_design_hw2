package il.ac.technion.cs.softwaredesign

/*
data class that hold all relevant info received from messages sent between peers, epically piece request and piece sent
 */
data class PeerMessage (
    var index: Long,
    var begin: Long,
    var length: Long,
    var block: ByteArray,
    var messageResult: Int
)
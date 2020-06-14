package il.ac.technion.cs.softwaredesign

data class PeerMessage (
    var index: Long,
    var begin: Long,
    var length: Long,
    var block: ByteArray,
    var messageResult: Int
)
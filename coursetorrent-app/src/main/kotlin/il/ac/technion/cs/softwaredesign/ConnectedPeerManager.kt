package il.ac.technion.cs.softwaredesign

import java.net.Socket
import java.time.Duration
import kotlin.experimental.and

class ConnectedPeerManager(
        var connectedPeer: ConnectedPeer,
        var socket: Socket,
        var availablePieces: MutableList<Long>,
        var requestedPieces: MutableList<Long>,
        var requestedPiecesDetails: MutableMap<Long, PeerMessage>,
        var downloaded: Long,
        var leechTime: Duration
) {
    /*
     * Messages to receive and handle from peers:
     *
     * 1. keep-alive: Do nothing.
     * 2. unchoke: Mark this peer as not choking in future calls to [connectedPeers].
     * 3. choke: Mark this peer as choking in future calls to [connectedPeers].
     * 4. have: Update the internal state of which pieces this client has, as seen in future calls to [availablePieces]
     * and [connectedPeers].
     * 5. request: Mark the peer as requesting a piece, as seen in future calls to [requestedPieces]. Ignore if the peer
     * is choked.
     */
    fun handleIncomingMessages() : Unit {
        val availableBytes = socket.getInputStream().available()
        if (availableBytes <= 0) return
        var message = socket.getInputStream().readNBytes(availableBytes)
        while (message.isNotEmpty()) {
            val messageId = WireProtocolDecoder.messageId(message)
            when(messageId) {
                0.toByte() -> connectedPeer = connectedPeer.copy(peerChoking = true)
                1.toByte() -> connectedPeer = connectedPeer.copy(peerChoking = false)
                2.toByte() -> connectedPeer = connectedPeer.copy(peerInterested = true)
                3.toByte() -> connectedPeer = connectedPeer.copy(peerInterested = false)
                4.toByte() -> { //have
                    val pieceIndex = WireProtocolDecoder.decode(message, 1).ints[0].toLong()
                    availablePieces.add(pieceIndex)
                }
                5.toByte() -> { //bitfield
                    val bitfield = WireProtocolDecoder.decode(message, 0).contents
                    var base = 0.toLong()
                    for(byte in bitfield) {
                        for(i in 0.until(8)) {
                            val bytePlace = Math.pow(2.toDouble(), 7 - (i.toDouble() % 8)).toByte() //1, 2, 4, 8, 16, 32, 64, 128
                            if(byte and bytePlace == bytePlace) {
                                availablePieces.add(base + i)
                            }
                        }
                        base += 8
                    }
                }
                6.toByte() -> { //request
                    if(connectedPeer.amChoking) return
                    val decodedMessage = WireProtocolDecoder.decode(message, 3)
                    val index = decodedMessage.ints[0]
                    val begin = decodedMessage.ints[1]
                    val length = decodedMessage.ints[2]
                    requestedPieces.add(index.toLong())
                    requestedPiecesDetails[index.toLong()] = PeerMessage(index.toLong(), begin.toLong(), length.toLong(), ByteArray(0), -1)
                }
            }
            message = message.sliceArray(IntRange(WireProtocolDecoder.length(message) + 4, message.size - 1))
        }
    }

    fun sendKeepAlive() : Unit {
        socket.getOutputStream().write(byteArrayOf(0.toByte()))
    }

    fun decideIfInterested(piecesWeHaveMap : Map<Long, PieceIndexStats>) : Unit {
        val hasPieceThatWeWant = availablePieces.filter { index -> !piecesWeHaveMap.containsKey(index) }.isNotEmpty()
        if (connectedPeer.amInterested && !hasPieceThatWeWant) {
            //change to not interested
            socket.getOutputStream().write(WireProtocolEncoder.encode(3))
            connectedPeer = connectedPeer.copy(amInterested = true)
        }
        else if(!connectedPeer.amInterested && hasPieceThatWeWant) {
            //change to interested
            socket.getOutputStream().write(WireProtocolEncoder.encode(2))
            connectedPeer = connectedPeer.copy(amInterested = true)
        }
    }
}
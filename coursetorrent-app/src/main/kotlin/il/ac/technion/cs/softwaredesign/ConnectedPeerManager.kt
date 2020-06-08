package il.ac.technion.cs.softwaredesign

import java.net.Socket
import java.util.*

class ConnectedPeerManager(
        var connectedPeer: ConnectedPeer,
        var socket: Socket,
        var availablePieces: MutableList<Long>,
        var requestedPieces: MutableList<Long>
) {
    fun handleIncomingMessages() : Unit = TODO("impl")
    fun sendKeepAlive() : Unit = TODO("impl")
}
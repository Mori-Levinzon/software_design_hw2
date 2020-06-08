package il.ac.technion.cs.softwaredesign

import java.net.Socket
import java.util.*

data class ConnectedPeerManager(
        var connectedPeer: ConnectedPeer,
        var socket: Socket,
        var availablePieces: MutableList<Long>,
        var requestedPieces: MutableList<Long>
);
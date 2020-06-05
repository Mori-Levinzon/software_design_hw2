package il.ac.technion.cs.softwaredesign

import java.net.Socket

data class ConnectedPeerManager(
        var connectedPeer: ConnectedPeer,
        var socket: Socket
);
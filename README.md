# CourseTorrent: Assignment 2

## Authors
* Bahjat Kawar, 206989105
* Mori Levinzon, 308328467

## Notes

### Implementation Summary
#### The CourseTorrent Application

In coursetorrent-app/src/main/kotlin, we put the class **CourseTorrent**

**CourseTorrent** is the main application class. The API was specified by the TA. We added a database property so that a different CRUD key-value database implementation may be used (for example, the mocked in-memory DB in testing), but of course specifying it on initialization is optional.
We also added a few private properties: alphaNumericID - which is the random character string generated on instance creation, serverSocket - which holds the server socket we open for accepting new peer connections, connectedPeers to maintain peer connections, and lastTimeKeptAlive - to keep track of our keepalive messages.
Its methods are load(torrent), unload(infohash), announces(infohash), announces(infohash,TorrentEvent,uploaded,downloaded,left), scrape(infohash) , knownPeers(infohash) invalidatePeer(infohash,peer), trackerStats(infohash), torrentStats(infohash), start(), stop(), connect(infohash, peer), disconnect(infohash, peer), connectedPeers(infohash), choke(infohash, peer), unchoke(infohash, peer), handleSmallMessages(), requestPiece(infohash, peer, pieceIndex), sendPiece(infohash, peer, pieceIndex), availablePieces(infohash, perPeer, startIndex), requestedPieces(infohash), files(infohash), loadFiles(infohash, files), and recheck(infohash). They are all well-documented in the code (Javadoc).

##### The External Library
###### SimpleDB
For this assignment were we given the SecureStorageFactory which enables us to use guice and inject its constructor in order to create 6 different DB for out storage:
torrents - stores the entire torrent file for each torrent,
announces - stores the announce list for each torrent,
peers - stores the known peers for each torrent,
trackersStats - stores all the tracker stats for each torrent,
piecesStats - stores all the pieces stats for each torrent, and
indexedPiece - which isn't just one database, but it is rather a database for each piece of a torrent, which contains its contents. These databases are basically how we store the actual torrent file contents.
In library/src/main/kotlin/, We made a class called SimpleDB. SimpleDB is a simple persistent database that supports the basic CRUD operations on String-ByteArray key-value pairs. It uses the provided read/write methods for persistence.
We used the basic methods CRUD methods from the previous assignment( create(key, value), read(key), update(key, value), and delete(key)) and implemented CRUD functions for each of the DBs, thus allowing us to guarantee type safety. Basically, the DB-specific CRUD functions are wrappers for the general-purose CRUD functions, which ensure type safety for the types we want in our DBs.
Each of the DB's read, update, and delete methods throw an IllegalArgumentException when the supplied key does not exist in the database.
Each of the DB's create methods throws an IllegalStateException when the supplied key already exists in the database.

###### Ben
This is a Bencoding library which is based on an implementation that we did not write. Original source code is here: https://gist.github.com/omarmiatello/b09d4ba995c8c0881b0921c0e7aa9bdc
we modified it such that:
1. The "pieces" property of the info dictionary would be parsed as a raw byte array and held in a map data structure.
2. The "info" property of the torrent metainfo file would hold a dictionary, that includes "pieces" as a raw byte array
3. The "peers" property received from the http get request, in case it was a string, would be parsed as a raw byte array rather than a string.
4. We added a byte array encoder to this library in order to support encoding raw byte arrays using bencoding.
5. We added support for both Long and Int numbers

###### Utils
We implemented a number of functions in Utils companion object, which serve as general utility functions, such as SHA1 hashing, URL encoding, reversing URL encoding, generating random characters, comparing IPs, formatting strings, and some extension functions which help us serialize our own objects into Bencodable objects. The functions are all well documented in the code.

###### TorrentFile
This class represents a torrent file. The class is stateless and immutable. It is initialized using a torrent byte array, which is parsed into the TorrentFile object.
Its only public property is announce List which is immutable, and it consists of announce list a proper torrent file has.
The public methods are getInfohash() and shuffleAnnounceList() which are self-explanatory, and announceTracker() and scrapeTrackers() which are only used by the announce and scrape call respectively. The latter two methods basically exist here because they operate directly on the announce list, and it made better sense they would be here rather than in CourseTorrent. We realize this increases coupling between the two classes, but we think it's okay because no other class should depend on TorrentFile.
The TorrentFile API will continue to be expanded and modified in future assignments.


###### ConnectedPeerManager
This class manages a live connection to a peer. It includes a mutable ConnectedPeer object, a socket to maintain the connection, and mutable lists of available and requested pieces by this peer.
Its public methods are:
handleIncomingMessages() which, as its name implies, handles messages coming in from this peer. The messages it handles are well documented in the code (Javadoc).
sendKeepAlive() which is very self-explanatory, it sends a keep alive message to the peer.
decideIfInterested(piecesWeHaveMap) which decides if we are interested in requesting pieces from this peer (if they have a piece that we don't), and in case that is different from the current connection status (interested or not), we send an interested/not interested message to the peer.

###### Fuel
In our implementations of announce and scrape methods we used the external library fuel (https://github.com/kittinunf/fuel.git)
in order to facilitate the way we send http GET requests and receive response from the server.
It is included in the gradle files as a dependency of ours, and we mocked it in the tests.

### Testing Summary
The following components were thoroughly tested:
* **CourseTorrent**: tested in CourseTorrentHW0Test (methods implemented in the assigment #0), CourseTorrentHW1Test (methods implemented in the assigment #1) and CourseTorrentHW2Test (methods implemented in the assigment #2). All the methods were tested and every important scenerio we could think of has been checked as well (mostly with concern to time limitations).
* **TorrentFile**: all the methods added in this assigment were created as part of a refactoring process of CourseTorrent class and are called only once from the courseTorrent method, therefore the composed tests for this assignment (CourseTorrentHW1Test) covers their functionality as well.
* **Utils**: tested in UtilsTest.
* **Ben**: tested in BenTest.

In total the tests span nearly 100% code coverage across all the classes we've implemented.

The tests make use of the `initialize CourseTorrent with mocked DB`, `mockHttp` and `mockHttpStringStartsWith` functions in order to mock the missing behavior of the remote servers and to simulate the behavior of the http requests.
Guice is used to provide the constructor parameter for CourseTorrent and bind the DB to the implementations.

### Difficulties
It was extremely hard to follow along all the BitTorrent specification details, especially in the peer protocol, it took us a lot of time to understand the requirements. Other than that, the assignment in itself was very long and tiring, and we needed to implement a lot of things, which left us with barely enough time to appreciate the concepts introduced in the HW such as the monads.

### Feedback
This was by far the longest assignment we have ever worked on in our entire time in the Technion, and it was disproportionate to our expectations.
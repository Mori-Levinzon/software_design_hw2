package il.ac.technion.cs.softwaredesign

import com.google.inject.Inject
import il.ac.technion.cs.softwaredesign.Utils.Companion.toMap
import il.ac.technion.cs.softwaredesign.Utils.Companion.toPieceIndexStats
import il.ac.technion.cs.softwaredesign.storage.SecureStorage
import il.ac.technion.cs.softwaredesign.storage.SecureStorageFactory
import java.lang.IllegalStateException
import java.nio.charset.Charset
import java.time.Duration
import java.util.concurrent.CompletableFuture

/**
 * A simple database implementation using the provided read/write methods.
 * Supports the basic CRUD methods: create, read, update, and delete
 * Supports multiple databases which are specific to the problem, and defined in the enum class Databases
 * Ensures type safety for each database type
 */
class SimpleDB @Inject constructor(storageFactory: SecureStorageFactory, private val charset: Charset = Charsets.UTF_8) {
    private val torrentsStorage : CompletableFuture<SecureStorage> = storageFactory.open("torrents".toByteArray(charset))
    private val announcesStorage : CompletableFuture<SecureStorage> = storageFactory.open("announces".toByteArray(charset))
    private val peersStorage : CompletableFuture<SecureStorage> = storageFactory.open("peers".toByteArray(charset))
    private val trackersStatsStorage : CompletableFuture<SecureStorage> = storageFactory.open("trackersStats".toByteArray(charset))
    private val piecesStatsStorage : CompletableFuture<SecureStorage> = storageFactory.open("piecesStats".toByteArray(charset))
    private val storage : SecureStorageFactory = storageFactory

    /**
     * Creates a torrents database entry
     * @param key the database key
     * @param value the database value
     * @throws IllegalStateException if the key already exists
     */
    fun torrentsCreate(key: String, value: Map<String, Any>) : CompletableFuture<Unit> {
        return torrentsStorage.thenCompose { create(it, key, Ben.encodeStr(value).toByteArray())}
    }
    /**
     * Creates an announces database entry
     * @param key the database key
     * @param value the database value
     * @throws IllegalStateException if the key already exists
     */
    fun announcesCreate(key: String, value: List<List<String>>) : CompletableFuture<Unit> {
        return announcesStorage.thenCompose { create(it, key, Ben.encodeStr(value).toByteArray())}
    }
    /**
     * Creates a peers database entry
     * @param key the database key
     * @param value the database value
     * @throws IllegalStateException if the key already exists
     */
    fun peersCreate(key: String, value: List<Map<String, String>>) : CompletableFuture<Unit> {
        return peersStorage.thenCompose { create(it, key, Ben.encodeStr(value).toByteArray())}
    }
    /**
     * Creates a tracker stats database entry
     * @param key the database key
     * @param value the database value
     * @throws IllegalStateException if the key already exists
     */
    fun trackersStatsCreate(key: String, value: Map<String, Map<String, Any>>) : CompletableFuture<Unit> {
        return trackersStatsStorage.thenCompose { create(it, key, Ben.encodeStr(value).toByteArray())}
    }
    /**
     * Creates a pieces stats database entry
     * @param key the database key
     * @param value the database value
     * @throws IllegalStateException if the key already exists
     */
    fun piecesStatsCreate(key: String, piecesMap: Map<Long,PieceIndexStats>) : CompletableFuture<Unit> {
        return piecesStatsStorage.thenCompose { create(it, key, Ben.encodeStr(piecesMap.mapValues { pair -> pair.value.toMap() }.mapKeys { it.key.toString() }).toByteArray())}
//        return piecesStatsStorage.thenCompose { create(it, key, Ben.encodeStr(piecesMap).toByteArray())}
    }
    /**
     * Creates an indexed piece database entry
     * @param infohash the piece's torrent infohash
     * @param index the piece index
     * @param value the database value
     * @throws IllegalStateException if the key already exists
     */
    fun indexedPieceCreate(infohash: String, index: Long, value: ByteArray) : CompletableFuture<Unit> {//this one is rather Unnecessary since we can create a database each time we update a piece
        return storage.open((infohash+index.toString()).toByteArray(charset)).thenCompose{ create(it, (infohash+index.toString()), value)}
    }

    /**
     * Creates all indexed piece initial entries
     * @param infohash the torrent infohash
     * @param piecesSize the number of pieces
     * @throws IllegalStateException if a key already exists
     */
    fun allpiecesCreate(infohash: String, piecesSize: Long) : CompletableFuture<Unit> {
        return CompletableFuture.supplyAsync {
            for (i in 0 until piecesSize){
                indexedPieceCreate(infohash,i, byteArrayOf(0)).join()
            }
        }
    }

    /**
     * reads a value from the torrent database
     * @param key the databse key
     * @throws IllegalArgumentException if the key does not exist
     * @throws IllegalStateException if somehow the database value is type unsafe
     */
    fun torrentsRead(key: String) : CompletableFuture<Map<String, Any>> {
        return torrentsStorage.thenApply { read(it,key) }
                .thenCompose { dbContent ->  dbContent }//to extract the value from the CompletableFuture
                .thenApply {dbContent->
                    dbContent?.let { Ben(dbContent).decode() } as? Map<String, Any>
                            ?: throw IllegalStateException("Database contents disobey type rules")
                }

    }

    /**
     * reads a value from the announces database
     * @param key the databse key
     * @throws IllegalArgumentException if the key does not exist
     * @throws IllegalStateException if somehow the database value is type unsafe
     */
    fun announcesRead(key: String) : CompletableFuture<List<List<String>>> {
        return announcesStorage.thenApply { read(it,key) }
            .thenCompose { dbContent ->  dbContent }//to extract the value from the CompletableFuture
            .thenApply {dbContent->
                        dbContent?.let { Ben(dbContent).decode() } as? List<List<String>>
                            ?: throw IllegalStateException("Database contents disobey type rules")
                        }

    }


    /**
     * reads a value from the peers database
     * @param key the databse key
     * @throws IllegalArgumentException if the key does not exist
     * @throws IllegalStateException if somehow the database value is type unsafe
     */
    fun peersRead(key: String) : CompletableFuture<List<Map<String, String>>> {
        return peersStorage.thenCompose { read(it,key) }.thenApply { dbContent ->
            dbContent?.let { Ben(dbContent).decode() } as? List<Map<String, String>>
                    ?: throw IllegalStateException("Database contents disobey type rules")
        }
    }

    /**
     * reads a value from the tracker stats database
     * @param key the databse key
     * @throws IllegalArgumentException if the key does not exist
     * @throws IllegalStateException if somehow the database value is type unsafe
     */
    fun trackersStatsRead(key: String) : CompletableFuture<Map<String, Map<String, Any>>> {
        return trackersStatsStorage.thenApply { read(it,key) }
            .thenCompose { dbContent ->  dbContent }//to extract the value from the CompletableFuture
            .thenApply {dbContent->
                dbContent?.let { Ben(dbContent).decode() } as? Map<String, Map<String, Any>>
                    ?: throw IllegalStateException("Database contents disobey type rules")
            }
    }

    /**
     * reads a value from the pieces stats database
     * @param key the databse key
     * @throws IllegalArgumentException if the key does not exist
     * @throws IllegalStateException if somehow the database value is type unsafe
     */
    fun piecesStatsRead(key: String) : CompletableFuture<Map<Long, PieceIndexStats>> {
        return piecesStatsStorage.thenApply { read(it,key) }
                .thenCompose { dbContent ->  dbContent }//to extract the value from the CompletableFuture
                .thenApply {dbContent->
                    (dbContent?.let { Ben(dbContent).decode() } as? Map<String, Map<String, Any>>)?.
                    mapValues { it.value.toPieceIndexStats() }?.mapKeys { it.key.toLong() }
                            ?: throw IllegalStateException("Database contents disobey type rules")
                }
    }

    /**
     * reads a value from the indexed piece database
     * @param infohash the piece's torrent infohash
     * @param index the piece index
     * @throws IllegalArgumentException if the key does not exist
     * @throws IllegalStateException if somehow the database value is type unsafe
     */
    fun indexedPieceRead(infohash: String, index: Long) : CompletableFuture<ByteArray> {
        return storage.open((infohash+index).toByteArray(charset)).thenApply { read(it,(infohash+index.toString())) }
                .thenCompose { dbContent ->  dbContent }//to extract the value from the CompletableFuture
                .thenApply {dbContent->
                    dbContent
                            ?: throw IllegalStateException("Database contents disobey type rules")
                }
    }

    /**
     * Updates a torrents database entry
     * @param key the database key
     * @param value the database value
     * @throws IllegalArgumentException if the key does not exist
     */
    fun torrentsUpdate(key: String, value: Map<String, Any>) : Unit {
        torrentsStorage.thenApply { update(it, key, Ben.encodeStr(value).toByteArray())}.join()
    }
    /**
     * Updates an announces database entry
     * @param key the database key
     * @param value the database value
     * @throws IllegalArgumentException if the key does not exist
     */
    fun announcesUpdate(key: String, value: List<List<String>>) : Unit {
        announcesStorage.thenApply { update(it, key, Ben.encodeStr(value).toByteArray())}.join()
    }
    /**
     * Updates a peers database entry
     * @param key the database key
     * @param value the database value
     * @throws IllegalArgumentException if the key does not exist
     */
    fun peersUpdate(key: String, value: List<Map<String, String>>) : Unit {
        peersStorage.thenApply {update(it, key, Ben.encodeStr(value).toByteArray())}.join()
    }
    /**
     * Updates a trackerstats database entry
     * @param key the database key
     * @param value the database value
     * @throws IllegalArgumentException if the key does not exist
     */
    fun trackersStatsUpdate(key: String, value: Map<String, Map<String, Any>>) : Unit {
        trackersStatsStorage.thenApply {update(it, key, Ben.encodeStr(value).toByteArray())}.join()
    }
    /**
     * Updates a pieces stats database entry
     * @param key the database key
     * @param value the database value
     * @throws IllegalArgumentException if the key does not exist
     */
    fun piecesStatsUpdate(key: String, value: Map<Long, PieceIndexStats>) : Unit {
        piecesStatsStorage.thenApply {update(it, key, Ben.encodeStr(value.mapValues { it.value.toMap() }.mapKeys { it.key.toString() }).toByteArray())}.join()
    }

    /**
     * Updates an indexed piece database entry
     * @param infohash the piece's torrent infohash
     * @param index the piece index
     * @param value the database value
     * @throws IllegalArgumentException if the key does not exist
     */
    fun indexedPieceUpdate(infohash: String, index: Long, value: ByteArray) : Unit {
        storage.open((infohash+index).toByteArray(charset))
                .thenApply {update(it, (infohash+index.toString()), value)}.join()
    }

    /**
     * Deletes an entry from the torrents database
     * @param key the database key
     * @throws IllegalArgumentException if the key does not exist
     */
    fun torrentsDelete(key: String) : CompletableFuture<Unit> {
        return torrentsStorage.thenApply { delete(it, key) }
    }
    /**
     * Deletes an entry from the announces database
     * @param key the database key
     * @throws IllegalArgumentException if the key does not exist
     */
    fun announcesDelete(key: String) : CompletableFuture<Unit> {
        return announcesStorage.thenApply { delete(it, key) }
    }
    /**
     * Deletes an entry from the peers database
     * @param key the database key
     * @throws IllegalArgumentException if the key does not exist
     */
    fun peersDelete(key: String) : CompletableFuture<Unit> {
        return peersStorage.thenApply { delete(it, key) }
    }
    /**
     * Deletes an entry from the tracker stats database
     * @param key the database key
     * @throws IllegalArgumentException if the key does not exist
     */
    fun trackersStatsDelete(key: String) : CompletableFuture<Unit> {
        return trackersStatsStorage.thenApply { delete(it, key) }
    }
    /**
     * Deletes an entry from the pieces stats database
     * @param key the database key
     * @throws IllegalArgumentException if the key does not exist
     */
    fun piecesStatsDelete(key: String) : CompletableFuture<Unit> {
        return piecesStatsStorage.thenApply { delete(it, key) }
    }

    /**
     * Deletes an entry from the indexed piece database
     * @param key the database key
     * @throws IllegalArgumentException if the key does not exist
     */
    fun indexedPieceDelete(infohash: String, index: Long) : CompletableFuture<Unit> {
        return storage.open((infohash+index).toByteArray(charset))
                .thenApply { delete(it, infohash+index.toString()) }
    }
    /**
     * Deletes all entries from the indexed piece database that belong to an infohash
     * @param infohash the infohash to delete pieces for
     * @param piecesSize the number of pieces to delete
     * @throws IllegalArgumentException if a key does not exist
     */
    fun allpiecesDelete(infohash: String, piecesSize: Long) : CompletableFuture<Unit> {
        return CompletableFuture.supplyAsync {
            for (i in 0..piecesSize){
                storage.open((infohash+i.toString()).toByteArray(charset))
                        .thenApply { delete(it, infohash+i.toString()) }
            }
        }
    }


    /**
     * Creates a key-value pair in the given database
     * @throws IllegalStateException if the key already exists in the database
     */
    private fun create(storage: SecureStorage, key: String, value: ByteArray): CompletableFuture<Unit> {
        return storage.read(key.toByteArray(charset)).thenApply {
            if (it == null || it.isEmpty()) {
                null
            } else {
                it
            }
        }.thenCompose {
            if (it == null) {
                throw IllegalArgumentException("Key already exists")
            } else {
                storage.write(key.toByteArray(charset), value)
            }
        }
    }

    /**
     * Reads a value from the given database that corresponds to the given key
     * @throws IllegalArgumentException if the key doesn't exist in the database
     * @returns the requested value
     */

    private fun read(storage: SecureStorage, key: String) : CompletableFuture<ByteArray?> {
        return storage.read(key.toByteArray(charset)).thenApply {
            if (it == null || it.isEmpty()) {
                throw IllegalArgumentException("Key doesn't exist")
            } else {
                it
            }
        }
    }

    /**
     * Updates a key-value pair in the given database
     * @throws IllegalArgumentException if the key doesn't exists in the database
     */
    private fun update(storage: SecureStorage, key: String, value: ByteArray): CompletableFuture<Unit> {
        return storage.read(key.toByteArray(charset)).thenApply {
            if (it == null || it.isEmpty()) {
                null
            } else {
                it
            }
        }.thenCompose {
            if (it == null) {
                throw IllegalArgumentException("Key doesn't exist")
            } else {
                storage.write(key.toByteArray(charset), value)
            }
        }
    }


    /**
     * Deletes a key-value pair from the given database
     * @throws IllegalArgumentException if the key doesn't exists in the database
     */
    private fun delete(storage: SecureStorage, key: String) {
        update(storage, key, ByteArray(0))
    }
}
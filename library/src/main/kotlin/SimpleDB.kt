package il.ac.technion.cs.softwaredesign

import com.google.inject.Inject
import il.ac.technion.cs.softwaredesign.Utils.Companion.toMap
import il.ac.technion.cs.softwaredesign.Utils.Companion.toPieceIndexStats
import il.ac.technion.cs.softwaredesign.storage.SecureStorage
import il.ac.technion.cs.softwaredesign.storage.SecureStorageFactory
import java.lang.IllegalStateException
import java.nio.charset.Charset
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
    private val piecesStatsStorage : CompletableFuture<SecureStorage> = storageFactory.open("piecesStats".toByteArray(charset))//TODO: find better names
    private val piecesStorage : CompletableFuture<SecureStorage> = storageFactory.open("pieces".toByteArray(charset))
    private val storage : SecureStorageFactory = storageFactory


    fun torrentsCreate(key: String, value: Map<String, Any>) : CompletableFuture<Unit> {
        return torrentsStorage.thenCompose { create(it, key, Ben.encodeStr(value).toByteArray())}
    }
    fun announcesCreate(key: String, value: List<List<String>>) : CompletableFuture<Unit> {
        return announcesStorage.thenCompose { create(it, key, Ben.encodeStr(value).toByteArray())}
    }
    fun peersCreate(key: String, value: List<Map<String, String>>) : CompletableFuture<Unit> {
        return peersStorage.thenCompose { create(it, key, Ben.encodeStr(value).toByteArray())}
    }
    fun trackersStatsCreate(key: String, value: Map<String, Map<String, Any>>) : CompletableFuture<Unit> {
        return trackersStatsStorage.thenCompose { create(it, key, Ben.encodeStr(value).toByteArray())}
    }
    fun piecesStatsCreate(key: String, value: Map<Long, PieceIndexStats>) : CompletableFuture<Unit> {
        return piecesStatsStorage.thenCompose { create(it, key, Ben.encodeStr(value.mapValues { pair -> pair.value.toMap() }).toByteArray())}
    }
    fun indexedPieceCreate(infohash: String, index: Long, value: ByteArray) : CompletableFuture<Unit> {//this one is rather Unnecessary since we can create a database each time we update a piece
        return storage.open((infohash+index).toByteArray(charset)).thenCompose{ create(it, (infohash+index), Ben.encodeStr(value).toByteArray())}
    }

    fun torrentsRead(key: String) : CompletableFuture<Map<String, Any>> {
        return torrentsStorage.thenApply { read(it,key) }
                .thenCompose { dbContent ->  dbContent }//to extract the value from the CompletableFuture
                .thenApply {dbContent->
                    dbContent?.let { Ben(dbContent).decode() } as? Map<String, Any>
                            ?: throw IllegalStateException("Database contents disobey type rules")
                }

    }

    fun announcesRead(key: String) : CompletableFuture<List<List<String>>> {
        return announcesStorage.thenApply { read(it,key) }
            .thenCompose { dbContent ->  dbContent }//to extract the value from the CompletableFuture
            .thenApply {dbContent->
                        dbContent?.let { Ben(dbContent).decode() } as? List<List<String>>
                            ?: throw IllegalStateException("Database contents disobey type rules")
                        }

    }

    fun peersRead(key: String) : CompletableFuture<List<Map<String, String>>> {
        return peersStorage.thenCompose { read(it,key) }.thenApply { dbContent ->
            dbContent?.let { Ben(dbContent).decode() } as? List<Map<String, String>>
                    ?: throw IllegalStateException("Database contents disobey type rules")
        }
    }

    fun trackersStatsRead(key: String) : CompletableFuture<Map<String, Map<String, Any>>> {
        return trackersStatsStorage.thenApply { read(it,key) }
            .thenCompose { dbContent ->  dbContent }//to extract the value from the CompletableFuture
            .thenApply {dbContent->
                dbContent?.let { Ben(dbContent).decode() } as? Map<String, Map<String, Any>>
                    ?: throw IllegalStateException("Database contents disobey type rules")
            }
    }

    fun piecesStatsRead(key: String) : CompletableFuture<Map<Long, PieceIndexStats>> {
        return piecesStatsStorage.thenApply { read(it,key) }
                .thenCompose { dbContent ->  dbContent }//to extract the value from the CompletableFuture
                .thenApply {dbContent->
                    (dbContent?.let { Ben(dbContent).decode() } as? Map<Long, Map<String, Any>>)?.
                    mapValues { it.value.toPieceIndexStats() }
                            ?: throw IllegalStateException("Database contents disobey type rules")
                }
    }

    fun indexedPieceRead(infohash: String, index: Long) : CompletableFuture<ByteArray> {
        return storage.open((infohash+index).toByteArray(charset)).thenApply { read(it,(infohash+index.toString())) }
                .thenCompose { dbContent ->  dbContent }//to extract the value from the CompletableFuture
                .thenApply {dbContent->
                    dbContent
                            ?: throw IllegalStateException("Database contents disobey type rules")
                }
    }

    fun torrentsUpdate(key: String, value: Map<String, Any>) : Unit {
        torrentsStorage.thenApply { update(it, key, Ben.encodeStr(value).toByteArray())}
    }
    fun announcesUpdate(key: String, value: List<List<String>>) : Unit {
        announcesStorage.thenApply { update(it, key, Ben.encodeStr(value).toByteArray())}
    }
    fun peersUpdate(key: String, value: List<Map<String, String>>) : Unit {
        peersStorage.thenApply {update(it, key, Ben.encodeStr(value).toByteArray())}
    }
    fun trackersStatsUpdate(key: String, value: Map<String, Map<String, Any>>) : Unit {
        trackersStatsStorage.thenApply {update(it, key, Ben.encodeStr(value).toByteArray())}
    }
    fun piecesStatsUpdate(key: String, value: Map<Long, PieceIndexStats>) : Unit {
        piecesStatsStorage.thenApply {update(it, key, Ben.encodeStr(value.mapValues { it.value.toMap() }).toByteArray())}
    }

    fun indexedPieceUpdate(infohash: String, index: Long, value: ByteArray) : Unit {
        storage.open((infohash+index).toByteArray(charset))
                .thenApply {update(it, (infohash+index.toString()), value)}
    }

    fun torrentsDelete(key: String) : CompletableFuture<Unit> {
        return torrentsStorage.thenApply { delete(it, key) }
    }
    fun announcesDelete(key: String) : CompletableFuture<Unit> {
        return announcesStorage.thenApply { delete(it, key) }
    }
    fun peersDelete(key: String) : CompletableFuture<Unit> {
        return peersStorage.thenApply { delete(it, key) }
    }
    fun trackersStatsDelete(key: String) : CompletableFuture<Unit> {
        return trackersStatsStorage.thenApply { delete(it, key) }
    }
    fun piecesStatsDelete(key: String) : CompletableFuture<Unit> {
        return piecesStatsStorage.thenApply { delete(it, key) }
    }

    fun indexedPieceDelete(infohash: String, index: Long) : CompletableFuture<Unit> {
        return storage.open((infohash+index).toByteArray(charset))
                .thenApply { delete(it, infohash+index.toString()) }
    }
    fun allpiecesDelete(infohash: String, piecesSize: Long) : CompletableFuture<Unit> {
        return CompletableFuture.supplyAsync {
            for (i in 0..piecesSize){
                storage.open((infohash+i).toByteArray(charset))
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
                null//TODO: should throw an exception right at this stage?
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
                null//TODO: should throw an exception right at this stage?
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
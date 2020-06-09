package il.ac.technion.cs.softwaredesign

import com.google.inject.Inject
import il.ac.technion.cs.softwaredesign.il.ac.technion.cs.softwaredesign.PieceIndexStats
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
    private val trackersStatsStorage : CompletableFuture<SecureStorage> = storageFactory.open("stats".toByteArray(charset))
    private val piecesStatsStorage : CompletableFuture<SecureStorage> = storageFactory.open("stats".toByteArray(charset))//TODO: find better names
    private val piecesStorage : CompletableFuture<SecureStorage> = storageFactory.open("pieces".toByteArray(charset))

    fun torrentsCreate(key: String, value: Map<String, Any>) : CompletableFuture<Unit> {
        return torrentsStorage.thenApply { create(it, key, Ben.encodeStr(value).toByteArray()).join()}
    }
    fun announcesCreate(key: String, value: List<List<String>>) : CompletableFuture<Unit> {
        return announcesStorage.thenApply { create(it, key, Ben.encodeStr(value).toByteArray()).join()}
    }
    fun peersCreate(key: String, value: List<Map<String, String>>) : CompletableFuture<Unit> {
        return peersStorage.thenApply { create(it, key, Ben.encodeStr(value).toByteArray()).join()}
    }
    fun trackersStatsCreate(key: String, value: Map<String, Map<String, Any>>) : CompletableFuture<Unit> {
        return trackersStatsStorage.thenApply { create(it, key, Ben.encodeStr(value).toByteArray()).join()}
    }
    fun piecesStatsCreate(key: String, value: Map<Long, PieceIndexStats>) : CompletableFuture<Unit> {
        return piecesStatsStorage.thenApply { create(it, key, Ben.encodeStr(value).toByteArray()).join()}
    }
    fun piecesCreate(key: String, value: Map<Long, ByteArray>) : CompletableFuture<Unit> {
        return piecesStorage.thenApply { create(it, key, Ben.encodeStr(value).toByteArray()).join()}
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
        return peersStorage.thenApply { read(it,key) }
            .thenCompose { dbContent ->  dbContent }//to extract the value from the CompletableFuture
            .thenApply {dbContent->
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
                    dbContent?.let { Ben(dbContent).decode() } as? Map<Long, PieceIndexStats>
                            ?: throw IllegalStateException("Database contents disobey type rules")
                }
    }

    fun piecesRead(key: String) : CompletableFuture<Map<Long, ByteArray>> {
        return piecesStorage.thenApply { read(it,key) }
                .thenCompose { dbContent ->  dbContent }//to extract the value from the CompletableFuture
                .thenApply {dbContent->
                    dbContent?.let { Ben(dbContent).decode() } as? Map<Long, ByteArray>
                            ?: throw IllegalStateException("Database contents disobey type rules")
                }
    }

    fun torrentsUpdate(key: String, value: List<List<String>>) : CompletableFuture<Unit> {
        return torrentsStorage.thenApply { update(it, key, Ben.encodeStr(value).toByteArray())}
    }
    fun announcesUpdate(key: String, value: List<List<String>>) : CompletableFuture<Unit> {
        return announcesStorage.thenApply { update(it, key, Ben.encodeStr(value).toByteArray())}
    }
    fun peersUpdate(key: String, value: List<Map<String, String>>) : CompletableFuture<Unit> {
        return peersStorage.thenApply {update(it, key, Ben.encodeStr(value).toByteArray())}
    }
    fun trackersStatsUpdate(key: String, value: Map<String, Map<String, Any>>) : CompletableFuture<Unit> {
        return trackersStatsStorage.thenApply {update(it, key, Ben.encodeStr(value).toByteArray())}
    }
    fun piecesStatsUpdate(key: String, value: Map<Long, PieceIndexStats>) : CompletableFuture<Unit> {
        return piecesStatsStorage.thenApply {update(it, key, Ben.encodeStr(value).toByteArray())}
    }
    fun piecesUpdate(key: String, value: Map<Long, ByteArray>) : CompletableFuture<Unit> {
        return piecesStorage.thenApply {update(it, key, Ben.encodeStr(value).toByteArray())}
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
    fun piecesDelete(key: Long) : CompletableFuture<Unit> {
        return piecesStorage.thenApply { delete(it, key.toString()) }
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
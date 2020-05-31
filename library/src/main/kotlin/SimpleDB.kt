package il.ac.technion.cs.softwaredesign

import com.google.inject.Inject
import il.ac.technion.cs.softwaredesign.storage.SecureStorage
import il.ac.technion.cs.softwaredesign.storage.SecureStorageFactory
import java.lang.Exception
import java.lang.IllegalStateException
import java.nio.charset.Charset
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Future
import java.util.function.Function

/**
 * A simple database implementation using the provided read/write methods.
 * Supports the basic CRUD methods: create, read, update, and delete
 * Supports multiple databases which are specific to the problem, and defined in the enum class Databases
 * Ensures type safety for each database type
 */
class SimpleDB @Inject constructor(storageFactory: SecureStorageFactory, private val charset: Charset = Charsets.UTF_8) {
    private val torrentsStorage : CompletableFuture<SecureStorage> = storageFactory.open("torrents".toByteArray(charset))
    private val peersStorage : CompletableFuture<SecureStorage> = storageFactory.open("peers".toByteArray(charset))
    private val statsStorage : CompletableFuture<SecureStorage> = storageFactory.open("stats".toByteArray(charset))

    fun torrentsCreate(key: String, value: List<List<String>>) : CompletableFuture<Unit> {
        return torrentsStorage.thenApply { create(it, key, Ben.encodeStr(value).toByteArray()).join()}
    }
    fun peersCreate(key: String, value: List<Map<String, String>>) : CompletableFuture<Unit> {
        return peersStorage.thenApply { create(it, key, Ben.encodeStr(value).toByteArray()).join()}
    }
    fun statsCreate(key: String, value: Map<String, Map<String, Any>>) : CompletableFuture<Unit> {
        return statsStorage.thenApply { create(it, key, Ben.encodeStr(value).toByteArray()).join()}
    }

    fun torrentsRead(key: String) : CompletableFuture<List<List<String>>> {
        return torrentsStorage.thenApply { read(it,key) }
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

    fun statsRead(key: String) : CompletableFuture<Map<String, Map<String, Any>>> {
        return statsStorage.thenApply { read(it,key) }
            .thenCompose { dbContent ->  dbContent }//to extract the value from the CompletableFuture
            .thenApply {dbContent->
                dbContent?.let { Ben(dbContent).decode() } as? Map<String, Map<String, Any>>
                    ?: throw IllegalStateException("Database contents disobey type rules")
            }
    }

    fun torrentsUpdate(key: String, value: List<List<String>>) : CompletableFuture<Unit> {
        return torrentsStorage.thenApply { update(it, key, Ben.encodeStr(value).toByteArray()).join()}
    }
    fun peersUpdate(key: String, value: List<Map<String, String>>) : CompletableFuture<Unit> {
        return peersStorage.thenApply {update(it, key, Ben.encodeStr(value).toByteArray()).join()}
    }
    fun statsUpdate(key: String, value: Map<String, Map<String, Any>>) : CompletableFuture<Unit> {
        return statsStorage.thenApply {update(it, key, Ben.encodeStr(value).toByteArray()).join()}
    }

    fun torrentsDelete(key: String) : CompletableFuture<Unit> {
        return torrentsStorage.thenApply { delete(it, key) }
    }
    fun peersDelete(key: String) : CompletableFuture<Unit> {
        return peersStorage.thenApply { delete(it, key) }
    }
    fun statsDelete(key: String) : CompletableFuture<Unit> {
        return statsStorage.thenApply { delete(it, key) }
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
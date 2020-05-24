package il.ac.technion.cs.softwaredesign

import com.google.inject.Inject
import il.ac.technion.cs.softwaredesign.storage.SecureStorage
import il.ac.technion.cs.softwaredesign.storage.SecureStorageFactory
import java.lang.Exception
import java.lang.IllegalStateException
import java.nio.charset.Charset

/**
 * A simple database implementation using the provided read/write methods.
 * Supports the basic CRUD methods: create, read, update, and delete
 * Supports multiple databases which are specific to the problem, and defined in the enum class Databases
 * Ensures type safety for each database type
 */
class SimpleDB @Inject constructor(storageFactory: SecureStorageFactory, private val charset: Charset = Charsets.UTF_8) {
    private val torrentsStorage : SecureStorage = storageFactory.open("torrents".toByteArray(charset))
    private val peersStorage : SecureStorage = storageFactory.open("peers".toByteArray(charset))
    private val statsStorage : SecureStorage = storageFactory.open("stats".toByteArray(charset))

    fun torrentsCreate(key: String, value: List<List<String>>) : Unit {
        create(torrentsStorage, key, Ben.encodeStr(value).toByteArray())
    }
    fun peersCreate(key: String, value: List<Map<String, String>>) : Unit {
        create(peersStorage, key, Ben.encodeStr(value).toByteArray())
    }
    fun statsCreate(key: String, value: Map<String, Map<String, Any>>) : Unit {
        create(statsStorage, key, Ben.encodeStr(value).toByteArray())
    }

    fun torrentsRead(key: String) : List<List<String>> {
        val dbContent = read(torrentsStorage, key)
        try {
            return Ben(dbContent).decode() as List<List<String>>
        }
        catch (e: Exception) {
            throw IllegalStateException("Database contents disobey type rules")
        }
    }
    fun peersRead(key: String) : List<Map<String, String>> {
        val dbContent = read(peersStorage, key)
        try {
            return Ben(dbContent).decode() as List<Map<String, String>>
        }
        catch (e: Exception) {
            throw IllegalStateException("Database contents disobey type rules")
        }
    }
    fun statsRead(key: String) : Map<String, Map<String, Any>> {
        val dbContent = read(statsStorage, key)
        try {
            return Ben(dbContent).decode() as Map<String, Map<String, Any>>
        }
        catch (e: Exception) {
            throw IllegalStateException("Database contents disobey type rules")
        }
    }

    fun torrentsUpdate(key: String, value: List<List<String>>) : Unit {
        update(torrentsStorage, key, Ben.encodeStr(value).toByteArray())
    }
    fun peersUpdate(key: String, value: List<Map<String, String>>) : Unit {
        update(peersStorage, key, Ben.encodeStr(value).toByteArray())
    }
    fun statsUpdate(key: String, value: Map<String, Map<String, Any>>) : Unit {
        update(statsStorage, key, Ben.encodeStr(value).toByteArray())
    }

    fun torrentsDelete(key: String) : Unit {
        delete(torrentsStorage, key)
    }
    fun peersDelete(key: String) : Unit {
        delete(torrentsStorage, key)
    }
    fun statsDelete(key: String) : Unit {
        delete(torrentsStorage, key)
    }


    /**
     * Creates a key-value pair in the given database
     * @throws IllegalStateException if the key already exists in the database
     */
    private fun create(storage: SecureStorage, key: String, value: ByteArray) {
        val oldValueByteArray = storage.read(key.toByteArray(charset))
        if (oldValueByteArray != null && oldValueByteArray.size != 0) {
            throw IllegalStateException("Key already exists")
        }

        storage.write(key.toByteArray(charset), value)
    }

    /**
     * Reads a value from the given database that corresponds to the given key
     * @throws IllegalArgumentException if the key doesn't exist in the database
     * @returns the requested value
     */
    private fun read(storage: SecureStorage, key: String) : ByteArray {
        val value = storage.read(key.toByteArray(charset))
        if(value == null || value.size == 0) {
            throw IllegalArgumentException("Key doesn't exist")
        }
        return value
    }

    /**
     * Updates a key-value pair in the given database
     * @throws IllegalArgumentException if the key doesn't exists in the database
     */
    private fun update(storage: SecureStorage, key: String, value: ByteArray) {
        val oldValueByteArray = storage.read(key.toByteArray(charset))
        if (oldValueByteArray == null || oldValueByteArray.size == 0) {
            throw IllegalArgumentException("Key doesn't exist")
        }
        storage.write(key.toByteArray(charset), value)
    }


    /**
     * Deletes a key-value pair from the given database
     * @throws IllegalArgumentException if the key doesn't exists in the database
     */
    private fun delete(storage: SecureStorage, key: String) {
        update(storage, key, ByteArray(0))
    }
}
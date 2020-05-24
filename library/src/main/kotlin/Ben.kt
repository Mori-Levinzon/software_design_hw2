package il.ac.technion.cs.softwaredesign

/**
 * class for Bencoding, with special treatment for "info", "peers" (in case it was a byte string), and "pieces" keys (because they're bytes)
 * based on: https://gist.github.com/omarmiatello/b09d4ba995c8c0881b0921c0e7aa9bdc
 * I modified the code such that the "pieces" key would have its value taken as ByteArray and not parsed,
 * while "info" will have a regular dictionary under it, in addition to the original info ByteArray under "infoEncoded"
 * I also made the class accept a ByteArray rather than a string
 * I also fixed some bugs that were in the original code, unfortunately
 */
class Ben(val byteArray: ByteArray, var i: Int = 0) {
    private var isPieces = false
    private var isPeers = false
    private var currChar : Char = '0'
    private val charset = Charsets.UTF_8
    private fun readBytes(count: Int) : ByteArray {
        val requiredByteArray = byteArray.sliceArray(IntRange(i, i + count - 1))
        i += count
        return requiredByteArray
    }
    private fun read(count: Int) : String {
        val remainingStr = String(byteArray.sliceArray(IntRange(i, byteArray.size - 1)), charset)
        val requiredStr = remainingStr.substring(0, count)
        i += requiredStr.toByteArray(charset).size
        return requiredStr
    }
    private fun readUntil(c: Char) : String {
        val remainingStr = String(byteArray.sliceArray(IntRange(i, byteArray.size - 1)), charset)
        val requiredStr = remainingStr.substring(0, remainingStr.indexOf(c) + 1)
        i += requiredStr.toByteArray(charset).size
        return requiredStr.substring(0, requiredStr.length - 1)
    }

    /**
     * Main decoder function
     * It recursively parses the given ByteArray
     * Should only be called once (except for the recursive calls)
     */
    fun decode(): Any = when ({currChar = read(1)[0]; currChar}.invoke()) {
        'i' -> readUntil('e').toLong()
        'l' -> ArrayList<Any>().apply {
            isPeers = false
            var obj = decode()
            while (obj != Unit) {
                add(obj)
                obj = decode()
            }
        }
        'd' -> HashMap<String, Any>().apply {
            isPeers = false
            var obj = decode()
            while (obj != Unit) {
                if(obj as String == "info") {
                    val startIndex = i
                    put(obj as String, decode())
                    val info = byteArray.sliceArray(IntRange(startIndex, i - 1))
                    put("infoEncoded", info)
                }
                else if (obj as String == "pieces") {
                    isPieces = true
                    val startIndex = i
                    decode()
                    val info = byteArray.sliceArray(IntRange(startIndex, i - 1))
                    put(obj as String, info)
                }
                else if (obj as String == "peers") {
                    isPeers = true
                    val info = decode()
                    put(obj as String, info)
                }
                else {
                    put(obj as String, decode())
                }
                obj = decode()
            }
        }
        'e' -> Unit
        in ('0'..'9') -> if (isPieces || isPeers) {
            isPieces = false
            isPeers = false
            readBytes((currChar + readUntil(':')).toInt())
        }
        else {
            String(readBytes((currChar + readUntil(':')).toInt()), charset)
        }
        else -> throw IllegalStateException("Byte: ${i}")
    }

    companion object {
        fun encodeStr(obj: Any): String = when (obj) {
            is Int -> "i${obj}e"
            is Long -> "i${obj}e"
            is String -> "${obj.toByteArray().size}:$obj"
            is List<*> -> "l${obj.joinToString("") {
                encodeStr(
                        it!!
                )
            }}e"
            is Map<*, *> -> "d${obj.map { encodeStr(it.key!!) + encodeStr(
                    it.value!!
            )
            }.joinToString("")}e"
            else -> throw IllegalStateException()
        }
        fun encodeByteArray(obj: Any): ByteArray = when (obj) {
            is Int -> "i${obj}e".toByteArray()
            is Long -> "i${obj}e".toByteArray()
            is String -> "${obj.toByteArray().size}:$obj".toByteArray()
            is UByteArray -> "${obj.toByteArray().size}:".toByteArray() + obj.toByteArray()
            is ByteArray -> "${obj.size}:".toByteArray() + obj
            is List<*> -> "l".toByteArray() + obj.map {
                encodeByteArray(
                        it!!
                )
            }.fold(ByteArray(0), {acc, it -> acc + it}) + "e".toByteArray()
            is Map<*, *> -> "d".toByteArray() + obj.map { encodeByteArray(it.key!!) + encodeByteArray(
                    it.value!!
            )
            }.fold(ByteArray(0), {acc, it -> acc + it}) + "e".toByteArray()
            else -> throw IllegalStateException()
        }
    }
}
import il.ac.technion.cs.softwaredesign.Ben
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.lang.IllegalStateException

class BenTest {
    @Test
    fun `basic decoder works`() {
        val bencoded = "d3:abci123e3:ghill1:a1:bel1:c1:deee".toByteArray()
        val result = Ben(bencoded).decode()
        assert(result is Map<*, *> && result == mapOf("abc" to 123.toLong(),
                "ghi" to listOf(listOf("a", "b"), listOf("c", "d"))))
    }

    @Test
    fun `wrong character throws exception`() {
        val bencoded = "d3:abcXe".toByteArray()
        assertThrows<IllegalStateException> {
            Ben(bencoded).decode()
        }
    }

    @Test
    fun `decoder returns peers as byte array`() {
        val bencoded = "d5:peers10:01234567".toByteArray() + byteArrayOf(4) + "9e".toByteArray()
        val result = Ben(bencoded).decode()
        assert(result is Map<*, *> && result["peers"] is ByteArray)
    }

    @Test
    fun `decoder returns peers dictionary as dictionary`() {
        val bencoded = "d5:peersd1:a1:bee".toByteArray()
        val result = Ben(bencoded).decode()
        assert(result is Map<*, *> && result == mapOf("peers" to mapOf("a" to "b")))
    }

    @Test
    fun `decoder returns pieces as byte array`() {
        val bencoded = "d6:pieces10:01234567".toByteArray() + byteArrayOf(4) + "9e".toByteArray()
        val result = Ben(bencoded).decode()
        assert(result is Map<*, *> && result["pieces"] is ByteArray)
    }

    @Test
    fun `basic encoder works`() {
        val bencoded = "d3:abci123e3:ghill1:a1:bel1:c1:deee"
        val original = mapOf("abc" to 123.toLong(),
                "ghi" to listOf(listOf("a", "b"), listOf("c", "d")))
        assert(Ben.encodeStr(original) == bencoded)
    }

    @Test
    fun `byte array encoder works`() {
        val bencoded = "d3:abc3:".toByteArray() + byteArrayOf(3,4,5) + "3:ghill1:a1:bel1:c1:deee".toByteArray()
        val original = mapOf("abc" to byteArrayOf(3,4,5),
                "ghi" to listOf(listOf("a", "b"), listOf("c", "d")))
        assert(Ben.encodeByteArray(original).contentEquals(bencoded))
    }
}
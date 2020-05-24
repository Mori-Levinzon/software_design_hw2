

import com.google.common.base.Predicates.equalTo
import il.ac.technion.cs.softwaredesign.Utils
import il.ac.technion.cs.softwaredesign.Utils.Companion.getRandomChars
import il.ac.technion.cs.softwaredesign.Utils.Companion.sha1hash
import il.ac.technion.cs.softwaredesign.Utils.Companion.urlEncode
import il.ac.technion.cs.softwaredesign.Utils.Companion.withParams
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.lang.IllegalArgumentException

class UtilsTest {

    @Test
    fun `sha1hash hashes correctly`() {
        val barr = ubyteArrayOf(0u, 1u, 2u, 3u, 4u, 5u, 6u, 7u, 8u, 9u, 0u, 1u, 2u, 3u, 4u, 5u, 6u, 7u, 8u, 9u).toByteArray()
        val hash = sha1hash(barr)
        assert(hash == "1c32fac532617e86abcce0d64834819e7d957968")
    }

    @Test
    fun `urlencode works well`() {
        val str = "what is up"
        val hexed = str.toByteArray().fold("", { s, it -> s + "%02x".format(it) })
        val encoded = urlEncode(hexed)
        assert(encoded == "what%20is%20up")
    }

    @Test
    fun `IP compares correctly`() {
        val addresses = ArrayList<String>()
        addresses.add("123.4.245.23")
        addresses.add("104.244.253.29")
        addresses.add("1.198.3.93")
        addresses.add("32.183.93.40")
        addresses.add("104.30.244.2")
        addresses.add("104.244.4.1")

        val sortedAddresses = ArrayList<String>()
        sortedAddresses.add("1.198.3.93")
        sortedAddresses.add("32.183.93.40")
        sortedAddresses.add("104.30.244.2")
        sortedAddresses.add("104.244.4.1")
        sortedAddresses.add("104.244.253.29")
        sortedAddresses.add( "123.4.245.23")
        addresses.sortWith(Comparator { ip1, ip2 -> Utils.compareIPs(ip1, ip2) })
        Assertions.assertEquals(addresses, sortedAddresses)
    }

    @Test
    fun `Random Char return characters in range and in correct length`() {
        val randomStr = getRandomChars(6)
        Assertions.assertEquals(randomStr.length, 6)
        for (i in randomStr.indices){
            assert(randomStr[i] in 'a'..'z' || randomStr[i] in 'A'..'Z' || randomStr[i] in '0'..'9')
        }

        assert( getRandomChars(0) == "")

        assertThrows<IllegalArgumentException> { getRandomChars(-1) }

    }

    @Test
    fun `withParams works correctly`() {
        assert("http://myurl.com/index.php".withParams(listOf("key1" to "val1", "key2" to "val2"))
                == "http://myurl.com/index.php?key1=val1&key2=val2")
    }

}
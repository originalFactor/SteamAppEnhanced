package cc.ptoe.steam.enhanced.core.proto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ProtobufWireTest {

    @Test
    fun `varint round trips`() {
        for (value in listOf(0L, 1L, 127L, 128L, 300L, 1_700_000_000L, Long.MAX_VALUE)) {
            val bytes = ProtoWriter().writeUint64(1, value).toBytes()
            val fields = ProtoReader(bytes).parseAll()
            assertEquals(1, fields.size)
            assertEquals(value, fields[0].varint)
        }
    }

    @Test
    fun `negative int is encoded as a 64-bit two's complement pattern`() {
        // Steam sends os_type = -500 as a varint; it must survive as a bit pattern.
        val bytes = ProtoWriter().writeVarint(3, -500).toBytes()
        val field = ProtoReader(bytes).parse()[3]!!
        assertEquals(-500, field.asInt)
    }

    @Test
    fun `fixed64 writes 8 little-endian bytes`() {
        val bytes = ProtoWriter().writeFixed64(2, 0x0102030405060708L).toBytes()
        // tag(0x11) then the little-endian payload
        assertEquals(0x11, bytes[0].toInt() and 0xFF)
        assertArrayEquals(
            byteArrayOf(8, 7, 6, 5, 4, 3, 2, 1),
            bytes.copyOfRange(1, 9),
        )
    }

    @Test
    fun `fixed64 round trips through asFixed64`() {
        val steamId = 76561197960265728L
        val bytes = ProtoWriter().writeFixed64(1, steamId).toBytes()
        assertEquals(steamId, ProtoReader(bytes).parse()[1]!!.asFixed64)
    }

    @Test
    fun `fixed64 keeps the unsigned decimal for ids above 2^63`() {
        // A token_id with the top bit set must round-trip as an unsigned decimal,
        // not a sign-flipped negative.
        val decimal = "18446744073709551615" // 2^64 - 1
        val signed = java.math.BigInteger(decimal).toLong() // -1
        val bytes = ProtoWriter().writeFixed64(1, signed).toBytes()
        assertEquals(decimal, ProtoReader(bytes).parse()[1]!!.asUInt64String)
    }

    @Test
    fun `string and bytes fields round trip`() {
        val bytes = ProtoWriter()
            .writeString(1, "hello 世界")
            .writeBytes(2, byteArrayOf(1, 2, 3))
            .toBytes()
        val f = ProtoReader(bytes).parse()
        assertEquals("hello 世界", f[1]!!.asString)
        assertArrayEquals(byteArrayOf(1, 2, 3), f[2]!!.bytes)
    }

    @Test
    fun `bool round trips`() {
        val bytes = ProtoWriter().writeBool(1, true).writeBool(2, false).toBytes()
        val f = ProtoReader(bytes).parse()
        assertTrue(f[1]!!.asBool)
        assertFalse(f[2]!!.asBool)
    }

    @Test
    fun `nested message round trips`() {
        val inner = ProtoWriter().writeString(1, "nested")
        val bytes = ProtoWriter().writeMessage(3, inner).toBytes()
        val outer = ProtoReader(bytes).parse()
        val nested = ProtoReader(outer[3]!!.bytes!!).parse()
        assertEquals("nested", nested[1]!!.asString)
    }

    @Test
    fun `repeated packed varints decode`() {
        val packed = ProtoWriter().writeUint64(1, 1).writeUint64(2, 2).toBytes()
        // Re-encode just the payload values as a packed field.
        val inner = java.io.ByteArrayOutputStream()
        for (v in listOf(5L, 300L, 70000L)) {
            var x = v
            while (x and 0x7FL.inv() != 0L) {
                inner.write(((x and 0x7F) or 0x80).toInt())
                x = x ushr 7
            }
            inner.write(x.toInt())
        }
        assertEquals(listOf(5L, 300L, 70000L), ProtoReader.readPackedVarints(inner.toByteArray()))
        // Sanity: the helper above produced real (non-packed) fields too.
        assertEquals(2, ProtoReader(packed).parseAll().size)
    }

    @Test
    fun `truncated varint throws ProtoParseException`() {
        try {
            ProtoReader(byteArrayOf(0x80.toByte())).parseAll()
            fail("expected ProtoParseException")
        } catch (e: ProtoParseException) {
            assertTrue(e.message!!.contains("truncated"))
        }
    }

    @Test
    fun `length overrun throws instead of reading past the buffer`() {
        // field 1, wire type 2, declared length 10 but no payload
        val bytes = byteArrayOf(0x0A, 0x0A)
        try {
            ProtoReader(bytes).parseAll()
            fail("expected ProtoParseException")
        } catch (e: ProtoParseException) {
            assertTrue(e.message!!.contains("overruns"))
        }
    }

    @Test
    fun `oversized varint is rejected rather than looping`() {
        val runaway = ByteArray(12) { 0x80.toByte() }
        try {
            ProtoReader(runaway).parseAll()
            fail("expected ProtoParseException")
        } catch (e: ProtoParseException) {
            assertTrue(e.message!!.contains("varint too long"))
        }
    }

    @Test
    fun `unknown wire type is rejected`() {
        // field 1, wire type 3 (start group: unsupported)
        val bytes = byteArrayOf(0x0B)
        try {
            ProtoReader(bytes).parseAll()
            fail("expected ProtoParseException")
        } catch (e: ProtoParseException) {
            assertTrue(e.message!!.contains("unsupported wire type"))
        }
    }

    @Test
    fun `empty buffer parses to no fields`() {
        assertTrue(ProtoReader(ByteArray(0)).parseAll().isEmpty())
        assertNull(ProtoReader(ByteArray(0)).parse()[1])
    }
}

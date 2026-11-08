package cc.ptoe.steam.enhanced.core.proto

/**
 * Structurally invalid protobuf bytes: truncated buffer, oversized varint,
 * length overrun.
 *
 * Extends [IllegalArgumentException] so screens can fold it into their generic
 * failure copy — a malformed Steam payload must never surface as a raw parser
 * dump (or worse, an uncaught array-index exception string).
 */
class ProtoParseException(message: String) : IllegalArgumentException("protobuf: $message")

/**
 * Minimal protobuf wire-format reader/writer.
 *
 * Ported from AVA's `core/proto/protobuf_wire.dart`. Only the wire types used by
 * Steam's service messages are implemented: varint (0), 64-bit (1),
 * length-delimited (2) and 32-bit (5). This avoids a protoc dependency for a
 * handful of small messages.
 *
 * Two field-type traps dominate this protocol and both are handled here:
 *  - `steamid` is sometimes a **fixed64** (write 8 little-endian bytes) and
 *    sometimes a uint64 varint, depending on the message. See
 *    [ProtoWriter.writeFixed64] / [ProtoField.asFixed64].
 *  - 64-bit ids (client_id, token_id) routinely exceed `Long.MAX_VALUE`. They
 *    are carried as raw bit patterns: write the signed `Long` whose low 64 bits
 *    are the id, and read ids back as unsigned decimal strings ([ProtoField.asUInt64String]).
 */
class ProtoWriter {
    private val out = java.io.ByteArrayOutputStream()

    fun toBytes(): ByteArray = out.toByteArray()

    private fun tag(field: Int, wire: Int) = varint(((field shl 3) or wire).toLong())

    private fun varint(value: Long) {
        var v = value
        // Encode as 64-bit unsigned; negative Longs are already the correct
        // two's-complement bit pattern, and `ushr` keeps the loop terminating.
        while (v and 0x7FL.inv() != 0L) {
            out.write(((v and 0x7F) or 0x80).toInt())
            v = v ushr 7
        }
        out.write(v.toInt())
    }

    fun writeVarint(field: Int, value: Long): ProtoWriter {
        tag(field, 0)
        varint(value)
        return this
    }

    fun writeVarint(field: Int, value: Int): ProtoWriter = writeVarint(field, value.toLong())

    fun writeBool(field: Int, value: Boolean): ProtoWriter =
        writeVarint(field, if (value) 1L else 0L)

    fun writeUint64(field: Int, value: Long): ProtoWriter = writeVarint(field, value)

    fun writeString(field: Int, value: String): ProtoWriter {
        val bytes = value.toByteArray(Charsets.UTF_8)
        tag(field, 2)
        varint(bytes.size.toLong())
        out.write(bytes)
        return this
    }

    fun writeBytes(field: Int, value: ByteArray): ProtoWriter {
        tag(field, 2)
        varint(value.size.toLong())
        out.write(value)
        return this
    }

    fun writeMessage(field: Int, message: ProtoWriter): ProtoWriter =
        writeBytes(field, message.toBytes())

    /**
     * 64-bit fixed (wire type 1), 8 bytes little-endian.
     *
     * Steam uses this for `steamid` in several messages (AddAuthenticator,
     * mobile confirmation, UpdateAuthSessionWithSteamGuardCode…).
     */
    fun writeFixed64(field: Int, value: Long): ProtoWriter {
        tag(field, 1)
        var v = value
        repeat(8) {
            out.write((v and 0xFF).toInt())
            v = v ushr 8
        }
        return this
    }

    /** 32-bit fixed (wire type 5), 4 bytes little-endian. */
    fun writeFixed32(field: Int, value: Int): ProtoWriter {
        tag(field, 5)
        var v = value
        repeat(4) {
            out.write(v and 0xFF)
            v = v ushr 8
        }
        return this
    }
}

/**
 * One decoded protobuf field.
 *
 * [varint] carries wire types 0/1/5 (and the raw bytes for 1/5 live in
 * [bytes]); [bytes] carries wire type 2 and the fixed-width raw values.
 */
class ProtoField(
    val number: Int,
    val wireType: Int,
    val varint: Long? = null,
    val bytes: ByteArray? = null,
) {
    val asString: String
        get() = String(bytes ?: ByteArray(0), Charsets.UTF_8)

    val asInt: Int
        get() = (varint ?: 0L).toInt()

    val asBool: Boolean
        get() = (varint ?: 0L) != 0L

    /**
     * Reads a wire-type-1 (fixed64) field's 8 little-endian bytes as a signed
     * Long. Use [asUInt64String] for ids whose top bit is routinely set.
     */
    val asFixed64: Long
        get() {
            val b = bytes
            if (b == null || b.size < 8) return 0L
            var v = 0L
            for (i in 7 downTo 0) {
                v = (v shl 8) or (b[i].toLong() and 0xFF)
            }
            return v
        }

    /**
     * Reads a wire-type-1 field as an *unsigned* 64-bit value rendered as a
     * decimal string.
     *
     * token_id / client_id are handles, not numbers, and their top bit is often
     * set — [asFixed64] would sign-flip those into a value that no longer
     * round-trips back to Steam.
     */
    val asUInt64String: String
        get() {
            val b = bytes
            if (b == null || b.size < 8) return ""
            var v = java.math.BigInteger.ZERO
            for (i in 7 downTo 0) {
                v = v.shiftLeft(8).or(java.math.BigInteger.valueOf((b[i].toLong() and 0xFF)))
            }
            return v.toString()
        }

    /** Decodes a wire-2 payload as a packed repeated varint (bounds-checked). */
    fun asPackedVarints(): List<Long> =
        ProtoReader.readPackedVarints(bytes ?: ByteArray(0))
}

class ProtoReader(private val data: ByteArray) {
    private var pos = 0

    private val hasMore: Boolean
        get() = pos < data.size

    private fun readByte(): Int {
        if (pos >= data.size) {
            throw ProtoParseException("truncated varint at $pos")
        }
        return data[pos++].toInt() and 0xFF
    }

    private fun readVarintRaw(): Long {
        var shift = 0
        var result = 0L
        while (true) {
            val byte = readByte()
            result = result or ((byte.toLong() and 0x7F) shl shift)
            if (byte and 0x80 == 0) break
            shift += 7
            // A 64-bit varint is at most 10 bytes; anything longer is garbage,
            // not a big number — without this cap a run of 0x80s never ends.
            if (shift > 63) throw ProtoParseException("varint too long at $pos")
        }
        return result
    }

    /**
     * Parses all top-level fields into a map keyed by field number.
     *
     * Repeated fields keep their **last** occurrence here; use [parseAll] for
     * repeats.
     */
    fun parse(): Map<Int, ProtoField> {
        val out = linkedMapOf<Int, ProtoField>()
        for (f in parseAll()) {
            out[f.number] = f
        }
        return out
    }

    fun parseAll(): List<ProtoField> {
        val out = mutableListOf<ProtoField>()
        while (hasMore) {
            val key = readVarintRaw()
            val field = (key ushr 3).toInt()
            val wire = (key and 0x7).toInt()
            when (wire) {
                0 -> out.add(ProtoField(field, wire, varint = readVarintRaw()))
                1 -> out.add(ProtoField(field, wire, bytes = readFixed(8)))
                2 -> {
                    val len = readVarintRaw()
                    // A huge wire value comes back negative as a signed Long, so
                    // both bounds must be checked before converting to Int.
                    if (len < 0 || len > data.size - pos) {
                        throw ProtoParseException(
                            "length $len overruns ${data.size - pos} remaining at $pos",
                        )
                    }
                    val end = pos + len.toInt()
                    out.add(ProtoField(field, wire, bytes = data.copyOfRange(pos, end)))
                    pos = end
                }
                5 -> out.add(ProtoField(field, wire, bytes = readFixed(4)))
                else -> throw ProtoParseException("unsupported wire type $wire")
            }
        }
        return out
    }

    private fun readFixed(n: Int): ByteArray {
        if (n > data.size - pos) {
            throw ProtoParseException(
                "fixed$n needs $n bytes, ${data.size - pos} remaining at $pos",
            )
        }
        val out = data.copyOfRange(pos, pos + n)
        pos += n
        return out
    }

    companion object {
        fun fromBase64(b64: String): ProtoReader =
            ProtoReader(kotlin.io.encoding.Base64.withPadding(kotlin.io.encoding.Base64.PaddingOption.PRESENT_OPTIONAL).decode(b64))

        /**
         * Reads back-to-back varints until [bytes] is exhausted — the packed
         * encoding of a repeated varint field.
         *
         * Shares [readVarintRaw]'s bounds checks, so a truncated packed field
         * throws [ProtoParseException] instead of silently under-reading.
         */
        fun readPackedVarints(bytes: ByteArray): List<Long> {
            val r = ProtoReader(bytes)
            val out = mutableListOf<Long>()
            while (r.hasMore) {
                out.add(r.readVarintRaw())
            }
            return out
        }
    }
}

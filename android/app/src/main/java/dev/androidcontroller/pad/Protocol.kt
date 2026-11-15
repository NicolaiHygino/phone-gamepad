package dev.androidcontroller.pad

import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Button bits of the wire protocol. Keep in sync with PROTOCOL.md / server/protocol.py. */
object Btn {
    const val A = 1 shl 0
    const val B = 1 shl 1
    const val X = 1 shl 2
    const val Y = 1 shl 3
    const val LB = 1 shl 4
    const val RB = 1 shl 5
    const val START = 1 shl 6
    const val SELECT = 1 shl 7
    const val MODE = 1 shl 8
    const val L3 = 1 shl 9
    const val R3 = 1 shl 10
    const val DPAD_UP = 1 shl 11
    const val DPAD_DOWN = 1 shl 12
    const val DPAD_LEFT = 1 shl 13
    const val DPAD_RIGHT = 1 shl 14
}

/** Full controller state. Stick axes: +x right, +y down (evdev convention). */
data class PadState(
    val buttons: Int = 0,
    val lx: Int = 0,
    val ly: Int = 0,
    val lt: Int = 0,
    val rt: Int = 0,
) {
    companion object {
        val NEUTRAL = PadState()
    }
}

object Protocol {
    const val MAGIC = 0xC7
    const val PACKET_SIZE = 12
    const val ACK_SIZE = 5
    const val TAG_SIZE = 8 // truncated HMAC-SHA256 appended when a key is set
    const val ACK_OK = 0
    const val ACK_BUSY = 1
    const val ACK_BAD_KEY = 2 // the server wants a key and ours is missing or wrong

    data class Ack(val slot: Int, val seq: Int, val status: Int)

    /** Keys are typed on a phone, so case, dashes and spaces are ignored (same rule as the server). */
    fun normalizeKey(text: String) =
        text.filter { it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' }.uppercase()

    /** The signer for [key], or null when no key is set. Not thread-safe: keep it on one thread. */
    fun newMac(key: String): Mac? {
        val normalized = normalizeKey(key)
        if (normalized.isEmpty()) return null
        return Mac.getInstance("HmacSHA256").apply {
            init(SecretKeySpec(normalized.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        }
    }

    /** Bytes of a state packet: 12, or 20 with the tag. */
    fun packetSize(mac: Mac?) = if (mac == null) PACKET_SIZE else PACKET_SIZE + TAG_SIZE

    /**
     * Writes the state packet into [out] (little-endian) and returns it. With [mac] the 8-byte tag
     * is appended, so [out] must have [packetSize] bytes.
     */
    fun encode(
        slot: Int,
        seq: Int,
        s: PadState,
        out: ByteArray = ByteArray(PACKET_SIZE),
        mac: Mac? = null,
    ): ByteArray {
        ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN).apply {
            put(MAGIC.toByte())
            put(slot.toByte())
            putShort(seq.toShort())
            putShort(s.buttons.toShort())
            putShort(s.lx.toShort())
            putShort(s.ly.toShort())
            put(s.lt.toByte())
            put(s.rt.toByte())
        }
        if (mac != null) {
            mac.update(out, 0, PACKET_SIZE)
            System.arraycopy(mac.doFinal(), 0, out, PACKET_SIZE, TAG_SIZE)
        }
        return out
    }

    const val DISCOVER_REQUEST = 0xD0
    const val DISCOVER_REPLY = 0xD1
    private const val DISCOVER_REPLY_HEADER = 6

    data class Discovered(val port: Int, val players: Int, val name: String)

    /** "Who is there?" broadcast: magic, type, version. */
    fun encodeDiscover() = byteArrayOf(MAGIC.toByte(), DISCOVER_REQUEST.toByte(), 1)

    /** Server answer: magic, type, port (u16), players, name length, name (UTF-8). */
    fun decodeDiscoverReply(data: ByteArray, length: Int): Discovered? {
        if (length < DISCOVER_REPLY_HEADER) return null
        if ((data[0].toInt() and 0xFF) != MAGIC || (data[1].toInt() and 0xFF) != DISCOVER_REPLY) return null
        val b = ByteBuffer.wrap(data, 0, length).order(ByteOrder.LITTLE_ENDIAN)
        b.position(2)
        val port = b.short.toInt() and 0xFFFF
        val players = b.get().toInt() and 0xFF
        val nameLength = b.get().toInt() and 0xFF
        if (length != DISCOVER_REPLY_HEADER + nameLength) return null
        return Discovered(port, players, String(data, DISCOVER_REPLY_HEADER, nameLength, Charsets.UTF_8))
    }

    fun decodeAck(data: ByteArray, length: Int): Ack? {
        if (length != ACK_SIZE || (data[0].toInt() and 0xFF) != MAGIC) return null
        val b = ByteBuffer.wrap(data, 0, length).order(ByteOrder.LITTLE_ENDIAN)
        b.get() // magic
        val slot = b.get().toInt() and 0xFF
        val seq = b.short.toInt() and 0xFFFF
        val status = b.get().toInt() and 0xFF
        return Ack(slot, seq, status)
    }
}

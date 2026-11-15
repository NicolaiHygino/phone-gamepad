package dev.androidcontroller.pad

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProtocolTest {
    private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    @Test
    fun encodeMatchesPythonReference() {
        // Produced by server/protocol.py: encode(1, 0xFFFF, State(buttons=A|DPAD_LEFT, lx=-32768, ly=32767, lt=7, rt=255))
        val state = PadState(buttons = Btn.A or Btn.DPAD_LEFT, lx = -32768, ly = 32767, lt = 7, rt = 255)
        assertArrayEquals(hex("c701ffff01200080ff7f07ff"), Protocol.encode(1, 0xFFFF, state))
    }

    @Test
    fun neutralPacketIsAllZeroPayload() {
        assertArrayEquals(hex("c70001000000000000000000"), Protocol.encode(0, 1, PadState.NEUTRAL))
    }

    @Test
    fun decodeAckMatchesPythonReference() {
        // server/protocol.py: encode_ack(1, 0xFFFE, ACK_BUSY)
        val ack = Protocol.decodeAck(hex("c701feff01"), 5)
        assertEquals(Protocol.Ack(slot = 1, seq = 0xFFFE, status = Protocol.ACK_BUSY), ack)
    }

    @Test
    fun signedPacketMatchesPythonReference() {
        // server/protocol.py: encode(1, 0xFFFF, <same state>, b"TESTKEY1234"): 12 bytes + 8-byte HMAC tag
        val state = PadState(buttons = Btn.A or Btn.DPAD_LEFT, lx = -32768, ly = 32767, lt = 7, rt = 255)
        val mac = Protocol.newMac("test-key-1234") // typed with a dash and lowercase: same key as TESTKEY1234
        val out = Protocol.encode(1, 0xFFFF, state, ByteArray(Protocol.packetSize(mac)), mac)
        assertArrayEquals(hex("c701ffff01200080ff7f07ff6de7b4f77d977213"), out)
    }

    @Test
    fun keysAreNormalisedLikeTheServer() {
        assertEquals("TESTKEY1234", Protocol.normalizeKey(" test-key 1234 "))
        assertNull(Protocol.newMac(""))
        assertNull(Protocol.newMac(" - ")) // nothing left after normalising
        assertEquals(12, Protocol.packetSize(null))
        assertEquals(20, Protocol.packetSize(Protocol.newMac("abc")))
    }

    @Test
    fun oneSignerCanSignManyPackets() {
        val mac = Protocol.newMac("TESTKEY1234")
        val state = PadState(buttons = Btn.B)
        val first = Protocol.encode(0, 1, state, ByteArray(20), mac).copyOf()
        Protocol.encode(0, 2, PadState(), ByteArray(20), mac)
        val again = Protocol.encode(0, 1, state, ByteArray(20), mac)
        assertArrayEquals(first, again) // the signer carries no state from one packet to the next
    }

    @Test
    fun discoverRequestMatchesPythonReference() {
        // server/protocol.py: encode_discover()
        assertArrayEquals(hex("c7d001"), Protocol.encodeDiscover())
    }

    @Test
    fun decodeDiscoverReplyMatchesPythonReference() {
        // server/protocol.py: encode_discover_reply(9876, 2, "my-pc")
        val raw = hex("c7d1942602056d792d7063")
        assertEquals(Protocol.Discovered(port = 9876, players = 2, name = "my-pc"), Protocol.decodeDiscoverReply(raw, raw.size))
        // server/protocol.py: encode_discover_reply(1, 1, "ééé")
        val utf8 = hex("c7d101000106c3a9c3a9c3a9")
        assertEquals("ééé", Protocol.decodeDiscoverReply(utf8, utf8.size)?.name)
    }

    @Test
    fun decodeDiscoverReplyRejectsGarbage() {
        val good = hex("c7d1942602056d792d7063")
        assertNull(Protocol.decodeDiscoverReply(good, good.size - 1)) // name shorter than declared
        assertNull(Protocol.decodeDiscoverReply(good, 3)) // too short for the header
        assertNull(Protocol.decodeDiscoverReply(hex("00d1942602056d792d7063"), good.size)) // wrong magic
        assertNull(Protocol.decodeDiscoverReply(hex("c7d001"), 3)) // a request, not a reply
    }

    @Test
    fun decodeAckRejectsGarbage() {
        assertNull(Protocol.decodeAck(hex("c701feff01"), 4)) // wrong length
        assertNull(Protocol.decodeAck(hex("00" + "01feff01"), 5)) // wrong magic
    }
}

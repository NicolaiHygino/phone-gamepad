package dev.androidcontroller.pad

import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.LockSupport

/**
 * Process-wide UDP client for one server address and player slot.
 *
 * It owns ONE socket for the whole session: the server treats a new source port as another phone
 * and refuses it ("busy") for up to 0.5 s, so the socket must survive activity restarts.
 * A sender thread transmits the full state on every change plus a ~60 Hz heartbeat;
 * a receiver thread reads the server's acks to report connection status and round-trip time.
 */
object PadClient {
    private const val HEARTBEAT_NS = 16_000_000L // ~60 Hz
    private const val IDLE_NS = 200_000_000L
    private const val STALE_NS = 1_500_000_000L // no ack for this long = no reply
    private const val RING = 1024 // send timestamps kept for RTT matching
    private const val RTT_SAMPLES = 600 // ~10 s of acks at 60 Hz

    enum class Link { OFF, NO_REPLY, OK, BUSY, BAD_KEY, ERROR }

    /** [rttMs] is the median round trip and [maxMs] the worst one over the last ~10 s. */
    data class Status(val link: Link, val rttMs: Float = 0f, val maxMs: Float = 0f, val detail: String = "")

    data class RttStats(val median: Float, val p99: Float, val max: Float, val count: Int)

    /** Summarises the first [n] samples (nanoseconds) of [samples]. */
    internal fun summarize(samples: LongArray, n: Int): RttStats? {
        if (n <= 0) return null
        val sorted = samples.copyOf(n).also { it.sort() }
        fun ms(i: Int) = sorted[i.coerceIn(0, n - 1)] / 1_000_000f
        return RttStats(median = ms(n / 2), p99 = ms(n * 99 / 100), max = ms(n - 1), count = n)
    }

    fun rttStats(): RttStats? = session?.rttStats()

    private val state = AtomicReference(PadState.NEUTRAL)

    @Volatile
    private var session: Session? = null

    /** (Re)connects. Does nothing if the target is unchanged, so the socket is kept. */
    @Synchronized
    fun configure(host: String, port: Int, slot: Int, key: String = "") {
        val current = session
        if (current != null && current.host == host && current.port == port &&
            current.slot == slot && current.key == key
        ) {
            return
        }
        current?.close()
        session = Session(host, port, slot, key).also { it.start() }
    }

    /** Publishes a new controller state; sends immediately if it changed. */
    fun update(s: PadState) {
        if (state.getAndSet(s) != s) session?.wake()
    }

    /** While inactive (app in background) the pad is released and nothing is sent. */
    fun setActive(active: Boolean) {
        if (!active) state.set(PadState.NEUTRAL)
        session?.let {
            it.active = active
            it.wake()
        }
    }

    fun status(): Status {
        val s = session ?: return Status(Link.OFF)
        s.error?.let { return Status(Link.ERROR, detail = it) }
        val age = System.nanoTime() - s.lastAckNs
        return when {
            s.lastAckNs == 0L || age > STALE_NS -> Status(Link.NO_REPLY)
            s.lastStatus == Protocol.ACK_BAD_KEY -> Status(Link.BAD_KEY)
            s.lastStatus == Protocol.ACK_BUSY -> Status(Link.BUSY)
            else -> {
                val r = s.rttStats()
                Status(Link.OK, rttMs = r?.median ?: 0f, maxMs = r?.max ?: 0f)
            }
        }
    }

    private class Session(val host: String, val port: Int, val slot: Int, val key: String) {
        private val socket = DatagramSocket()
        private val mac = Protocol.newMac(key) // used by the sender thread only
        private val sentAt = LongArray(RING)
        private var seq = 0

        @Volatile var stop = false
        @Volatile var active = true
        @Volatile var lastAckNs = 0L
        @Volatile var lastStatus = -1
        @Volatile var error: String? = null

        private val rttRing = LongArray(RTT_SAMPLES)

        @Volatile private var rttCount = 0 // written only by the receiver thread

        fun rttStats() = summarize(rttRing, minOf(rttCount, RTT_SAMPLES))

        private val sender = Thread(::sendLoop, "pad-send").apply { isDaemon = true }
        private val receiver = Thread(::receiveLoop, "pad-recv").apply { isDaemon = true }

        fun start() {
            sender.start()
            receiver.start()
        }

        fun wake() = LockSupport.unpark(sender)

        fun close() {
            stop = true
            wake()
            socket.close()
        }

        private fun sendLoop() {
            val addr = try {
                InetAddress.getByName(host)
            } catch (e: IOException) {
                error = "bad address: $host"
                return
            }
            val buf = ByteArray(Protocol.packetSize(mac))
            val packet = DatagramPacket(buf, buf.size, addr, port)

            fun send(s: PadState) {
                seq = (seq + 1) and 0xFFFF
                Protocol.encode(slot, seq, s, buf, mac)
                sentAt[seq and (RING - 1)] = System.nanoTime()
                try {
                    socket.send(packet)
                    error = null
                } catch (e: IOException) {
                    if (!stop) error = e.message ?: "send failed"
                }
            }

            var idleSent = false
            while (!stop) {
                if (active) {
                    send(state.get())
                    idleSent = false
                } else if (!idleSent) {
                    send(PadState.NEUTRAL) // release everything once, then stay quiet
                    idleSent = true
                }
                LockSupport.parkNanos(if (active) HEARTBEAT_NS else IDLE_NS)
            }
        }

        private fun receiveLoop() {
            val buf = ByteArray(16)
            val packet = DatagramPacket(buf, buf.size)
            socket.soTimeout = 500
            while (!stop) {
                packet.length = buf.size // receive() shrinks it to the last message's length
                try {
                    socket.receive(packet)
                } catch (e: SocketTimeoutException) {
                    continue
                } catch (e: IOException) {
                    if (stop) return
                    Thread.sleep(100)
                    continue
                }
                val ack = Protocol.decodeAck(buf, packet.length) ?: continue
                if (ack.slot != slot) continue
                val now = System.nanoTime()
                val sent = sentAt[ack.seq and (RING - 1)]
                if (ack.status == Protocol.ACK_OK && sent != 0L && now - sent in 1..2_000_000_000L) {
                    rttRing[rttCount % RTT_SAMPLES] = now - sent
                    rttCount += 1
                }
                lastStatus = ack.status
                lastAckNs = now
            }
        }
    }
}

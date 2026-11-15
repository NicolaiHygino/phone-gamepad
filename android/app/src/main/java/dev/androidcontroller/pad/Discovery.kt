package dev.androidcontroller.pad

import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.SocketTimeoutException

/** Finds pad servers on the local network, so the PC's address never has to be typed. */
object Discovery {
    data class Found(val host: String, val port: Int, val players: Int, val name: String)

    /**
     * Blocking: call it off the main thread. Broadcasts a request to [port] and collects answers
     * for [waitMs]. Both the limited broadcast and each Wi-Fi/Ethernet subnet's own broadcast
     * address are used, because routers and phones differ in which one they pass along.
     */
    fun scan(port: Int, waitMs: Long = 1500): List<Found> {
        val found = LinkedHashMap<String, Found>()
        DatagramSocket().use { socket ->
            socket.broadcast = true
            socket.soTimeout = 200
            val request = Protocol.encodeDiscover()
            for (target in broadcastAddresses()) {
                try {
                    socket.send(DatagramPacket(request, request.size, target, port))
                } catch (_: IOException) {
                    // that network is not usable right now; the others may still work
                }
            }
            val buf = ByteArray(128)
            val packet = DatagramPacket(buf, buf.size)
            val end = System.nanoTime() + waitMs * 1_000_000
            while (System.nanoTime() < end) {
                packet.length = buf.size
                try {
                    socket.receive(packet)
                } catch (_: SocketTimeoutException) {
                    continue
                } catch (_: IOException) {
                    break
                }
                val reply = Protocol.decodeDiscoverReply(buf, packet.length) ?: continue
                val host = packet.address.hostAddress ?: continue
                found.putIfAbsent(host, Found(host, reply.port, reply.players, reply.name))
            }
        }
        return found.values.toList()
    }

    private fun broadcastAddresses(): List<InetAddress> {
        val out = linkedSetOf(InetAddress.getByName("255.255.255.255"))
        try {
            for (ni in NetworkInterface.getNetworkInterfaces()) {
                if (!ni.isUp || ni.isLoopback) continue
                for (address in ni.interfaceAddresses) address.broadcast?.let { out.add(it) }
            }
        } catch (_: IOException) {
            // fall back to the limited broadcast alone
        }
        return out.toList()
    }
}

package com.skz.localdrop.core

import java.net.Inet4Address
import java.net.NetworkInterface

/** Local IPv4 addresses (desktop/localdrop/net/ifaces.py). */
object Net {
    data class Iface(val name: String, val ip: String, val prefix: Int)

    private val VIRTUAL = Regex("^(tun|ppp|rmnet|ccmni|dummy|ipsec|clat|v4-|p2p|aware|nan|lo)", RegexOption.IGNORE_CASE)

    fun list(): List<Iface> = runCatching {
        NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp && !it.isLoopback }.flatMap { ni ->
            ni.interfaceAddresses.filter { it.address is Inet4Address }.map { Iface(ni.name, it.address.hostAddress ?: "", it.networkPrefixLength.toInt()) }
        }
    }.getOrDefault(emptyList())

    /** LAN interfaces first: Wi-Fi, Ethernet, hotspot; mobile data and VPN last. */
    fun lan(): List<Iface> = list().filter { !VIRTUAL.containsMatchIn(it.name) }.sortedBy {
        when {
            it.name.startsWith("wlan") -> 0
            it.name.startsWith("eth") -> 1
            it.name.startsWith("ap") || it.name.startsWith("swlan") -> 2
            else -> 3
        }
    }

    fun pickIp(): String = lan().firstOrNull()?.ip ?: ""

    fun broadcast(ip: String, prefix: Int): String? {
        if (prefix !in 1..30) return null
        val parts = ip.split(".").map { it.toInt() }
        val addr = (parts[0] shl 24) or (parts[1] shl 16) or (parts[2] shl 8) or parts[3]
        val mask = (-1) shl (32 - prefix)
        val b = addr or mask.inv()
        return "${(b ushr 24) and 255}.${(b ushr 16) and 255}.${(b ushr 8) and 255}.${b and 255}"
    }

    fun sameSubnet(a: String, b: String, prefix: Int): Boolean = runCatching {
        fun n(s: String) = s.split(".").map { it.toInt() }.fold(0) { acc, x -> (acc shl 8) or x }
        val mask = if (prefix == 0) 0 else (-1) shl (32 - prefix)
        (n(a) and mask) == (n(b) and mask)
    }.getOrDefault(false)
}

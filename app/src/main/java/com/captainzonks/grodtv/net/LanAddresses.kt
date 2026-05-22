package com.captainzonks.grodtv.net

import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * Return non-loopback IPv4 addresses on UP interfaces. First-run screen uses
 * these to tell the user what IP to enter in grod_remote. We skip IPv6 and
 * link-local entries because remotes target the LAN form (192.168.x / 10.x).
 */
fun getLanAddresses(): List<String> = buildList {
    val ifaces = try {
        NetworkInterface.getNetworkInterfaces() ?: return@buildList
    } catch (_: Exception) {
        return@buildList
    }
    for (iface in ifaces) {
        if (!iface.isUp || iface.isLoopback) continue
        for (addr in iface.inetAddresses) {
            if (addr is Inet4Address && !addr.isLoopbackAddress && !addr.isLinkLocalAddress) {
                add(addr.hostAddress ?: continue)
            }
        }
    }
}

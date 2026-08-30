package com.darelisme.sweetspot.server

import java.net.Inet4Address
import java.net.NetworkInterface

object NetworkUtils {

    /**
     * Best-effort LAN IPv4 address used to show the control URL.
     * No permission required (enumerates local interfaces).
     */
    fun getLanIpAddress(): String? {
        return try {
            NetworkInterface.getNetworkInterfaces()
                ?.asSequence()
                ?.flatMap { it.inetAddresses.asSequence() }
                ?.filter { !it.isLoopbackAddress && it is Inet4Address }
                ?.map { it.hostAddress }
                ?.firstOrNull()
        } catch (e: Exception) {
            null
        }
    }
}

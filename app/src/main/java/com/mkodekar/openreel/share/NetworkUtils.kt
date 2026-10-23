// SPDX-License-Identifier: GPL-3.0-or-later
package com.mkodekar.openreel.share

import java.net.Inet4Address
import java.net.NetworkInterface

object NetworkUtils {

    /** Best-guess local IPv4 address on the current Wi-Fi/hotspot network. */
    fun localIpv4(): String? {
        return runCatching {
            NetworkInterface.getNetworkInterfaces().asSequence()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.inetAddresses.asSequence() }
                .filterIsInstance<Inet4Address>()
                .firstOrNull { !it.isLoopbackAddress }
                ?.hostAddress
        }.getOrNull()
    }

    fun shareUrl(port: Int): String? = localIpv4()?.let { "http://$it:$port/" }
}

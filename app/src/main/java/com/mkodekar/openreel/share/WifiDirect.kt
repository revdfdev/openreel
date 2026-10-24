// SPDX-License-Identifier: GPL-3.0-or-later
package com.mkodekar.openreel.share

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Wi-Fi Direct (Wi-Fi P2P) transport for device-to-device sharing.
 *
 * Why this and not just the shared Wi-Fi network: Wi-Fi Direct forms its own
 * group between the two phones, so transfers work with no router, no shared
 * SSID and no internet -- which is the point of sharing files in person, and
 * what MX's transfer feature and LocalSend's hotspot mode both do.
 *
 * Shape of a transfer:
 *   sender    createGroup()  -> becomes group owner, always 192.168.49.1,
 *                               and ShareServer serves over that interface
 *   receiver  discover() -> connect(device) -> onConnectionInfo gives
 *                               groupOwnerAddress, then ShareClient pulls
 *
 * The existing HTTP transport is unchanged; this only supplies the link.
 *
 * Permissions: NEARBY_WIFI_DEVICES on API 33+, otherwise ACCESS_FINE_LOCATION.
 * Both are declared in the manifest; the caller must have one granted at
 * runtime or discovery silently returns nothing.
 */
class WifiDirect(private val context: Context) {

    /** Group owner's address in a Wi-Fi Direct group; fixed by the framework. */
    companion object {
        const val GROUP_OWNER_ADDRESS = "192.168.49.1"

        /** The runtime permission this API needs on the running OS version. */
        fun requiredPermission(): String =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                android.Manifest.permission.NEARBY_WIFI_DEVICES
            else
                android.Manifest.permission.ACCESS_FINE_LOCATION
    }

    sealed interface State {
        data object Idle : State
        data object Unsupported : State
        data object Discovering : State
        /** We are the group owner; peers can pull from [url]. */
        data class Hosting(val url: String) : State
        /** We joined a group owned by someone else. */
        data class Joined(val hostUrl: String) : State
        data class Failed(val reason: String) : State
    }

    private val manager: WifiP2pManager? =
        context.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
    private var channel: WifiP2pManager.Channel? = null
    private var receiver: BroadcastReceiver? = null

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state

    private val _peers = MutableStateFlow<List<WifiP2pDevice>>(emptyList())
    val peers: StateFlow<List<WifiP2pDevice>> = _peers

    val isSupported: Boolean get() = manager != null

    /** Registers for P2P broadcasts. Call from onStart/onResume. */
    @SuppressLint("MissingPermission")
    fun start() {
        val m = manager
        if (m == null) { _state.value = State.Unsupported; return }
        val ch = m.initialize(context, context.mainLooper, null).also { channel = it }

        val filter = IntentFilter().apply {
            addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
        }
        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) {
                when (intent?.action) {
                    WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
                        val enabled = intent.getIntExtra(
                            WifiP2pManager.EXTRA_WIFI_STATE, -1
                        ) == WifiP2pManager.WIFI_P2P_STATE_ENABLED
                        if (!enabled) _state.value = State.Failed("Wi-Fi Direct is off")
                    }
                    WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION ->
                        runCatching {
                            m.requestPeers(ch) { list ->
                                _peers.value = list.deviceList.toList()
                            }
                        }
                    WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION ->
                        runCatching {
                            m.requestConnectionInfo(ch) { info -> onConnectionInfo(info) }
                        }
                }
            }
        }
        // API 33 requires an explicit export flag for runtime receivers.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(r, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            context.registerReceiver(r, filter)
        }
        receiver = r
    }

    fun stop() {
        receiver?.let { runCatching { context.unregisterReceiver(it) } }
        receiver = null
    }

    private fun onConnectionInfo(info: WifiP2pInfo) {
        if (!info.groupFormed) return
        val host = info.groupOwnerAddress?.hostAddress ?: GROUP_OWNER_ADDRESS
        _state.value =
            if (info.isGroupOwner) State.Hosting("http://$GROUP_OWNER_ADDRESS:${ShareService.PORT}/")
            else State.Joined("http://$host:${ShareService.PORT}/")
    }

    /**
     * Sender side: form a group we own. Peers then see us in their scan and
     * pull over [GROUP_OWNER_ADDRESS].
     */
    @SuppressLint("MissingPermission")
    fun host() {
        val m = manager ?: return
        val ch = channel ?: return
        runCatching {
            m.createGroup(ch, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    _state.value = State.Hosting(
                        "http://$GROUP_OWNER_ADDRESS:${ShareService.PORT}/"
                    )
                }
                override fun onFailure(reason: Int) {
                    // BUSY usually means a group already exists; reuse it.
                    if (reason == WifiP2pManager.BUSY) {
                        m.requestConnectionInfo(ch) { info -> onConnectionInfo(info) }
                    } else {
                        _state.value = State.Failed(reasonText(reason))
                    }
                }
            })
        }
    }

    /** Tears the group down so Wi-Fi returns to normal. */
    @SuppressLint("MissingPermission")
    fun stopHosting() {
        val m = manager ?: return
        val ch = channel ?: return
        runCatching { m.removeGroup(ch, null) }
        _peers.value = emptyList()
        _state.value = State.Idle
    }

    /** Receiver side: scan for nearby senders. */
    @SuppressLint("MissingPermission")
    fun discover() {
        val m = manager ?: return
        val ch = channel ?: return
        _state.value = State.Discovering
        runCatching {
            m.discoverPeers(ch, object : WifiP2pManager.ActionListener {
                override fun onSuccess() = Unit
                override fun onFailure(reason: Int) {
                    _state.value = State.Failed(reasonText(reason))
                }
            })
        }
    }

    /** Receiver side: join the chosen sender's group. */
    @SuppressLint("MissingPermission")
    fun connect(device: WifiP2pDevice) {
        val m = manager ?: return
        val ch = channel ?: return
        val config = android.net.wifi.p2p.WifiP2pConfig().apply {
            deviceAddress = device.deviceAddress
            // Let the sender own the group, since it is the one serving.
            groupOwnerIntent = 0
        }
        runCatching {
            m.connect(ch, config, object : WifiP2pManager.ActionListener {
                override fun onSuccess() = Unit   // connection info arrives by broadcast
                override fun onFailure(reason: Int) {
                    _state.value = State.Failed(reasonText(reason))
                }
            })
        }
    }

    private fun reasonText(reason: Int) = when (reason) {
        WifiP2pManager.P2P_UNSUPPORTED -> "Wi-Fi Direct is not supported here"
        WifiP2pManager.BUSY -> "Wi-Fi Direct is busy, try again"
        WifiP2pManager.ERROR -> "Wi-Fi Direct error"
        else -> "Wi-Fi Direct failed ($reason)"
    }
}

/** Human label for a peer in the share UI. */
val WifiP2pDevice.label: String
    get() = deviceName?.takeIf { it.isNotBlank() } ?: deviceAddress ?: "Unknown device"

/** Maps WifiP2pDevice.status to something worth showing. */
val WifiP2pDevice.statusLabel: String
    get() = when (status) {
        WifiP2pDevice.AVAILABLE -> "Available"
        WifiP2pDevice.INVITED -> "Invited"
        WifiP2pDevice.CONNECTED -> "Connected"
        WifiP2pDevice.FAILED -> "Failed"
        WifiP2pDevice.UNAVAILABLE -> "Unavailable"
        else -> ""
    }

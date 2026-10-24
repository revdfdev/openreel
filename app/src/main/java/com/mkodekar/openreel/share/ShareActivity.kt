// SPDX-License-Identifier: GPL-3.0-or-later
package com.mkodekar.openreel.share

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.net.wifi.p2p.WifiP2pDevice
import android.os.Build
import android.os.Bundle
import android.os.Environment
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.mkodekar.openreel.ui.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.cos
import kotlin.math.sin

/**
 * Device-to-device share over Wi-Fi Direct, with LocalSend's visual language:
 * a sonar sweep while discoverable or scanning, and transfers as live rows.
 *
 * Wi-Fi Direct forms a group directly between the two phones, so this works
 * with no router and no internet. The sender becomes the group owner (always
 * 192.168.49.1) and ShareServer serves over that link; the receiver joins and
 * pulls with ShareClient. The HTTP transport is unchanged -- Wi-Fi Direct only
 * supplies the link.
 */
class ShareActivity : ComponentActivity() {

    private lateinit var p2p: WifiDirect

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        window.navigationBarColor = android.graphics.Color.TRANSPARENT

        val themePref = com.mkodekar.openreel.settings.Settings(this).theme
        p2p = WifiDirect(this)
        val shared = sharedUris(intent)

        setContent {
            MxTheme(dark = rememberThemeIsDark(themePref)) {
                var tab by remember { mutableIntStateOf(0) }
                Column(
                    Modifier
                        .fillMaxSize()
                        .background(Mx.colors.background)
                        .windowInsetsPadding(WindowInsets.navigationBars)
                ) {
                    MxStatusBarScrim()
                    MxToolbar(title = "Share", onBack = { finish() })
                    MxTabStrip(listOf("Send", "Receive"), tab) { tab = it }
                    if (tab == 0) SendTab(shared) else ReceiveTab()
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        p2p.start()
    }

    override fun onStop() {
        super.onStop()
        p2p.stop()
    }

    // --- Send ----------------------------------------------------------------

    @Composable
    private fun SendTab(shared: List<Uri>) {
        val scope = rememberCoroutineScope()
        var staged by remember { mutableStateOf<List<File>>(emptyList()) }
        var preparing by remember { mutableStateOf(shared.isNotEmpty()) }
        val state by p2p.state.collectAsState()
        val hosting = state as? WifiDirect.State.Hosting

        val picker = rememberLauncherForActivityResult(
            ActivityResultContracts.OpenMultipleDocuments()
        ) { uris ->
            if (uris.isEmpty()) return@rememberLauncherForActivityResult
            scope.launch {
                val files = withContext(Dispatchers.IO) { stageForSharing(uris) }
                staged = files
                if (files.isNotEmpty()) {
                    startSharing(files)
                    p2p.host()          // become the Wi-Fi Direct group owner
                }
            }
        }

        val askPermission = rememberLauncherForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { granted -> if (granted) picker.launch(arrayOf("*/*")) }

        // Arrived through the system share sheet: stage and host straight
        // away rather than asking the user to pick the files again.
        LaunchedEffect(shared) {
            if (shared.isEmpty()) return@LaunchedEffect
            val files = withContext(Dispatchers.IO) { stageForSharing(shared) }
            staged = files
            preparing = false
            if (files.isNotEmpty()) {
                startSharing(files)
                p2p.host()
            }
        }

        Column(
            Modifier.fillMaxSize().padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (hosting == null) {
                Spacer(Modifier.height(36.dp))
                Sonar(active = preparing) { MxPlayGlyph(26.dp, Mx.colors.textSecondary) }
                Spacer(Modifier.height(30.dp))
                MxText(
                    if (preparing)
                        "Preparing ${shared.size} file(s) to send…"
                    else
                        "Pick files to send. Your phone hosts them over Wi-Fi Direct, so no router or internet is needed.",
                    Mx.type.primary,
                    Mx.colors.textSecondary,
                )
                if (!preparing) {
                    Spacer(Modifier.height(18.dp))
                    MxButton("Choose files") {
                        if (hasP2pPermission()) picker.launch(arrayOf("*/*"))
                        else askPermission.launch(WifiDirect.requiredPermission())
                    }
                }
                StateNote(state)
            } else {
                Spacer(Modifier.height(20.dp))
                Sonar(active = true) { QrTile(hosting.url) }
                Spacer(Modifier.height(24.dp))
                MxText("Discoverable over Wi-Fi Direct", Mx.type.mediumPrimary)
                Spacer(Modifier.height(4.dp))
                MxText(hosting.url, Mx.type.secondary, Mx.colors.textSecondary)
                Spacer(Modifier.height(18.dp))
                MxSectionLabel("Offering ${staged.size} file(s)")
                LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
                    items(staged, key = { it.absolutePath }) { f ->
                        OfferRow(f.name, f.length())
                    }
                }
                MxButton("Stop sharing", filled = false) {
                    stopService(Intent(this@ShareActivity, ShareService::class.java))
                    p2p.stopHosting()
                    staged = emptyList()
                }
            }
        }
    }

    // --- Receive -------------------------------------------------------------

    @Composable
    private fun ReceiveTab() {
        val scope = rememberCoroutineScope()
        val state by p2p.state.collectAsState()
        val peers by p2p.peers.collectAsState()
        var offered by remember { mutableStateOf<List<ShareClient.Offered>>(emptyList()) }
        var status by remember { mutableStateOf<String?>(null) }
        val progress = remember { mutableStateMapOf<Int, Float>() }

        val joined = state as? WifiDirect.State.Joined

        // Once the group forms, read what the host is offering.
        LaunchedEffect(joined?.hostUrl) {
            val url = joined?.hostUrl ?: return@LaunchedEffect
            status = "Reading what's on offer…"
            offered = withContext(Dispatchers.IO) {
                // The link takes a moment to come up after the group forms.
                repeat(6) {
                    val r = runCatching { ShareClient.fetchManifest(url) }.getOrNull()
                    if (!r.isNullOrEmpty()) return@withContext r
                    kotlinx.coroutines.delay(800)
                }
                emptyList()
            }
            status = if (offered.isEmpty()) "Host reachable but offering nothing." else null
        }

        val askPermission = rememberLauncherForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { granted -> if (granted) p2p.discover() }

        Column(Modifier.fillMaxSize().padding(20.dp)) {
            if (offered.isEmpty()) {
                Spacer(Modifier.height(20.dp))
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Sonar(active = state is WifiDirect.State.Discovering) {
                        MxText("${peers.size}", Mx.type.toolbar, Mx.colors.textSecondary)
                    }
                }
                Spacer(Modifier.height(22.dp))
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    MxButton(
                        if (state is WifiDirect.State.Discovering) "Scanning…"
                        else "Scan for senders"
                    ) {
                        if (hasP2pPermission()) p2p.discover()
                        else askPermission.launch(WifiDirect.requiredPermission())
                    }
                }
                StateNote(state)

                if (peers.isNotEmpty()) {
                    Spacer(Modifier.height(10.dp))
                    MxSectionLabel("Nearby devices")
                    LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
                        items(peers, key = { it.deviceAddress }) { d ->
                            PeerRow(d) { p2p.connect(d) }
                        }
                    }
                }
            } else {
                MxSectionLabel("Available from ${joined?.hostUrl.orEmpty()}")
                LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
                    items(offered, key = { it.index }) { item ->
                        val p = progress[item.index]
                        Column(Modifier.padding(vertical = 10.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    MxText(item.name, Mx.type.mediumPrimary, maxLines = 1)
                                    MxText(
                                        formatSize(item.size),
                                        Mx.type.secondary,
                                        Mx.colors.textSecondary,
                                    )
                                }
                                if (p == null) {
                                    MxButton("Get", filled = false) {
                                        val url = joined?.hostUrl ?: return@MxButton
                                        scope.launch {
                                            progress[item.index] = 0f
                                            withContext(Dispatchers.IO) {
                                                runCatching {
                                                    ShareClient.download(url, item, downloadDir()) { f ->
                                                        progress[item.index] = f
                                                    }
                                                }
                                            }
                                            progress[item.index] = 1f
                                        }
                                    }
                                } else if (p >= 1f) {
                                    MxText("Saved", Mx.type.secondary, Mx.colors.accent)
                                }
                            }
                            AnimatedVisibility(visible = p != null && p < 1f) {
                                Column {
                                    Spacer(Modifier.height(8.dp))
                                    MxProgress(p ?: 0f)
                                }
                            }
                        }
                        MxDivider()
                    }
                }
            }
            status?.let {
                Spacer(Modifier.height(10.dp))
                MxText(it, Mx.type.secondary, Mx.colors.textSecondary)
            }
        }
    }

    // --- pieces --------------------------------------------------------------

    @Composable
    private fun StateNote(state: WifiDirect.State) {
        val text = when (state) {
            is WifiDirect.State.Failed -> state.reason
            WifiDirect.State.Unsupported -> "This device has no Wi-Fi Direct support."
            else -> null
        } ?: return
        Spacer(Modifier.height(14.dp))
        MxText(text, Mx.type.secondary, Mx.colors.textSecondary)
    }

    @Composable
    private fun PeerRow(device: WifiP2pDevice, onConnect: () -> Unit) {
        Row(
            Modifier.fillMaxWidth().padding(vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(34.dp).clip(RoundedCornerShape(17.dp))
                    .background(Mx.colors.surface),
                contentAlignment = Alignment.Center,
            ) { MxText(device.label.take(1).uppercase(), Mx.type.mediumPrimary) }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                MxText(device.label, Mx.type.mediumPrimary, maxLines = 1)
                MxText(device.statusLabel, Mx.type.secondary, Mx.colors.textSecondary)
            }
            MxButton("Connect", filled = false, onClick = onConnect)
        }
        MxDivider(startIndent = 46.dp)
    }

    @Composable
    private fun QrTile(url: String) {
        val bmp = remember(url) { runCatching { QrCodes.generate(url, 420) }.getOrNull() }
        Box(
            Modifier.size(118.dp).clip(RoundedCornerShape(10.dp))
                .background(Color.White).padding(7.dp),
            contentAlignment = Alignment.Center,
        ) {
            bmp?.let {
                androidx.compose.foundation.Image(
                    bitmap = it.asImageBitmap(),
                    contentDescription = "Pairing QR code",
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }

    @Composable
    private fun OfferRow(name: String, size: Long) {
        Row(
            Modifier.fillMaxWidth().padding(vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(34.dp).clip(RoundedCornerShape(6.dp))
                    .background(Mx.colors.surface),
                contentAlignment = Alignment.Center,
            ) { MxPlayGlyph(12.dp, Mx.colors.textSecondary) }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                MxText(name, Mx.type.mediumPrimary, maxLines = 1)
                MxText(formatSize(size), Mx.type.secondary, Mx.colors.textSecondary)
            }
        }
        MxDivider(startIndent = 46.dp)
    }

    // --- plumbing ------------------------------------------------------------

    private fun hasP2pPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, WifiDirect.requiredPermission()) ==
            PackageManager.PERMISSION_GRANTED

    private fun startSharing(files: List<File>) {
        val intent = Intent(this, ShareService::class.java).putStringArrayListExtra(
            ShareService.EXTRA_FILES, ArrayList(files.map { it.absolutePath })
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent)
        else startService(intent)
    }

    /**
     * URIs handed to us by the system share sheet. ACTION_SEND carries one in
     * EXTRA_STREAM and ACTION_SEND_MULTIPLE carries a list, so both are read
     * here; anything else gives an empty list and the normal picker is shown.
     */
    private fun sharedUris(intent: Intent?): List<Uri> = when (intent?.action) {
        Intent.ACTION_SEND ->
            listOfNotNull(
                androidx.core.content.IntentCompat.getParcelableExtra(
                    intent, Intent.EXTRA_STREAM, Uri::class.java
                )
            )
        Intent.ACTION_SEND_MULTIPLE ->
            androidx.core.content.IntentCompat.getParcelableArrayListExtra(
                intent, Intent.EXTRA_STREAM, Uri::class.java
            ).orEmpty()
        else -> emptyList()
    }

    private fun stageForSharing(uris: List<Uri>): List<File> {
        val dir = File(cacheDir, "share").apply { mkdirs() }
        return uris.mapNotNull { uri ->
            val name = queryName(uri) ?: return@mapNotNull null
            val out = File(dir, name)
            runCatching {
                contentResolver.openInputStream(uri)?.use { input ->
                    out.outputStream().use { input.copyTo(it) }
                }
            }.getOrNull() ?: return@mapNotNull null
            out
        }
    }

    private fun queryName(uri: Uri): String? =
        contentResolver.query(uri, null, null, null, null)?.use { c ->
            val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && c.moveToFirst()) c.getString(idx) else null
        }

    private fun downloadDir(): File {
        val base = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
        else
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        return (base ?: cacheDir).apply { mkdirs() }
    }
}

/**
 * LocalSend's sonar: rings expanding outward on a loop with a stationary tile
 * at the centre. When [active] is false the rings hold still, so the screen is
 * not busy before anything is happening.
 */
@Composable
private fun Sonar(active: Boolean, centre: @Composable () -> Unit) {
    val ringCount = 3
    val transition = rememberInfiniteTransition(label = "sonar")
    val t by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(2600, easing = LinearEasing)),
        label = "sweep",
    )
    val accent = Mx.colors.accent
    val idle = Mx.colors.surface

    Box(Modifier.size(224.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val maxR = size.minDimension / 2f
            if (active) {
                repeat(ringCount) { i ->
                    // Stagger so one ring leaves as the next starts.
                    val phase = (t + i.toFloat() / ringCount) % 1f
                    drawCircle(
                        color = accent.copy(alpha = (1f - phase) * 0.45f),
                        radius = maxR * (0.32f + 0.68f * phase),
                        style = Stroke(width = 2.2f),
                    )
                }
                val angle = t * 2f * Math.PI.toFloat()
                val r = maxR * 0.88f
                drawCircle(
                    color = accent,
                    radius = 3.5f,
                    center = Offset(center.x + r * cos(angle), center.y + r * sin(angle)),
                )
            } else {
                repeat(ringCount) { i ->
                    drawCircle(
                        color = idle,
                        radius = maxR * (0.4f + 0.3f * i),
                        style = Stroke(width = 1.6f),
                    )
                }
            }
        }
        centre()
    }
}

private fun formatSize(bytes: Long): String = when {
    bytes >= 1L shl 30 -> "%.1f GB".format(bytes.toDouble() / (1L shl 30))
    bytes >= 1L shl 20 -> "%.0f MB".format(bytes.toDouble() / (1L shl 20))
    bytes > 0 -> "%.0f KB".format(bytes.toDouble() / (1L shl 10))
    else -> ""
}

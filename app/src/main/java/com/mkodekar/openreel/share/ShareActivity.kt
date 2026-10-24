// SPDX-License-Identifier: GPL-3.0-or-later
package com.mkodekar.openreel.share

import android.content.Intent
import android.net.Uri
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
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.mkodekar.openreel.ui.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.cos
import kotlin.math.sin

/**
 * Device-to-device share over the local network, with LocalSend's visual
 * language: a sonar sweep while the device is discoverable, the QR code at the
 * centre, and transfers shown as live rows.
 *
 * The transport is unchanged -- ShareService hosts a NanoHTTPD server and the
 * receiver pulls over plain HTTP. This is the UI for it.
 */
class ShareActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MxTheme {
                var tab by remember { mutableIntStateOf(0) }
                Column(
                    Modifier
                        .fillMaxSize()
                        .background(Mx.colors.background)
                        .windowInsetsPadding(WindowInsets.systemBars)
                ) {
                    MxToolbar(title = "Share", onBack = { finish() })
                    MxTabStrip(listOf("Send", "Receive"), tab) { tab = it }
                    if (tab == 0) SendTab() else ReceiveTab()
                }
            }
        }
    }

    // --- Send ----------------------------------------------------------------

    @Composable
    private fun SendTab() {
        val scope = rememberCoroutineScope()
        var staged by remember { mutableStateOf<List<File>>(emptyList()) }
        var serving by remember { mutableStateOf(false) }
        val url = remember(serving) { if (serving) NetworkUtils.shareUrl(ShareService.PORT) else null }

        val picker = rememberLauncherForActivityResult(
            ActivityResultContracts.OpenMultipleDocuments()
        ) { uris ->
            if (uris.isEmpty()) return@rememberLauncherForActivityResult
            scope.launch {
                val files = withContext(Dispatchers.IO) { stageForSharing(uris) }
                staged = files
                if (files.isNotEmpty()) {
                    startSharing(files)
                    serving = true
                }
            }
        }

        Column(
            Modifier.fillMaxSize().padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (!serving) {
                Spacer(Modifier.height(40.dp))
                Sonar(active = false, centre = { MxPlayGlyph(26.dp, Mx.colors.textSecondary) })
                Spacer(Modifier.height(32.dp))
                MxText(
                    "Pick the files you want to send.",
                    Mx.type.primary,
                    Mx.colors.textSecondary,
                )
                Spacer(Modifier.height(18.dp))
                MxButton("Choose files") { picker.launch(arrayOf("*/*")) }
            } else {
                Spacer(Modifier.height(24.dp))
                // Sonar sweeping while we are discoverable, QR in the middle.
                Sonar(active = true, centre = {
                    url?.let { QrTile(it) }
                })
                Spacer(Modifier.height(26.dp))
                MxText("Discoverable on this Wi-Fi", Mx.type.mediumPrimary)
                Spacer(Modifier.height(4.dp))
                MxText(
                    url ?: "No network address",
                    Mx.type.secondary,
                    Mx.colors.textSecondary,
                )
                Spacer(Modifier.height(20.dp))
                MxSectionLabel("Offering ${staged.size} file(s)")
                LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
                    items(staged, key = { it.absolutePath }) { f ->
                        OfferRow(f.name, f.length())
                    }
                }
                MxButton("Stop sharing", filled = false) {
                    stopService(Intent(this@ShareActivity, ShareService::class.java))
                    serving = false
                    staged = emptyList()
                }
            }
        }
    }

    @Composable
    private fun QrTile(url: String) {
        val bmp = remember(url) { runCatching { QrCodes.generate(url, 420) }.getOrNull() }
        Box(
            Modifier
                .size(118.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(androidx.compose.ui.graphics.Color.White)
                .padding(7.dp),
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
                Modifier
                    .size(34.dp)
                    .clip(RoundedCornerShape(6.dp))
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

    // --- Receive -------------------------------------------------------------

    @Composable
    private fun ReceiveTab() {
        val scope = rememberCoroutineScope()
        var baseUrl by remember { mutableStateOf<String?>(null) }
        var manual by remember { mutableStateOf("") }
        var offered by remember { mutableStateOf<List<ShareClient.Offered>>(emptyList()) }
        var status by remember { mutableStateOf<String?>(null) }
        val progress = remember { mutableStateMapOf<Int, Float>() }

        val scanner = rememberLauncherForActivityResult(
            com.journeyapps.barcodescanner.ScanContract()
        ) { result ->
            val url = result.contents ?: return@rememberLauncherForActivityResult
            baseUrl = url
            scope.launch {
                status = "Reading what's on offer…"
                offered = withContext(Dispatchers.IO) {
                    runCatching { ShareClient.fetchManifest(url) }.getOrDefault(emptyList())
                }
                status = if (offered.isEmpty()) "Nothing offered, or host unreachable." else null
            }
        }

        fun connect(url: String) {
            baseUrl = url
            scope.launch {
                status = "Reading what's on offer…"
                offered = withContext(Dispatchers.IO) {
                    runCatching { ShareClient.fetchManifest(url) }.getOrDefault(emptyList())
                }
                status = if (offered.isEmpty()) "Nothing offered, or host unreachable." else null
            }
        }

        Column(Modifier.fillMaxSize().padding(20.dp)) {
            if (offered.isEmpty()) {
                Spacer(Modifier.height(28.dp))
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    // Looking for a sender: sweep without a QR in the middle.
                    Sonar(active = true, centre = {
                        MxText("scan", Mx.type.tag, Mx.colors.textSecondary)
                    })
                }
                Spacer(Modifier.height(30.dp))
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    MxButton("Scan QR code") {
                        scanner.launch(
                            com.journeyapps.barcodescanner.ScanOptions()
                                .setDesiredBarcodeFormats(
                                    com.journeyapps.barcodescanner.ScanOptions.QR_CODE
                                )
                        )
                    }
                }
                Spacer(Modifier.height(24.dp))
                MxSectionLabel("Or type the address")
                MxField(manual, { manual = it }, "http://192.168.0.5:52525/")
                Spacer(Modifier.height(12.dp))
                MxButton("Connect", enabled = manual.startsWith("http")) { connect(manual) }
            } else {
                MxSectionLabel("Available from ${baseUrl.orEmpty()}")
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
                                        val url = baseUrl ?: return@MxButton
                                        scope.launch {
                                            progress[item.index] = 0f
                                            withContext(Dispatchers.IO) {
                                                runCatching {
                                                    ShareClient.download(
                                                        url, item, downloadDir()
                                                    ) { f -> progress[item.index] = f }
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
                Spacer(Modifier.height(12.dp))
                MxText(it, Mx.type.secondary, Mx.colors.textSecondary)
            }
        }
    }

    // --- plumbing ------------------------------------------------------------

    private fun startSharing(files: List<File>) {
        val intent = Intent(this, ShareService::class.java).putStringArrayListExtra(
            ShareService.EXTRA_FILES, ArrayList(files.map { it.absolutePath })
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent)
        else startService(intent)
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
 * LocalSend's sonar: concentric rings expanding outward on a loop, with a
 * stationary tile at the centre. When [active] is false the rings hold still
 * so the screen does not look busy before anything is happening.
 */
@Composable
private fun Sonar(
    active: Boolean,
    centre: @Composable () -> Unit,
) {
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

    Box(Modifier.size(230.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val maxR = size.minDimension / 2f
            if (active) {
                repeat(ringCount) { i ->
                    // Stagger the rings so one leaves as the next starts.
                    val phase = (t + i.toFloat() / ringCount) % 1f
                    val radius = maxR * (0.32f + 0.68f * phase)
                    drawCircle(
                        color = accent.copy(alpha = (1f - phase) * 0.45f),
                        radius = radius,
                        style = Stroke(width = 2.2f),
                    )
                }
                // A faint sweeping dot riding the outer ring.
                val angle = t * 2f * Math.PI.toFloat()
                val r = maxR * 0.88f
                drawCircle(
                    color = accent,
                    radius = 3.5f,
                    center = Offset(
                        center.x + r * cos(angle),
                        center.y + r * sin(angle),
                    ),
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

package com.mkodekar.openreel.share

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Module 4 UI. Two roles, no cloud, no account:
 *   SEND    -> pick files, start the local server, show a QR of its URL
 *   RECEIVE -> scan that QR, list what's offered, pull files over local Wi-Fi
 */
class ShareActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { ShareScreen() } }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun ShareScreen() {
        var tab by remember { mutableIntStateOf(0) }
        Scaffold(topBar = { TopAppBar(title = { Text("Share") }) }) { pad ->
            Column(Modifier.padding(pad).fillMaxSize()) {
                TabRow(tab) {
                    Tab(tab == 0, { tab = 0 }, text = { Text("Send") })
                    Tab(tab == 1, { tab = 1 }, text = { Text("Receive") })
                }
                if (tab == 0) SendTab() else ReceiveTab()
            }
        }
    }

    // --- SEND ---------------------------------------------------------------
    @Composable
    private fun SendTab() {
        val scope = rememberCoroutineScope()
        var shareUrl by remember { mutableStateOf<String?>(null) }

        val picker = rememberLauncherForActivityResult(
            ActivityResultContracts.OpenMultipleDocuments()
        ) { uris ->
            if (uris.isEmpty()) return@rememberLauncherForActivityResult
            scope.launch {
                val files = withContext(Dispatchers.IO) { stageForSharing(uris) }
                startService(
                    Intent(this@ShareActivity, ShareService::class.java)
                        .putStringArrayListExtra(
                            ShareService.EXTRA_FILES, ArrayList(files.map { it.absolutePath })
                        )
                )
                shareUrl = NetworkUtils.shareUrl(ShareService.PORT)
            }
        }

        Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Button(onClick = { picker.launch(arrayOf("*/*")) }) { Text("Pick files to send") }
            Spacer(Modifier.height(24.dp))
            shareUrl?.let { url ->
                Text("Scan this on the other device:")
                Spacer(Modifier.height(12.dp))
                Image(QrCodes.generate(url).asImageBitmap(), contentDescription = "Share QR")
                Spacer(Modifier.height(8.dp))
                Text(url)
            } ?: Text("Pick files, then a QR code appears here.")
        }
    }

    /** Copy picked content:// items into cache so NanoHTTPD can serve real Files. */
    private fun stageForSharing(uris: List<Uri>): List<File> {
        val dir = File(cacheDir, "share").apply { mkdirs() }
        return uris.mapNotNull { uri ->
            val name = queryName(uri) ?: return@mapNotNull null
            val out = File(dir, name)
            contentResolver.openInputStream(uri)?.use { input ->
                out.outputStream.use { input.copyTo(it) }
            }
            out
        }
    }

    private fun queryName(uri: Uri): String? =
        contentResolver.query(uri, null, null, null, null)?.use { c ->
            val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && c.moveToFirst()) c.getString(idx) else null
        }

    // --- RECEIVE ------------------------------------------------------------
    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun ReceiveTab() {
        val scope = rememberCoroutineScope()
        var items by remember { mutableStateOf<List<ShareClient.Offered>>(emptyList()) }
        var baseUrl by remember { mutableStateOf<String?>(null) }
        var status by remember { mutableStateOf("") }

        val scanner = rememberLauncherForActivityResult(ScanContract()) { result ->
            val url = result.contents ?: return@rememberLauncherForActivityResult
            baseUrl = url
            scope.launch {
                status = "Connecting…"
                items = withContext(Dispatchers.IO) {
                    runCatching { ShareClient.fetchManifest(url) }.getOrElse {
                        status = "Could not connect: ${it.message}"; emptyList()
                    }
                }
                if (items.isNotEmpty()) status = ""
            }
        }

        Column(Modifier.fillMaxSize().padding(16.dp)) {
            Button(onClick = {
                scanner.launch(ScanOptions().setOrientationLocked(false).setPrompt("Scan the sender's QR"))
            }) { Text("Scan QR to receive") }

            if (status.isNotEmpty()) { Spacer(Modifier.height(12.dp)); Text(status) }

            LazyColumn(Modifier.fillMaxWidth()) {
                items(items) { item ->
                    ListItem(
                        headlineContent = { Text(item.name) },
                        supportingContent = { Text("${item.size / 1024} KB") },
                        trailingContent = {
                            TextButton(onClick = {
                                val url = baseUrl ?: return@TextButton
                                scope.launch {
                                    status = "Downloading ${item.name}…"
                                    withContext(Dispatchers.IO) {
                                        runCatching {
                                            ShareClient.download(url, item, downloadDir())
                                        }
                                    }
                                    status = "Saved ${item.name}"
                                }
                            }) { Text("Get") }
                        },
                    )
                    HorizontalDivider()
                }
            }
        }
    }

    private fun downloadDir(): File {
        val base = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            getExternalFilesDir("received") else File(cacheDir, "received")
        return (base ?: File(cacheDir, "received")).apply { mkdirs() }
    }
}

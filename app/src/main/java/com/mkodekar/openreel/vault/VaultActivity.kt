// SPDX-License-Identifier: GPL-3.0-or-later
package com.mkodekar.openreel.vault

import android.app.Activity
import android.content.Intent
import android.content.pm.ActivityInfo
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.mkodekar.openreel.player.PlayerActivity
import com.mkodekar.openreel.ui.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Private Folder, MX's equivalent of a vault.
 *
 * Flow: set a PIN and a recovery question the first time, then unlock to see
 * what is hidden. Adding a video copies it in and then asks the system for
 * permission to delete the original, which on scoped storage is the only way
 * to remove a MediaStore row we do not own.
 *
 * See Vault for what this protects against and what it does not; the screen
 * says so too, because a locked-looking folder invites people to assume
 * encryption that is not there.
 */
class VaultActivity : ComponentActivity() {

    private lateinit var vault: Vault

    /** Files copied in, awaiting the user's consent to delete the originals. */
    private var pendingOriginals: List<Uri> = emptyList()
    private var onDeleteSettled: (() -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        window.navigationBarColor = android.graphics.Color.TRANSPARENT

        // Keep the contents out of the recents screenshot and screen capture.
        window.setFlags(
            android.view.WindowManager.LayoutParams.FLAG_SECURE,
            android.view.WindowManager.LayoutParams.FLAG_SECURE,
        )

        vault = Vault(this)
        val themePref = com.mkodekar.openreel.settings.Settings(this).theme

        setContent {
            MxTheme(dark = rememberThemeIsDark(themePref)) {
                var unlocked by remember { mutableStateOf(false) }
                Column(
                    Modifier
                        .fillMaxSize()
                        .background(Mx.colors.background)
                        .windowInsetsPadding(WindowInsets.navigationBars)
                ) {
                    MxStatusBarScrim()
                    MxToolbar(title = "Private Folder", onBack = { finish() })
                    when {
                        !vault.isSetUp -> SetUpScreen { unlocked = true }
                        !unlocked -> UnlockScreen { unlocked = true }
                        else -> ContentsScreen()
                    }
                }
            }
        }
    }

    // --- first run -----------------------------------------------------------

    @Composable
    private fun SetUpScreen(onDone: () -> Unit) {
        var pin by remember { mutableStateOf("") }
        var confirm by remember { mutableStateOf("") }
        var question by remember { mutableStateOf("") }
        var answer by remember { mutableStateOf("") }
        var error by remember { mutableStateOf<String?>(null) }

        Column(
            Modifier
                .fillMaxSize()
                .padding(20.dp)
                .verticalScroll(rememberScrollState())
        ) {
            MxText("Set a PIN", Mx.type.toolbar)
            Spacer(Modifier.height(6.dp))
            MxText(
                "Hidden videos are moved out of your gallery into this app. " +
                    "The PIN keeps them out of sight -- it does not encrypt them, " +
                    "so anyone with USB debugging or root access could still read them.",
                Mx.type.secondary,
                Mx.colors.textSecondary,
            )

            MxSectionLabel("PIN")
            PinField(pin) { pin = it }
            MxSectionLabel("Confirm PIN")
            PinField(confirm) { confirm = it }

            MxSectionLabel("Recovery question")
            MxField(question, { question = it }, "e.g. First school you attended")
            Spacer(Modifier.height(10.dp))
            MxField(answer, { answer = it }, "Answer")
            Spacer(Modifier.height(6.dp))
            MxText(
                "This is the only way back in if you forget the PIN. Nothing is " +
                    "sent anywhere -- it stays on this device, so a forgotten " +
                    "answer cannot be recovered for you.",
                Mx.type.secondary,
                Mx.colors.textSecondary,
            )

            Spacer(Modifier.height(18.dp))
            MxButton(
                "Create",
                enabled = pin.length >= 4 && confirm.isNotEmpty() &&
                    question.isNotBlank() && answer.isNotBlank(),
            ) {
                error = when {
                    pin != confirm -> "PINs do not match."
                    else -> null
                }
                if (error == null) {
                    vault.setUp(pin, question.trim(), answer)
                    onDone()
                }
            }
            error?.let {
                Spacer(Modifier.height(10.dp))
                MxText(it, Mx.type.secondary, Mx.colors.badgeNew)
            }
        }
    }

    // --- unlock --------------------------------------------------------------

    @Composable
    private fun UnlockScreen(onUnlocked: () -> Unit) {
        var pin by remember { mutableStateOf("") }
        var error by remember { mutableStateOf<String?>(null) }
        var recovering by remember { mutableStateOf(false) }
        var answer by remember { mutableStateOf("") }
        var newPin by remember { mutableStateOf("") }

        Column(
            Modifier.fillMaxSize().padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(40.dp))
            LockGlyph()
            Spacer(Modifier.height(20.dp))

            if (!recovering) {
                MxText("Enter your PIN", Mx.type.toolbar)
                Spacer(Modifier.height(18.dp))
                PinField(pin) { pin = it; error = null }
                Spacer(Modifier.height(16.dp))
                MxButton("Unlock", enabled = pin.isNotEmpty()) {
                    if (vault.verify(pin)) onUnlocked() else error = "Incorrect PIN."
                }
                Spacer(Modifier.height(12.dp))
                MxButton("Forgot PIN", filled = false) { recovering = true; error = null }
            } else {
                MxText(vault.securityQuestion.orEmpty(), Mx.type.mediumPrimary)
                Spacer(Modifier.height(14.dp))
                MxField(answer, { answer = it }, "Your answer")
                Spacer(Modifier.height(12.dp))
                MxText("New PIN", Mx.type.secondary, Mx.colors.textSecondary)
                Spacer(Modifier.height(6.dp))
                PinField(newPin) { newPin = it }
                Spacer(Modifier.height(16.dp))
                MxButton("Reset PIN", enabled = answer.isNotBlank() && newPin.length >= 4) {
                    if (vault.verifyAnswer(answer)) {
                        vault.resetPin(newPin)
                        onUnlocked()
                    } else {
                        error = "That answer does not match."
                    }
                }
                Spacer(Modifier.height(10.dp))
                MxButton("Back", filled = false) { recovering = false; error = null }
            }

            error?.let {
                Spacer(Modifier.height(14.dp))
                MxText(it, Mx.type.secondary, Mx.colors.badgeNew)
            }
        }
    }

    // --- contents ------------------------------------------------------------

    @Composable
    private fun ContentsScreen() {
        val scope = rememberCoroutineScope()
        var entries by remember { mutableStateOf(vault.list()) }
        var busy by remember { mutableStateOf<String?>(null) }

        // The system asks the user before we may delete the originals.
        val deleteRequest = rememberLauncherForActivityResult(
            ActivityResultContracts.StartIntentSenderForResult()
        ) { _ ->
            onDeleteSettled?.invoke()
            onDeleteSettled = null
            entries = vault.list()
            busy = null
        }

        val picker = rememberLauncherForActivityResult(
            ActivityResultContracts.OpenMultipleDocuments()
        ) { uris ->
            if (uris.isEmpty()) return@rememberLauncherForActivityResult
            scope.launch {
                busy = "Moving ${uris.size} file(s) in…"
                val originals = withContext(Dispatchers.IO) { importAll(uris) }
                entries = vault.list()
                if (originals.isEmpty()) { busy = null; return@launch }
                // Ask once for all of them.
                val sender = requestDeletion(originals)
                if (sender != null) {
                    onDeleteSettled = { }
                    deleteRequest.launch(
                        androidx.activity.result.IntentSenderRequest.Builder(sender).build()
                    )
                } else {
                    busy = null
                }
            }
        }

        Column(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    MxText(
                        if (entries.isEmpty()) "Nothing hidden yet"
                        else "${entries.size} item(s) hidden",
                        Mx.type.mediumPrimary,
                    )
                    MxText(
                        "Hidden from the gallery · not encrypted",
                        Mx.type.secondary,
                        Mx.colors.textSecondary,
                    )
                }
                MxButton("Add") { picker.launch(arrayOf("video/*")) }
            }
            Spacer(Modifier.height(8.dp))
            MxDivider()

            busy?.let {
                Spacer(Modifier.height(10.dp))
                MxText(it, Mx.type.secondary, Mx.colors.textSecondary)
            }

            if (entries.isEmpty()) {
                Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                    MxText(
                        "Add videos to move them out of your gallery.",
                        Mx.type.primary,
                        Mx.colors.textSecondary,
                    )
                }
            } else {
                LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
                    items(entries, key = { it.file.absolutePath }) { e ->
                        VaultRow(
                            entry = e,
                            onPlay = {
                                startActivity(
                                    Intent(this@VaultActivity, PlayerActivity::class.java)
                                        .setData(Uri.fromFile(e.file))
                                )
                            },
                            onRestore = {
                                scope.launch {
                                    busy = "Restoring…"
                                    withContext(Dispatchers.IO) { vault.restore(e) }
                                    entries = vault.list()
                                    busy = null
                                }
                            },
                            onDelete = {
                                vault.delete(e)
                                entries = vault.list()
                            },
                        )
                    }
                }
            }
        }
    }

    @Composable
    private fun VaultRow(
        entry: Vault.Entry,
        onPlay: () -> Unit,
        onRestore: () -> Unit,
        onDelete: () -> Unit,
    ) {
        Column(Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
            MxText(entry.displayName, Mx.type.mediumPrimary, maxLines = 2)
            Spacer(Modifier.height(3.dp))
            MxText(size(entry.sizeBytes), Mx.type.secondary, Mx.colors.textSecondary)
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MxButton("Play", filled = false, onClick = onPlay)
                MxButton("Restore", filled = false, onClick = onRestore)
                Spacer(Modifier.weight(1f))
                MxButton("Delete", filled = false, onClick = onDelete)
            }
        }
        MxDivider()
    }

    // --- plumbing ------------------------------------------------------------

    /** Copies each picked video in; returns the originals that should now go. */
    private fun importAll(uris: List<Uri>): List<Uri> {
        val originals = mutableListOf<Uri>()
        uris.forEach { uri ->
            val name = queryName(uri) ?: "video"
            if (vault.import(uri, name) != null) originals += uri
        }
        return originals
    }

    /**
     * On API 30+ the system owns MediaStore rows we did not create, so the
     * only way to remove the originals is to ask the user with
     * createDeleteRequest. Below that we can delete directly.
     */
    private fun requestDeletion(uris: List<Uri>): android.content.IntentSender? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return runCatching {
                MediaStore.createDeleteRequest(contentResolver, uris).intentSender
            }.getOrNull()
        }
        uris.forEach { runCatching { contentResolver.delete(it, null, null) } }
        return null
    }

    private fun queryName(uri: Uri): String? =
        contentResolver.query(uri, null, null, null, null)?.use { c ->
            val i = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (i >= 0 && c.moveToFirst()) c.getString(i) else null
        }

    private fun size(b: Long) = when {
        b >= 1L shl 30 -> "%.1f GB".format(b.toDouble() / (1L shl 30))
        b >= 1L shl 20 -> "%.0f MB".format(b.toDouble() / (1L shl 20))
        b > 0 -> "%.0f KB".format(b.toDouble() / (1L shl 10))
        else -> ""
    }
}

@Composable
private fun PinField(value: String, onChange: (String) -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(Mx.colors.surface)
            .padding(horizontal = 12.dp, vertical = 12.dp),
    ) {
        if (value.isEmpty()) {
            MxText("PIN", Mx.type.primary, Mx.colors.textSecondary)
        }
        androidx.compose.foundation.text.BasicTextField(
            value = value,
            onValueChange = { if (it.length <= 12 && it.all(Char::isDigit)) onChange(it) },
            singleLine = true,
            textStyle = Mx.type.primary.copy(color = Mx.colors.textPrimary),
            cursorBrush = androidx.compose.ui.graphics.SolidColor(Mx.colors.accent),
            visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun LockGlyph() {
    val c = Mx.colors.accent
    androidx.compose.foundation.Canvas(Modifier.size(52.dp)) {
        val w = size.width
        val h = size.height
        drawArc(
            color = c,
            startAngle = 180f, sweepAngle = 180f, useCenter = false,
            topLeft = androidx.compose.ui.geometry.Offset(w * 0.28f, h * 0.10f),
            size = androidx.compose.ui.geometry.Size(w * 0.44f, h * 0.42f),
            style = androidx.compose.ui.graphics.drawscope.Stroke(width = w * 0.08f),
        )
        drawRoundRect(
            color = c,
            topLeft = androidx.compose.ui.geometry.Offset(w * 0.20f, h * 0.42f),
            size = androidx.compose.ui.geometry.Size(w * 0.60f, h * 0.46f),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(w * 0.08f, w * 0.08f),
        )
    }
}

// SPDX-License-Identifier: GPL-3.0-or-later
package com.mkodekar.openreel.vault

import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import java.io.File
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * Private Folder: a PIN-gated store for videos you would rather not have
 * appear in the gallery.
 *
 * WHAT THIS DOES AND DOES NOT DO -- stated plainly, because a feature called a
 * "vault" invites people to assume more than it provides:
 *
 *   It moves files into app-private storage (Context.filesDir), which other
 *   apps and the gallery cannot enumerate, and removes the MediaStore entry so
 *   they stop appearing in pickers and photo apps. The PIN gates the UI.
 *
 *   It does NOT encrypt the files. Anyone with adb, root, or a device backup
 *   can read them. libmpv plays from a real file path, so encrypting would
 *   mean decrypting a whole video to a temp file before playback -- slow for
 *   large files, and it would leave plaintext on disk anyway. This is
 *   concealment, not protection against a determined examiner.
 *
 * The PIN itself is never stored. Only a PBKDF2 hash and its salt are kept, so
 * reading the preferences does not reveal it.
 *
 * MX's equivalent offers PIN recovery by email through its own servers. This
 * deliberately does not: a local security question is the whole recovery
 * story, and nothing leaves the device.
 */
class Vault(private val context: Context) {

    private val prefs = context.getSharedPreferences("vault", Context.MODE_PRIVATE)

    /** Where hidden files live. Not visible to other apps or the gallery. */
    private val dir: File
        get() = File(context.filesDir, "vault").apply { mkdirs() }

    val isSetUp: Boolean get() = prefs.contains(KEY_HASH)

    /** The recovery question, shown when someone forgets the PIN. */
    val securityQuestion: String? get() = prefs.getString(KEY_QUESTION, null)

    // --- PIN -----------------------------------------------------------------

    fun setUp(pin: String, question: String, answer: String) {
        val salt = randomSalt()
        val answerSalt = randomSalt()
        prefs.edit()
            .putString(KEY_SALT, salt.toHex())
            .putString(KEY_HASH, hash(pin, salt).toHex())
            .putString(KEY_QUESTION, question)
            .putString(KEY_ANSWER_SALT, answerSalt.toHex())
            // Normalised so capitalisation and stray spaces do not lock you out.
            .putString(KEY_ANSWER_HASH, hash(answer.normalise(), answerSalt).toHex())
            .apply()
    }

    fun verify(pin: String): Boolean {
        val salt = prefs.getString(KEY_SALT, null)?.fromHex() ?: return false
        val expected = prefs.getString(KEY_HASH, null) ?: return false
        return constantTimeEquals(hash(pin, salt).toHex(), expected)
    }

    fun verifyAnswer(answer: String): Boolean {
        val salt = prefs.getString(KEY_ANSWER_SALT, null)?.fromHex() ?: return false
        val expected = prefs.getString(KEY_ANSWER_HASH, null) ?: return false
        return constantTimeEquals(hash(answer.normalise(), salt).toHex(), expected)
    }

    /** Only reachable after the security answer has been verified. */
    fun resetPin(newPin: String) {
        val salt = randomSalt()
        prefs.edit()
            .putString(KEY_SALT, salt.toHex())
            .putString(KEY_HASH, hash(newPin, salt).toHex())
            .apply()
    }

    // --- contents ------------------------------------------------------------

    data class Entry(val file: File, val displayName: String, val sizeBytes: Long)

    fun list(): List<Entry> =
        dir.listFiles().orEmpty()
            .filter { it.isFile && it.name != NAMES_FILE }
            .map { Entry(it, displayName(it.name), it.length()) }
            .sortedBy { it.displayName.lowercase() }

    /**
     * Copies [uri] into the vault. Returns the stored file, or null on failure.
     *
     * The caller is responsible for removing the original afterwards: on
     * scoped storage deleting someone else's MediaStore row needs the user's
     * consent through createDeleteRequest, which only an Activity can ask for.
     */
    fun import(uri: Uri, displayName: String): File? = runCatching {
        // Prefix with a timestamp so two files of the same name can coexist.
        val stored = File(dir, "${System.currentTimeMillis()}_${displayName.sanitise()}")
        context.contentResolver.openInputStream(uri)?.use { input ->
            stored.outputStream().use { input.copyTo(it) }
        } ?: return null
        stored
    }.getOrNull()

    /**
     * Moves a file back out to the public Movies folder and re-registers it
     * with MediaStore so it shows up in the gallery again.
     */
    fun restore(entry: Entry): Boolean = runCatching {
        val values = android.content.ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, entry.displayName)
            put(MediaStore.Video.Media.MIME_TYPE, mimeOf(entry.displayName))
            put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/OpenReel")
        }
        val target = context.contentResolver.insert(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values
        ) ?: return false
        context.contentResolver.openOutputStream(target)?.use { out ->
            entry.file.inputStream().use { it.copyTo(out) }
        } ?: return false
        entry.file.delete()
        true
    }.getOrDefault(false)

    fun delete(entry: Entry): Boolean = runCatching { entry.file.delete() }.getOrDefault(false)

    /** Strips the timestamp prefix added on import. */
    private fun displayName(stored: String): String =
        stored.substringAfter('_', stored)

    // --- helpers -------------------------------------------------------------

    private fun randomSalt(): ByteArray = ByteArray(16).also { SecureRandom().nextBytes(it) }

    /** PBKDF2: a plain digest of a four-digit PIN would be trivial to reverse. */
    private fun hash(secret: String, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(secret.toCharArray(), salt, 120_000, 256)
        return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
    }

    private fun constantTimeEquals(a: String, b: String): Boolean {
        if (a.length != b.length) return false
        var diff = 0
        for (i in a.indices) diff = diff or (a[i].code xor b[i].code)
        return diff == 0
    }

    private fun String.normalise() = trim().lowercase()
    private fun String.sanitise() = replace(Regex("""[^A-Za-z0-9._\- ]"""), "_").take(120)
    private fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }
    private fun String.fromHex() =
        chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    private fun mimeOf(name: String) = when (name.substringAfterLast('.', "").lowercase()) {
        "mp4", "m4v" -> "video/mp4"
        "mkv" -> "video/x-matroska"
        "webm" -> "video/webm"
        "avi" -> "video/x-msvideo"
        "3gp" -> "video/3gpp"
        else -> "video/*"
    }

    private companion object {
        const val KEY_HASH = "pin_hash"
        const val KEY_SALT = "pin_salt"
        const val KEY_QUESTION = "question"
        const val KEY_ANSWER_HASH = "answer_hash"
        const val KEY_ANSWER_SALT = "answer_salt"
        const val NAMES_FILE = ".names"
    }
}

/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.rustclipsync

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import android.util.Base64
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal data class StoredSession(
    val url: String,
    val token: String,
    val paired: Boolean,
    val enabled: Boolean,
    val state: SyncState,
)

/** The outbox and device token are encrypted by a non-exportable Android Keystore key. */
internal class SyncStore(context: Context) {
    private val file = AtomicFile(File(context.noBackupFilesDir, "rustclipsync-v1"))

    fun load(): StoredSession? = synchronized(lock) { readLocked() }

    fun save(session: StoredSession) = synchronized(lock) { writeLocked(session) }

    fun update(transform: (StoredSession) -> StoredSession): StoredSession = synchronized(lock) {
        val next = transform(requireNotNull(readLocked()))
        writeLocked(next)
        next
    }

    fun clear() = synchronized(lock) {
        file.delete()
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        store.deleteEntry(KEY_ALIAS)
    }

    private fun key(create: Boolean): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val existing = store.getKey(KEY_ALIAS, null) as? SecretKey
        if (existing != null) return existing
        check(create) { "sync key unavailable" }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).build())
            generateKey()
        }
    }

    private fun readLocked(): StoredSession? {
        if (!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists()) return null
        // openRead() restores AtomicFile's legacy backup before reporting its size.
        val bytes = file.openRead().use {
            check(file.baseFile.length() <= MAX_STATE_BYTES) { "sync state too large" }
            it.readBytes()
        }
        check(bytes.size in (HEADER.size + IV_SIZE + 16)..MAX_STATE_BYTES)
        check(bytes.copyOfRange(0, HEADER.size).contentEquals(HEADER))
        val iv = bytes.copyOfRange(HEADER.size, HEADER.size + IV_SIZE)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(false), GCMParameterSpec(128, iv))
        cipher.updateAAD(HEADER)
        val clear = cipher.doFinal(bytes, HEADER.size + IV_SIZE, bytes.size - HEADER.size - IV_SIZE)
        return decode(JSONObject(clear.toString(Charsets.UTF_8)))
    }

    private fun writeLocked(session: StoredSession) {
        val clear = encode(session).toString().toByteArray(Charsets.UTF_8)
        require(clear.size <= MAX_STATE_BYTES - HEADER.size - IV_SIZE - 16)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key(!file.baseFile.exists()))
        check(cipher.iv.size == IV_SIZE)
        cipher.updateAAD(HEADER)
        val encrypted = cipher.doFinal(clear)
        val output = file.startWrite()
        try {
            output.write(HEADER)
            output.write(cipher.iv)
            output.write(encrypted)
            file.finishWrite(output)
        } catch (t: Throwable) {
            file.failWrite(output)
            throw t
        }
    }

    private fun encode(s: StoredSession): JSONObject = JSONObject().apply {
        put("version", 1)
        put("url", s.url)
        put("token", s.token)
        put("paired", s.paired)
        put("enabled", s.enabled)
        put("epoch", s.state.epoch)
        put("deviceId", s.state.deviceId)
        put("cursor", s.state.cursor)
        put("pending", s.state.pending?.let { p -> JSONObject().apply {
            put("copy", encodeCopy(p.copy)); put("base", p.baseRevision); put("attempted", p.attempted)
        } } ?: JSONObject.NULL)
        put("queued", s.state.queued?.let(::encodeCopy) ?: JSONObject.NULL)
        put("discardPending", s.state.discardPending)
        put("applying", s.state.applying?.let { m -> JSONObject().apply {
            put("id", m.id); put("revision", m.revision); put("source", m.sourceDeviceId); put("sensitive", m.sensitive)
        } } ?: JSONObject.NULL)
        put("ack", s.state.ack?.let { a -> JSONObject().apply {
            put("id", a.id); put("revision", a.revision); put("result", a.result)
        } } ?: JSONObject.NULL)
    }

    private fun encodeCopy(c: LocalCopy) = JSONObject().apply {
        put("id", c.id)
        put("text", Base64.encodeToString(c.text.toByteArray(Charsets.UTF_8), Base64.NO_WRAP))
        put("createdAt", c.createdAtMs)
        put("sensitive", c.sensitive)
    }

    private fun decodeCopy(j: JSONObject): LocalCopy {
        val bytes = Base64.decode(j.getString("text"), Base64.NO_WRAP)
        require(bytes.size <= MAX_TEXT_BYTES)
        return LocalCopy(j.getString("id"), bytes.toString(Charsets.UTF_8), j.getLong("createdAt"), j.optBoolean("sensitive", false))
    }

    private fun decode(j: JSONObject): StoredSession {
        require(j.getInt("version") == 1)
        val pending = j.optJSONObject("pending")?.let { Outbox(
            decodeCopy(it.getJSONObject("copy")), it.getLong("base"), it.getBoolean("attempted")) }
        val applying = j.optJSONObject("applying")?.let { RemoteMessage(
            it.getString("id"), it.getLong("revision"), it.getString("source"), it.optBoolean("sensitive", false)) }
        val ack = j.optJSONObject("ack")?.let { Ack(it.getString("id"), it.getLong("revision"), it.getString("result")) }
        return StoredSession(j.getString("url"), j.getString("token"), j.getBoolean("paired"), j.getBoolean("enabled"),
            SyncState(j.getString("epoch"), j.getString("deviceId"), j.getLong("cursor"),
                pending, j.optJSONObject("queued")?.let(::decodeCopy),
                j.optBoolean("discardPending", false), applying, ack))
    }

    companion object {
        const val MAX_TEXT_BYTES = 10 * 1024 * 1024
        private const val MAX_STATE_BYTES = 32 * 1024 * 1024
        private const val KEY_ALIAS = "io.github.infinifar.rustclipsync.v1"
        private const val IV_SIZE = 12
        private val HEADER = byteArrayOf(0x52, 0x43, 0x53, 0x31)
        private val lock = Any()
    }
}

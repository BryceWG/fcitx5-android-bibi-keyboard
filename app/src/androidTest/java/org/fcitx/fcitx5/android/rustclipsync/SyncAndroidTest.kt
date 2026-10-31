/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.rustclipsync

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.PersistableBundle
import android.util.Base64
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.util.UUID
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory

/** Runs only against a disposable emulator and a pinned, synthetic local fixture. */
class SyncAndroidTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val args get() = InstrumentationRegistry.getArguments()

    private fun fixtureContext(): Context {
        check(args.getString("rcs_fixture") == "1") { "disposable fixture flag required" }
        return instrumentation.targetContext
    }

    private fun pinnedFactory(): javax.net.ssl.SSLSocketFactory {
        val cert = Base64.decode(requireNotNull(args.getString("rcs_cert")), Base64.DEFAULT)
        val certificate = CertificateFactory.getInstance("X.509").generateCertificate(ByteArrayInputStream(cert))
        val keys = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null); setCertificateEntry("fixture", certificate) }
        val trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(keys) }
        return SSLContext.getInstance("TLS").apply { init(null, trust.trustManagers, null) }.socketFactory
    }

    private fun waitUntil(message: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + 15_000_000_000L
        while (System.nanoTime() < deadline) {
            if (condition()) return
            Thread.sleep(100)
        }
        throw AssertionError(message)
    }

    @Test fun androidKeystoreEncryptsAndRestoresPendingData() {
        val context = fixtureContext()
        val store = SyncStore(context)
        store.clear()
        try {
            val copy = LocalCopy(UUID.randomUUID().toString(), "fixture-private-text", 123, true)
            val expected = StoredSession("https://localhost:1234", "ab".repeat(32), true, true,
                SyncState(UUID.randomUUID().toString(), UUID.randomUUID().toString(), 1).localCopy(copy))
            store.save(expected)
            val bytes = java.io.File(context.noBackupFilesDir, "rustclipsync-v1").readBytes()
            assertFalse(bytes.toString(Charsets.ISO_8859_1).contains("fixture-private-text"))
            assertFalse(bytes.toString(Charsets.ISO_8859_1).contains("ab".repeat(32)))
            assertEquals(expected, SyncStore(context).load())
            bytes[bytes.lastIndex - 1] = (bytes[bytes.lastIndex - 1].toInt() xor 1).toByte()
            java.io.File(context.noBackupFilesDir, "rustclipsync-v1").writeBytes(bytes)
            var rejected = false
            try { store.load() } catch (_: Exception) { rejected = true }
            assertTrue("tampered encrypted state must be rejected", rejected)
        } finally { store.clear() }
    }

    @Test fun markedClipboardRemainsMarked() {
        val context = fixtureContext()
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        instrumentation.runOnMainSync {
            val clip = ClipData.newPlainText("fixture", "fixture-sensitive")
            clip.description.extras = PersistableBundle().apply { putBoolean("android.content.extra.IS_SENSITIVE", true) }
            clipboard.setPrimaryClip(clip)
            assertEquals("fixture-sensitive", clipboard.primaryClip?.getItemAt(0)?.text?.toString())
            assertTrue(clipboard.primaryClip?.description?.extras?.getBoolean("android.content.extra.IS_SENSITIVE") == true)
            clipboard.clearPrimaryClip()
        }
    }

    @Test fun httpsTransportMatchesLiveV1Server() {
        val context = fixtureContext()
        val url = requireNotNull(args.getString("rcs_url"))
        check(url.startsWith("https://localhost:"))
        val original = HttpsURLConnection.getDefaultSSLSocketFactory()
        HttpsURLConnection.setDefaultSSLSocketFactory(pinnedFactory())
        val store = SyncStore(context)
        store.clear()
        try {
            val transport = SyncTransport(url)
            val unpaired = StoredSession(url, "9c".repeat(32), false, true,
                SyncState("", UUID.randomUUID().toString(), 0))
            store.save(unpaired)
            val (epoch, baseline) = transport.register(unpaired.state.deviceId, unpaired.token,
                requireNotNull(args.getString("rcs_user")), requireNotNull(args.getString("rcs_password")))
            var session = unpaired.copy(paired = true, state = unpaired.state.copy(epoch = epoch, cursor = baseline))
            val first = LocalCopy(UUID.randomUUID().toString(), "android-fixture", System.currentTimeMillis())
            session = session.copy(state = session.state.localCopy(first).markAttempted())
            store.save(session)
            val sent = requireNotNull(session.state.pending)
            val revision = transport.send(session, sent)
            assertEquals(baseline + 1, revision)
            assertEquals(revision, transport.send(session, sent))
            session = session.copy(state = session.state.committed(first.id, revision))
            store.save(session)
            transport.acknowledge(session, requireNotNull(session.state.ack))
            assertEquals(session, SyncStore(context).load())

            val sensitive = LocalCopy(UUID.randomUUID().toString(), "android-sensitive-fixture", System.currentTimeMillis(), true)
            session = session.copy(state = session.state.acknowledged(requireNotNull(session.state.ack)).localCopy(sensitive).markAttempted())
            val sensitiveRevision = transport.send(session, requireNotNull(session.state.pending))
            assertEquals(baseline + 2, sensitiveRevision)
            val remote = transport.state(session)
            assertEquals(true, remote.message?.sensitive)
            assertEquals("android-sensitive-fixture", transport.content(session, requireNotNull(remote.message)))

            val obsolete = LocalCopy(UUID.randomUUID().toString(), "obsolete-fixture", System.currentTimeMillis())
            val pending = Outbox(obsolete, sensitiveRevision, true)
            assertEquals(null, transport.cancel(session, pending))
            var rejected = false
            try { transport.send(session, pending) } catch (e: HttpFailure) { rejected = e.status == 409 }
            assertTrue("cancelled in-flight requests must not commit later", rejected)
            assertNotNull(store.load())
        } finally {
            store.clear()
            HttpsURLConnection.setDefaultSSLSocketFactory(original)
        }
    }

    @Suppress("DEPRECATION")
    @Test fun runningSyncRuntimeHandlesClipboardAndSensitivePolicy() {
        val context = fixtureContext()
        val url = requireNotNull(args.getString("rcs_url"))
        check(url.startsWith("https://localhost:"))
        val original = HttpsURLConnection.getDefaultSSLSocketFactory()
        HttpsURLConnection.setDefaultSSLSocketFactory(pinnedFactory())
        val prefs = android.preference.PreferenceManager.getDefaultSharedPreferences(context)
        val store = SyncStore(context)
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val transport = SyncTransport(url)
        val runtime = SyncRuntime(context) { true }
        store.clear()
        try {
            fun registration(token: String): StoredSession {
                val initial = StoredSession(url, token, false, true, SyncState("", UUID.randomUUID().toString(), 0))
                val (epoch, revision) = transport.register(initial.state.deviceId, initial.token,
                    requireNotNull(args.getString("rcs_user")), requireNotNull(args.getString("rcs_password")))
                return initial.copy(paired = true, state = initial.state.copy(epoch = epoch, cursor = revision))
            }
            val device = registration("7a".repeat(32))
            val remote = registration("8b".repeat(32))
            val baseline = device.state.cursor
            assertEquals(baseline, remote.state.cursor)
            store.save(device)
            prefs.edit().putBoolean(RCS_ALLOW_SENSITIVE, false).putBoolean(RCS_ENABLED, true)
                .putBoolean(RCS_REVIEW_PENDING, false).putString(RCS_STATUS, "connecting").commit()
            instrumentation.runOnMainSync { runtime.create() }
            instrumentation.runOnMainSync { clipboard.setPrimaryClip(ClipData.newPlainText("fixture", "local-runtime")) }
            waitUntil("local copy was not uploaded") { transport.state(device).revision == baseline + 1 }
            val remoteCopy = LocalCopy(UUID.randomUUID().toString(), "remote-runtime", System.currentTimeMillis())
            assertEquals(baseline + 2, transport.send(remote, Outbox(remoteCopy, baseline + 1, true)))
            waitUntil("remote copy was not applied") {
                clipboard.primaryClip?.getItemAt(0)?.text?.toString() == "remote-runtime"
            }
            Thread.sleep(1200)
            assertEquals("remote write must not be echoed", baseline + 2, transport.state(device).revision)
            instrumentation.runOnMainSync { clipboard.clearPrimaryClip() }
            assertEquals(baseline + 2, transport.send(remote, Outbox(remoteCopy, baseline + 1, true)))
            Thread.sleep(1200)
            assertEquals(null, clipboard.primaryClip)
            val blocked = LocalCopy(UUID.randomUUID().toString(), "blocked-sensitive", System.currentTimeMillis(), true)
            assertEquals(baseline + 3, transport.send(remote, Outbox(blocked, baseline + 2, true)))
            waitUntil("sensitive skip was not acknowledged") { transport.state(device).ackRevision >= baseline + 3 }
            assertEquals(null, clipboard.primaryClip)
            prefs.edit().putBoolean(RCS_ALLOW_SENSITIVE, true).commit()
            val allowed = LocalCopy(UUID.randomUUID().toString(), "allowed-sensitive", System.currentTimeMillis(), true)
            assertEquals(baseline + 4, transport.send(remote, Outbox(allowed, baseline + 3, true)))
            waitUntil("enabled sensitive content was not applied") {
                clipboard.primaryClip?.getItemAt(0)?.text?.toString() == "allowed-sensitive"
            }
            assertTrue(clipboard.primaryClip?.description?.extras?.getBoolean("android.content.extra.IS_SENSITIVE") == true)
            Thread.sleep(1200)
            assertEquals(baseline + 4, transport.state(device).revision)
        } finally {
            instrumentation.runOnMainSync { runtime.destroy(); clipboard.clearPrimaryClip() }
            prefs.edit().putBoolean(RCS_ENABLED, false).putBoolean(RCS_ALLOW_SENSITIVE, false).commit()
            store.clear()
            HttpsURLConnection.setDefaultSSLSocketFactory(original)
        }
    }
}

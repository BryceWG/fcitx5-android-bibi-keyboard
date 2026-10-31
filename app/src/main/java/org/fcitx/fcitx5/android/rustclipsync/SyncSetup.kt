/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.rustclipsync

import android.content.Context
import java.security.SecureRandom
import java.util.UUID

/** Pairing and conflict resolution run on a background thread. */
internal object SyncSetup {
    fun pair(context: Context, url: String, user: String, password: String): String {
        val transport = SyncTransport(url)
        val store = SyncStore(context)
        val previous = store.load()
        check(previous == null || (!previous.paired && previous.url == url)) { "already paired" }
        val candidate = previous ?: StoredSession(
            url, token(), paired = false, enabled = true,
            state = SyncState("", UUID.randomUUID().toString(), 0),
        ).also(store::save)
        // The token was saved before the request: retrying after a lost response is safe.
        val (epoch, baseline) = transport.register(candidate.state.deviceId, candidate.token, user, password)
        store.update { current ->
            check(!current.paired && current.state.deviceId == candidate.state.deviceId && current.url == url)
            current.copy(paired = true, enabled = true, state = current.state.copy(epoch = epoch, cursor = baseline))
        }
        return candidate.state.deviceId
    }

    /** User explicitly chose to publish the newest pending local copy over the remote value. */
    @Suppress("DEPRECATION")
    fun resolveWithLocal(context: Context) {
        val store = SyncStore(context)
        val original = requireNotNull(store.load())
        val pending = requireNotNull(original.state.pending)
        check(!original.state.discardPending) { "no local copy" }
        val latestLocal = original.state.queued ?: pending.copy
        check(!latestLocal.sensitive || android.preference.PreferenceManager.getDefaultSharedPreferences(context)
            .getBoolean(RCS_ALLOW_SENSITIVE, false)) { "sensitive sync is disabled" }
        val transport = SyncTransport(original.url)
        if (pending.attempted) transport.cancel(original, pending)
        val latestRemote = transport.state(original)
        check(latestRemote.epoch == original.state.epoch)
        val ack = latestRemote.message?.let {
            Ack(it.id, it.revision, if (it.sourceDeviceId == original.state.deviceId) "self" else "skipped")
        }
        val replacement = LocalCopy(UUID.randomUUID().toString(), latestLocal.text, System.currentTimeMillis(), latestLocal.sensitive)
        store.update { current ->
            // A new local copy during this operation wins and must be reviewed again.
            check(current == original) { "clipboard changed during conflict resolution" }
            current.copy(state = SyncState(original.state.epoch, original.state.deviceId, latestRemote.revision,
                pending = Outbox(replacement, latestRemote.revision, false), ack = ack))
        }
    }

    private fun token(): String {
        val bytes = ByteArray(32)
        SecureRandom().nextBytes(bytes)
        val digits = "0123456789abcdef"
        return buildString(64) {
            bytes.forEach { value ->
                val n = value.toInt() and 0xff
                append(digits[n ushr 4]); append(digits[n and 15])
            }
        }
    }
}

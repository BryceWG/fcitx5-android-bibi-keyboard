/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.rustclipsync

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PersistableBundle
import android.os.SystemClock
import android.preference.PreferenceManager
import android.provider.Settings
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

internal const val RCS_ENABLED = "rustclipsync_enabled"
internal const val RCS_ALLOW_SENSITIVE = "rustclipsync_allow_sensitive"
internal const val RCS_STATUS = "rustclipsync_status"
internal const val RCS_PAIRED = "rustclipsync_paired"
internal const val RCS_DEVICE_ID = "rustclipsync_device_id"
internal const val RCS_REVIEW_PENDING = "rustclipsync_review_pending"

/** One sender and one long-poll worker, with all clipboard access on the main thread. */
@Suppress("DEPRECATION")
internal class SyncRuntime(private val service: Context, private val activeImeOverride: (() -> Boolean)? = null) {
    private val prefs = PreferenceManager.getDefaultSharedPreferences(service)
    private val clipboard = service.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    private val store = SyncStore(service)
    private val handler = Handler(Looper.getMainLooper())
    private val runId = AtomicLong()
    private val generation = AtomicLong()
    @Volatile private var localQueue = LocalQueue()
    private val echo = AtomicReference<Echo?>(null)
    @Volatile private var running = false
    private var sender: ScheduledExecutorService? = null
    private var localWriter: java.util.concurrent.ExecutorService? = null
    private var poller: java.util.concurrent.ExecutorService? = null
    private var nextSendAt = 0L
    private var sendBackoff = 1L

    private val preferenceListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == RCS_ENABLED) reconcile()
    }

    private val listener = ClipboardManager.OnPrimaryClipChangedListener {
        if (!running || !isDefaultIme()) return@OnPrimaryClipChangedListener
        generation.incrementAndGet()
        val clip = try { clipboard.primaryClip } catch (_: Exception) { null }
        val now = SystemClock.elapsedRealtime()
        val expected = echo.get()
        val marker = if (Build.VERSION.SDK_INT >= 24) clip?.description?.extras?.getString(MARKER) else null
        if (expected != null && now <= expected.until && marker == expected.messageId) {
            return@OnPrimaryClipChangedListener
        }
        echo.set(null)
        val sensitive = try {
            if (Build.VERSION.SDK_INT < 24) true
            else clip?.description?.extras?.getBoolean("android.content.extra.IS_SENSITIVE", false) ?: false
        } catch (_: Exception) { true }
        val text = try { if (clip != null && clip.itemCount > 0) clip.getItemAt(0).text?.toString() else null }
            catch (_: Exception) { null }
        val allowSensitive = prefs.getBoolean(RCS_ALLOW_SENSITIVE, false)
        val candidate = text?.takeIf { it.isNotEmpty() && it.length <= SyncStore.MAX_TEXT_BYTES }
        val value = if (sensitive && !allowSensitive) null else candidate
        enqueueLocal(LocalUpdate(value, sensitive, runId.get()))
    }

    fun create() {
        prefs.registerOnSharedPreferenceChangeListener(preferenceListener)
        reconcile()
    }

    fun destroy() {
        prefs.unregisterOnSharedPreferenceChangeListener(preferenceListener)
        stop()
    }

    private fun reconcile() {
        if (prefs.getBoolean(RCS_ENABLED, false)) start() else {
            if (running) prefs.edit().putBoolean(RCS_REVIEW_PENDING, true).apply()
            stop()
        }
    }

    private fun start() {
        if (running) return
        if (Build.VERSION.SDK_INT < 24) { status("unsupported"); return }
        running = true
        localQueue = LocalQueue()
        nextSendAt = 0
        sendBackoff = 1
        val run = runId.incrementAndGet()
        generation.incrementAndGet()
        val worker = Executors.newSingleThreadScheduledExecutor { task ->
            Thread(task, "RustClipSync-sender").apply { isDaemon = true }
        }
        val receiver = Executors.newSingleThreadExecutor { task ->
            Thread(task, "RustClipSync-receiver").apply { isDaemon = true }
        }
        val writer = Executors.newSingleThreadExecutor { task ->
            Thread(task, "RustClipSync-local-state").apply { isDaemon = true }
        }
        sender = worker
        localWriter = writer
        poller = receiver
        clipboard.addPrimaryClipChangedListener(listener)
        worker.execute {
            try {
                var current = store.load()
                if (current == null || !current.paired || !current.enabled) { status("not_paired"); return@execute }
                if (current.state.applying != null) current = store.update { it.copy(state = it.state.recoverAmbiguousApply()) }
                if (current.state.pending != null && !current.state.discardPending) reviewRecoveredCopy(run, current)
                if (prefs.getBoolean(RCS_REVIEW_PENDING, false)) {
                    val afterReview = store.load()
                    if (afterReview?.state?.pending != null && !afterReview.state.discardPending) status("conflict")
                    prefs.edit().putBoolean(RCS_REVIEW_PENDING, false).apply()
                }
                if (active(run)) receiver.execute { receiveLoop(run) }
            } catch (_: Exception) { status("storage_error") }
        }
        worker.scheduleWithFixedDelay({ if (active(run)) sendOnce(run) }, 1, 1, TimeUnit.SECONDS)
    }

    private fun stop() {
        if (!running) return
        running = false
        runId.incrementAndGet()
        generation.incrementAndGet()
        clipboard.removePrimaryClipChangedListener(listener)
        echo.set(null)
        sender?.shutdownNow()
        localWriter?.shutdownNow()
        poller?.shutdownNow()
        sender = null
        localWriter = null
        poller = null
        status("disabled")
    }

    private fun active(run: Long) = running && runId.get() == run

    private fun isDefaultIme(): Boolean = activeImeOverride?.invoke() ?: try {
        val current = Settings.Secure.getString(service.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
        val component = ComponentName.unflattenFromString(current ?: "")
        component?.packageName == service.packageName && component.className == service.javaClass.name
    } catch (_: Exception) { false }

    private fun same(a: StoredSession, b: StoredSession) = a.state.deviceId == b.state.deviceId && a.state.epoch == b.state.epoch

    private fun enqueueLocal(value: LocalUpdate) {
        val queue = localQueue
        val schedule = synchronized(queue) {
            queue.latest = value
            queue.busy.set(1)
            if (queue.scheduled) false else { queue.scheduled = true; true }
        }
        if (!schedule) return
        try {
            val writer = localWriter ?: throw java.util.concurrent.RejectedExecutionException()
            writer.execute {
                while (true) {
                    val next = synchronized(queue) {
                        val item = queue.latest
                        queue.latest = null
                        if (item == null) { queue.scheduled = false; queue.busy.set(0) }
                        item
                    } ?: break
                    try {
                        if (!active(next.run)) continue
                        val safe = next.text?.takeIf { it.toByteArray(Charsets.UTF_8).size <= SyncStore.MAX_TEXT_BYTES }
                        store.update { current ->
                            if (!active(next.run) || !current.paired || !current.enabled) current
                            else current.copy(state = if (safe == null) current.state.localCleared()
                                else current.state.localCopy(LocalCopy(UUID.randomUUID().toString(), safe, System.currentTimeMillis(), next.sensitive)))
                        }
                    } catch (_: Exception) { status("storage_error") }
                }
            }
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            synchronized(queue) { queue.latest = null; queue.scheduled = false; queue.busy.set(0) }
        }
    }

    /** A copy may have changed while the input method process was not running. */
    private fun reviewRecoveredCopy(run: Long, previous: StoredSession) {
        val expected = previous.state.queued ?: previous.state.pending?.copy ?: return
        val startGeneration = generation.get()
        val known = AtomicBoolean(false)
        val snapshot = AtomicReference<Pair<String?, Boolean>?>(null)
        val latch = CountDownLatch(1)
        handler.post {
            try {
                if (active(run) && isDefaultIme()) {
                    val clip = clipboard.primaryClip
                    val sensitive = if (Build.VERSION.SDK_INT >= 24)
                        clip?.description?.extras?.getBoolean("android.content.extra.IS_SENSITIVE", false) ?: false else true
                    val text = if (clip != null && clip.itemCount > 0) clip.getItemAt(0).text?.toString() else null
                    snapshot.set(text to sensitive)
                    known.set(true)
                }
            } catch (_: Exception) { }
            finally { latch.countDown() }
        }
        if (!latch.await(5, TimeUnit.SECONDS) || !known.get()) { status("conflict"); return }
        if (!active(run) || generation.get() != startGeneration || localQueue.busy.get() != 0) return
        val current = snapshot.get()
        if (current == null || current.first != expected.text || current.second != expected.sensitive ||
            (expected.sensitive && !prefs.getBoolean(RCS_ALLOW_SENSITIVE, false))) {
            store.update { latest ->
                if (same(previous, latest) && latest.state == previous.state &&
                    generation.get() == startGeneration && localQueue.busy.get() == 0)
                    latest.copy(state = latest.state.localCleared()) else latest
            }
        }
    }

    private fun sendOnce(run: Long) {
        try {
            if (prefs.getString(RCS_STATUS, "") in setOf("conflict", "expired", "re_pair", "storage_error")) return
            if (SystemClock.elapsedRealtime() < nextSendAt) return
            if (localQueue.busy.get() != 0) return
            var session = store.load() ?: return
            if (!session.paired || !session.enabled || !active(run)) return
            if (!prefs.getBoolean(RCS_ALLOW_SENSITIVE, false) &&
                (session.state.queued?.sensitive == true || (session.state.pending?.copy?.sensitive == true && session.state.queued == null))) {
                session = store.update { current -> current.copy(state = current.state.localCleared()) }
            }
            val transport = SyncTransport(session.url)
            session.state.ack?.let { ack ->
                try { transport.acknowledge(session, ack) }
                catch (e: HttpFailure) {
                    if (e.status != 410) throw e
                    val current = transport.state(session)
                    if (current.epoch != session.state.epoch || current.revision <= ack.revision) {
                        status("re_pair")
                        return
                    }
                }
                if (!active(run)) return
                session = store.update { current ->
                    if (same(session, current)) current.copy(state = current.state.acknowledged(ack)) else current
                }
            }
            val pending = session.state.pending ?: return
            val now = System.currentTimeMillis()
            if (now - pending.copy.createdAtMs > MAX_RETRY_MS || now + CLOCK_SKEW_MS < pending.copy.createdAtMs) {
                status("expired"); return
            }
            if (session.state.queued != null || session.state.discardPending) {
                val revision = transport.cancel(session, pending)
                if (!active(run)) return
                store.update { current ->
                    if (!same(session, current) || current.state.pending?.copy?.id != pending.copy.id) current
                    else current.copy(state = if (revision == null) current.state.cancelled(pending.copy.id)
                        else current.state.committed(pending.copy.id, revision))
                }
            } else {
                session = store.update { current ->
                    if (!same(session, current) || current.state.pending?.copy?.id != pending.copy.id ||
                        current.state.queued != null || current.state.discardPending) current
                    else current.copy(state = current.state.markAttempted())
                }
                val sending = session.state.pending ?: return
                if (sending.copy.id != pending.copy.id || !sending.attempted || session.state.queued != null ||
                    session.state.discardPending || localQueue.busy.get() != 0) return
                val revision = transport.send(session, sending)
                if (!active(run)) return
                store.update { current ->
                    if (!same(session, current) || current.state.pending?.copy?.id != sending.copy.id) current
                    else current.copy(state = current.state.committed(sending.copy.id, revision))
                }
            }
            nextSendAt = 0
            sendBackoff = 1
            connectedStatus()
        } catch (e: HttpFailure) {
            when (e.status) {
                401, 403 -> status("re_pair")
                409 -> status("conflict")
                else -> { connectedStatus("offline"); deferSend() }
            }
        } catch (_: java.io.IOException) { connectedStatus("offline"); deferSend() }
        catch (_: Exception) { status("storage_error") }
    }

    private fun receiveLoop(run: Long) {
        var backoff = 1L
        while (active(run)) {
            try {
                val session = store.load() ?: break
                if (!session.paired || !session.enabled) break
                val gen = generation.get()
                val remote = SyncTransport(session.url).waitForChanges(session)
                if (!active(run)) break
                if (remote != null) {
                    receive(run, gen, session, remote)
                    // A local pending copy, a hidden IME, or a guarded write can
                    // leave this revision unprocessed; avoid a hot poll loop.
                    val after = store.load()
                    if (after != null && after.state.cursor < remote.revision && !sleep(5)) break
                }
                connectedStatus()
                backoff = 1
            } catch (e: HttpFailure) {
                when (e.status) {
                    400, 401, 403, 409 -> { status("re_pair"); break }
                    else -> connectedStatus("offline")
                }
                if (!sleep(backoff)) break
                backoff = (backoff * 2).coerceAtMost(30)
            } catch (_: java.io.IOException) {
                connectedStatus("offline")
                if (!sleep(backoff)) break
                backoff = (backoff * 2).coerceAtMost(30)
            } catch (_: Exception) {
                status("storage_error")
                break
            }
        }
    }

    private fun receive(run: Long, gen: Long, session: StoredSession, remote: ServerState) {
        if (remote.epoch != session.state.epoch) { status("re_pair"); return }
        val message = remote.message ?: return
        if (remote.ackRevision > session.state.cursor) {
            store.update { current ->
                if (same(session, current) && current.state.cursor < remote.ackRevision)
                    current.copy(state = current.state.copy(cursor = remote.ackRevision)) else current
            }
            return
        }
        if (message.revision <= session.state.cursor || !active(run) || localQueue.busy.get() != 0) return
        val latest = store.load() ?: return
        if (!same(session, latest) || latest.state.pending != null || latest.state.applying != null ||
            message.revision <= latest.state.cursor || gen != generation.get()) return
        if (message.sourceDeviceId == session.state.deviceId) {
            store.update { current ->
                if (same(session, current) && current.state.pending == null && current.state.applying == null &&
                    message.revision > current.state.cursor && gen == generation.get())
                    current.copy(state = current.state.remoteSelf(message)) else current
            }
            return
        }
        if (message.sensitive && !prefs.getBoolean(RCS_ALLOW_SENSITIVE, false)) {
            store.update { current ->
                if (same(session, current) && current.state.pending == null && current.state.applying == null &&
                    message.revision > current.state.cursor && gen == generation.get())
                    current.copy(state = current.state.copy(cursor = message.revision,
                        ack = Ack(message.id, message.revision, "skipped"))) else current
            }
            return
        }
        if (!isDefaultIme()) return
        val text = try { SyncTransport(session.url).content(session, message) }
            catch (e: HttpFailure) { if (e.status == 410) return else throw e }
        require(text.toByteArray(Charsets.UTF_8).size.toLong() == remote.size)
        val confirmed = SyncTransport(session.url).state(session)
        if (confirmed.epoch != remote.epoch || confirmed.message != message) return
        if (!active(run) || gen != generation.get() || localQueue.busy.get() != 0) return
        val journaled = store.update { current ->
            if (same(session, current) && current.state.pending == null && current.state.applying == null &&
                message.revision > current.state.cursor && gen == generation.get() && localQueue.busy.get() == 0)
                current.copy(state = current.state.prepareApply(message)) else current
        }
        if (journaled.state.applying != message) return
        val latch = CountDownLatch(1)
        val allowed = AtomicBoolean(true)
        val applied = AtomicBoolean(false)
        handler.post {
            try {
                if (allowed.compareAndSet(true, false) && active(run) && isDefaultIme() &&
                    (!message.sensitive || prefs.getBoolean(RCS_ALLOW_SENSITIVE, false)) &&
                    gen == generation.get() && localQueue.busy.get() == 0) {
                    val clip = ClipData.newPlainText("RustClipSync", text)
                    if (Build.VERSION.SDK_INT >= 24) clip.description.extras = PersistableBundle().apply {
                        putString(MARKER, message.id)
                        if (message.sensitive) putBoolean("android.content.extra.IS_SENSITIVE", true)
                        if (Build.VERSION.SDK_INT >= 34) putBoolean("android.content.extra.IS_REMOTE_DEVICE", true)
                    }
                    echo.set(Echo(message.id, SystemClock.elapsedRealtime() + 3_000))
                    clipboard.setPrimaryClip(clip)
                    applied.set(true)
                }
            } catch (_: Exception) { echo.set(null) }
            finally { latch.countDown() }
        }
        if (!latch.await(5, TimeUnit.SECONDS)) {
            allowed.set(false)
            // Keep the journal if the main thread may already have started the write.
            if (!latch.await(1, TimeUnit.SECONDS)) { status("storage_error"); return }
        }
        store.update { current ->
            if (same(session, current) && current.state.applying == message)
                current.copy(state = current.state.finishApply(message.id, applied.get())) else current
        }
    }

    private fun sleep(seconds: Long): Boolean = try { Thread.sleep(seconds * 1_000); true }
        catch (_: InterruptedException) { false }

    private fun deferSend() {
        nextSendAt = SystemClock.elapsedRealtime() + sendBackoff * 1_000
        sendBackoff = (sendBackoff * 2).coerceAtMost(30)
    }

    private fun status(value: String) { prefs.edit().putString(RCS_STATUS, value).apply() }
    private fun connectedStatus(value: String = "connected") {
        if (prefs.getString(RCS_STATUS, "") !in setOf("conflict", "expired", "re_pair", "storage_error")) status(value)
    }

    private data class Echo(val messageId: String, val until: Long)
    private data class LocalUpdate(val text: String?, val sensitive: Boolean, val run: Long)
    private class LocalQueue {
        var latest: LocalUpdate? = null
        var scheduled = false
        val busy = AtomicInteger()
    }
    private companion object {
        const val MARKER = "io.github.infinifar.rustclipsync.message"
        const val MAX_RETRY_MS = 7L * 24 * 60 * 60 * 1_000
        const val CLOCK_SKEW_MS = 5L * 60 * 1_000
    }
}

/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.rustclipsync

/** Pure transitions. The caller must persist each returned state before side effects. */
internal data class SyncState(
    val epoch: String,
    val deviceId: String,
    val cursor: Long,
    val pending: Outbox? = null,
    val queued: LocalCopy? = null,
    val discardPending: Boolean = false,
    val applying: RemoteMessage? = null,
    val ack: Ack? = null,
) {
    init {
        require(cursor >= 0)
        require(queued == null || pending?.attempted == true)
        require(!discardPending || (pending?.attempted == true && queued == null))
    }

    fun localCopy(value: LocalCopy): SyncState {
        val current = pending
        return when {
            current == null -> copy(pending = Outbox(value, cursor, false))
            !current.attempted -> copy(pending = Outbox(value, current.baseRevision, false))
            else -> copy(queued = value, discardPending = false)
        }
    }

    /** Clearing or blocking a newer sensitive copy also supersedes old unsent text. */
    fun localCleared(): SyncState {
        val current = pending ?: return this
        return if (current.attempted) copy(queued = null, discardPending = true)
        else copy(pending = null, queued = null, discardPending = false)
    }

    /** Persist this state before sending the pending request. */
    fun markAttempted(): SyncState {
        val current = requireNotNull(pending)
        require(queued == null && !discardPending)
        return copy(pending = current.copy(attempted = true))
    }

    fun committed(messageId: String, revision: Long): SyncState {
        val current = requireNotNull(pending)
        require(current.copy.id == messageId && revision > current.baseRevision)
        val next = queued?.let { Outbox(it, revision, false) }
        return copy(cursor = maxOf(cursor, revision), pending = next, queued = null, discardPending = false,
            ack = Ack(messageId, revision, "self"))
    }

    /** Cancellation must have completed before a newer copy can replace an attempted request. */
    fun cancelled(messageId: String): SyncState {
        val current = requireNotNull(pending)
        require(current.copy.id == messageId && current.attempted && (queued != null || discardPending))
        return copy(pending = queued?.let { Outbox(it, current.baseRevision, false) }, queued = null,
            discardPending = false)
    }

    fun remoteSelf(message: RemoteMessage): SyncState {
        require(message.sourceDeviceId == deviceId && message.revision > cursor)
        require(pending == null && applying == null)
        return copy(cursor = message.revision, ack = Ack(message.id, message.revision, "self"))
    }

    /** Persist the journal before invoking the operating system clipboard write. */
    fun prepareApply(message: RemoteMessage): SyncState {
        require(message.sourceDeviceId != deviceId && message.revision > cursor)
        require(pending == null && applying == null)
        return copy(applying = message)
    }

    fun finishApply(messageId: String, success: Boolean): SyncState {
        val message = requireNotNull(applying)
        require(message.id == messageId)
        return if (success) copy(cursor = message.revision, applying = null,
            ack = Ack(message.id, message.revision, "applied")) else copy(applying = null)
    }

    /** A crash could have happened on either side of the OS write. Do not blindly replay it. */
    fun recoverAmbiguousApply(): SyncState {
        val message = applying ?: return this
        return copy(cursor = maxOf(cursor, message.revision), applying = null,
            ack = Ack(message.id, message.revision, "skipped"))
    }

    fun acknowledged(value: Ack): SyncState = if (ack == value) copy(ack = null) else this
}

internal data class LocalCopy(val id: String, val text: String, val createdAtMs: Long, val sensitive: Boolean = false)
internal data class Outbox(val copy: LocalCopy, val baseRevision: Long, val attempted: Boolean)
internal data class RemoteMessage(val id: String, val revision: Long, val sourceDeviceId: String, val sensitive: Boolean = false)
internal data class Ack(val id: String, val revision: Long, val result: String)

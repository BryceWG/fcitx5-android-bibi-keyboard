/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.rustclipsync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class SyncStateTest {
    private val initial = SyncState("epoch", "this-device", 4)
    private fun local(id: String, text: String) = LocalCopy(id, text, 1)

    @Test fun offlineCopiesCollapseToLatestBeforeFirstAttempt() {
        val state = initial.localCopy(local("a", "same"))
            .localCopy(local("b", "different"))
            .localCopy(local("c", "same"))
        assertEquals("c", state.pending?.copy?.id)
        assertEquals(4L, state.pending?.baseRevision)
        assertNull(state.queued)
    }

    @Test fun uncertainUploadMustBeResolvedBeforeLatestCopyIsSent() {
        val state = initial.localCopy(local("a", "old")).markAttempted()
            .localCopy(local("b", "middle")).localCopy(local("c", "new"))
        assertEquals("a", state.pending?.copy?.id)
        assertEquals("c", state.queued?.id)
        assertThrows(IllegalArgumentException::class.java) { state.markAttempted() }
        val cancelled = state.cancelled("a")
        assertEquals("c", cancelled.pending?.copy?.id)
        assertEquals(4L, cancelled.pending?.baseRevision)
        val committed = state.committed("a", 5)
        assertEquals("c", committed.pending?.copy?.id)
        assertEquals(5L, committed.pending?.baseRevision)
        assertEquals(Ack("a", 5, "self"), committed.ack)
    }

    @Test fun applyJournalPreventsBlindReplayAfterCrash() {
        val message = RemoteMessage("m", 5, "other")
        val journaled = initial.prepareApply(message)
        assertEquals(initial.cursor, journaled.cursor)
        val restarted = journaled.recoverAmbiguousApply()
        assertEquals(5L, restarted.cursor)
        assertEquals(Ack("m", 5, "skipped"), restarted.ack)
        assertNull(restarted.applying)
        assertEquals(initial, journaled.finishApply("m", false))
    }

    @Test fun identicalTextCanBeTwoDistinctEvents() {
        val first = initial.localCopy(local("a", "same")).markAttempted().committed("a", 5)
        val second = first.localCopy(local("b", "same"))
        assertEquals("b", second.pending?.copy?.id)
        assertEquals(5L, second.pending?.baseRevision)
    }

    @Test fun localPendingBlocksRemoteApply() {
        val state = initial.localCopy(local("a", "local"))
        assertThrows(IllegalArgumentException::class.java) {
            state.prepareApply(RemoteMessage("b", 5, "other"))
        }
    }

    @Test fun clearCancelsUncertainOldUploadAndStopsRetries() {
        val unsent = initial.localCopy(local("a", "text")).localCleared()
        assertNull(unsent.pending)
        val attempted = initial.localCopy(local("a", "text")).markAttempted().localCleared()
        assertEquals(true, attempted.discardPending)
        assertNull(attempted.cancelled("a").pending)
        val alreadyCommitted = attempted.committed("a", 5)
        assertNull(alreadyCommitted.pending)
        assertEquals(5L, alreadyCommitted.cursor)
    }

    @Test fun sensitiveMarkerSurvivesOutboxTransitions() {
        val copy = LocalCopy("sensitive", "fixture", 1, sensitive = true)
        val state = initial.localCopy(copy).markAttempted()
        assertEquals(true, state.pending?.copy?.sensitive)
        val later = state.localCopy(local("next", "normal")).committed("sensitive", 5)
        assertEquals(false, later.pending?.copy?.sensitive)
    }
}

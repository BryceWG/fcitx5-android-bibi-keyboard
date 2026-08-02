package org.fcitx.fcitx5.android.link

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AsrkbClipboardSyncStateTest {
    @Test
    fun statusRoundTripsWithDiagnosticDetail() {
        val status = AsrkbClipboardSyncStatus(
            AsrkbClipboardSyncPhase.ERROR,
            "Pro=-6, OSS=0",
        )

        assertEquals(status, AsrkbClipboardSyncStatus.decode(status.encode()))
        assertEquals(
            AsrkbClipboardSyncPhase.STOPPED,
            AsrkbClipboardSyncStatus.decode("broken").phase,
        )
    }

    @Test
    fun remoteWriteSuppressesOnlyMatchingNextCallback() {
        val suppressor = AsrkbClipboardEchoSuppressor(timeoutMs = 1_000)
        suppressor.expect("remote", nowMs = 100)

        assertTrue(suppressor.shouldSuppress("remote", nowMs = 200))
        assertFalse(suppressor.shouldSuppress("remote", nowMs = 201))
    }

    @Test
    fun differentOrExpiredClipboardDoesNotGetSuppressed() {
        val suppressor = AsrkbClipboardEchoSuppressor(timeoutMs = 1_000)
        suppressor.expect("remote", nowMs = 100)
        assertFalse(suppressor.shouldSuppress("local", nowMs = 200))

        suppressor.expect("remote", nowMs = 100)
        assertFalse(suppressor.shouldSuppress("remote", nowMs = 1_101))
    }

    @Test
    fun closingTheImeKeepsTheLastConnectionDiagnostic() {
        val failed = AsrkbClipboardSyncStatus(
            AsrkbClipboardSyncPhase.ERROR,
            "Pro=-7, OSS=-6",
        )

        assertEquals(failed, statusAfterWindowHidden(failed))
    }

    @Test
    fun closingTheImeCancelsInitialConnectionButKeepsAQueuedReconnectAlive() {
        val connecting = AsrkbClipboardSyncStatus(AsrkbClipboardSyncPhase.CONNECTING)
        val reconnecting = AsrkbClipboardSyncStatus(AsrkbClipboardSyncPhase.RECONNECTING)

        assertEquals(
            AsrkbClipboardSyncStatus(AsrkbClipboardSyncPhase.WAITING),
            statusAfterWindowHidden(connecting),
        )
        assertFalse(shouldStartActivationOnWindowShown(reconnecting.phase))
        assertEquals(reconnecting, statusAfterWindowHidden(reconnecting))
        assertTrue(shouldStartActivationOnWindowShown(AsrkbClipboardSyncPhase.WAITING))
    }

    @Test
    fun hostRequestsRequireTheEnabledActiveSession() {
        assertTrue(isClipboardHostRequestAuthorized(true, true, "oss", "oss"))
        assertFalse(isClipboardHostRequestAuthorized(false, true, "oss", "oss"))
        assertFalse(isClipboardHostRequestAuthorized(true, false, "oss", "oss"))
        assertFalse(isClipboardHostRequestAuthorized(true, true, "oss", "pro"))
    }

    @Test
    fun activeStatusPreservesAnObservingSubscription() {
        assertEquals(AsrkbClipboardSyncPhase.OBSERVING, activeSessionPhase(isObserving = true))
        assertEquals(AsrkbClipboardSyncPhase.CONNECTED, activeSessionPhase(isObserving = false))
    }
}

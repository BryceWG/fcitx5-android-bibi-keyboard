package org.fcitx.fcitx5.android.link

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.coroutines.runBlocking

class AsrkbExternalInputSessionTest {
    @Test
    fun successfulCommitTracksTheLastEditedSnapshotUntilClear() {
        val tracker = AsrkbCorrectionTracker(timeoutMs = 1_000)
        assertTrue(
            tracker.start(
                sessionId = 8,
                generation = 2,
                initial = AsrkbCursorSnapshot("before ", " after"),
                finalText = "Tory",
                committed = AsrkbCursorSnapshot("before Tory", " after"),
                nowMs = 0
            )
        )
        assertNull(tracker.update(2, AsrkbCursorSnapshot("before Tauri", " after"), 100))
        assertEquals(
            AsrkbCorrectionReport(8, 2, AsrkbCursorSnapshot("before Tauri", " after"), "cleared"),
            tracker.update(2, AsrkbCursorSnapshot("", ""), 200)
        )
    }

    @Test
    fun failedCommitVerificationAndGenerationChangesDoNotReport() {
        val tracker = AsrkbCorrectionTracker(timeoutMs = 10)
        assertFalse(tracker.start(1, 1, AsrkbCursorSnapshot("", ""), "Tory", AsrkbCursorSnapshot("", ""), 0))
        assertTrue(tracker.start(1, 1, AsrkbCursorSnapshot("", ""), "Tory", AsrkbCursorSnapshot("Tory", ""), 0))
        assertNull(tracker.update(2, AsrkbCursorSnapshot("Tauri", ""), 20))
    }

    @Test
    fun finishEventReportsTheLastTrustedSnapshot() {
        val tracker = AsrkbCorrectionTracker(timeoutMs = 1_000)
        assertTrue(
            tracker.start(
                8,
                2,
                AsrkbCursorSnapshot("before ", " after"),
                "Tory",
                AsrkbCursorSnapshot("before Tory", " after"),
                0
            )
        )
        assertNull(tracker.update(2, AsrkbCursorSnapshot("before Tauri", " after"), 100))

        assertEquals(
            AsrkbCorrectionReport(
                8,
                2,
                AsrkbCursorSnapshot("before Tauri", " after"),
                "editor_action"
            ),
            tracker.finishLast(2, "editor_action")
        )
        assertNull(tracker.finishLast(2, "editor_action"))
    }

    @Test
    fun finishEventCanCaptureAnImmediateEditWithoutWaitingForUpdate() {
        val tracker = AsrkbCorrectionTracker()
        assertTrue(tracker.start(8, 2, AsrkbCursorSnapshot("", ""), "Tory", AsrkbCursorSnapshot("Tory", ""), 0))

        assertEquals(
            AsrkbCorrectionReport(8, 2, AsrkbCursorSnapshot("Tauri", ""), "editor_action"),
            tracker.finish(2, AsrkbCursorSnapshot("Tauri", ""), "editor_action")
        )
    }

    @Test
    fun unchangedTextDoesNotProduceAReport() {
        val tracker = AsrkbCorrectionTracker()
        assertTrue(
            tracker.start(
                8,
                2,
                AsrkbCursorSnapshot("before ", " after"),
                "Tory",
                AsrkbCursorSnapshot("before Tory", " after"),
                0
            )
        )

        assertNull(tracker.finishLast(2, "finish_input"))
    }

    @Test
    fun editorGenerationFollowsInputLifecycleAndPreservesSameEditorRestart() {
        val tracker = AsrkbEditorGenerationTracker()
        val first = AsrkbEditorIdentity("pkg", 1, 1, 0)
        val second = first.copy(fieldId = 2)

        assertEquals(1, tracker.onStartInput(first, restarting = false))
        assertEquals(1, tracker.onStartInput(first, restarting = true))
        assertEquals(2, tracker.onStartInput(second, restarting = true))
        assertEquals(3, tracker.onFinishInput())
        assertEquals(4, tracker.onStartInput(second, restarting = true))
    }

    @Test
    fun unknownOptionalTransactionStillContinuesOriginalAsr() = runBlocking {
        var attachCalled = false
        var originalStarted = false
        val correctionEnabled = negotiateOptionalInputThenContinue(
            queryRequirements = { null },
            attachInputContext = {
                attachCalled = true
                true
            },
            continueOriginalAsr = { originalStarted = true }
        )

        assertFalse(correctionEnabled)
        assertFalse(attachCalled)
        assertTrue(originalStarted)
    }

    @Test
    fun editReportIsDispatchedOffTheCallerThread() = runBlocking {
        val caller = Thread.currentThread()
        var reportThread: Thread? = null
        val order = mutableListOf<String>()

        launchAsrkbEditReport(
            this,
            AsrkbCorrectionReport(1, 1, AsrkbCursorSnapshot("a", ""), "finish_input"),
            submit = {
                reportThread = Thread.currentThread()
                order += "submit"
            },
            onComplete = { order += "complete" }
        ).join()

        assertTrue(reportThread !== caller)
        assertEquals(listOf("submit", "complete"), order)
    }

    @Test
    fun staleNegotiationTokenIsRejected() {
        val gate = AsrkbNegotiationGate()
        val connection = Any()
        val stale = gate.begin(1, 1, connection)

        gate.begin(2, 2, connection)

        assertFalse(gate.isCurrent(stale))
    }

    @Test
    fun privacyGateRejectsSensitiveEditors() {
        assertTrue(AsrkbEditorPrivacy.isEligible(1, 0))
        assertFalse(AsrkbEditorPrivacy.isEligible(0x81, 0))
        assertFalse(AsrkbEditorPrivacy.isEligible(1, 0x01000000))
    }
}

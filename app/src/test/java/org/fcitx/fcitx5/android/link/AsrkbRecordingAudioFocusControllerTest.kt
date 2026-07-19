/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 BryceWG
 */

package org.fcitx.fcitx5.android.link

import android.media.AudioManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class AsrkbRecordingAudioFocusControllerTest {
    @Test
    fun releaseAbandonsGrantedFocusExactlyOnce() {
        val gateway = FakeGateway()
        val controller = AsrkbRecordingAudioFocusController(gateway) { }

        assertTrue(controller.acquire())
        controller.release()
        controller.release()

        assertFalse(controller.isHeldForTest())
        assertEquals(1, gateway.abandoned.size)
    }

    @Test
    fun failedAcquireDoesNotCreateLease() {
        val gateway = FakeGateway(grantRequests = false)
        val controller = AsrkbRecordingAudioFocusController(gateway) { }

        assertFalse(controller.acquire())
        controller.release()

        assertFalse(controller.isHeldForTest())
        assertTrue(gateway.abandoned.isEmpty())
    }

    @Test
    fun focusLossReleasesLeaseAndNotifiesOnce() {
        val gateway = FakeGateway()
        val losses = mutableListOf<AsrkbRecordingAudioFocusLoss>()
        val controller = AsrkbRecordingAudioFocusController(gateway, losses::add)

        assertTrue(controller.acquire())
        gateway.emit(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
        gateway.emit(AudioManager.AUDIOFOCUS_LOSS)

        assertFalse(controller.isHeldForTest())
        assertEquals(listOf(AsrkbRecordingAudioFocusLoss.Transient), losses)
        assertEquals(1, gateway.abandoned.size)
    }

    @Test
    fun focusChangesMapOnlyLossEvents() {
        val cases = listOf(
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT to AsrkbRecordingAudioFocusLoss.Transient,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK to AsrkbRecordingAudioFocusLoss.MayDuck,
            AudioManager.AUDIOFOCUS_LOSS to AsrkbRecordingAudioFocusLoss.Permanent
        )
        cases.forEach { (change, expected) ->
            val gateway = FakeGateway()
            val losses = mutableListOf<AsrkbRecordingAudioFocusLoss>()
            val controller = AsrkbRecordingAudioFocusController(gateway, losses::add)
            assertTrue(controller.acquire())

            gateway.emit(change)

            assertEquals(listOf(expected), losses)
            assertFalse(controller.isHeldForTest())
        }

        val gateway = FakeGateway()
        val losses = mutableListOf<AsrkbRecordingAudioFocusLoss>()
        val controller = AsrkbRecordingAudioFocusController(gateway, losses::add)
        assertTrue(controller.acquire())
        gateway.emit(AudioManager.AUDIOFOCUS_GAIN)
        assertTrue(losses.isEmpty())
        assertTrue(controller.isHeldForTest())
        controller.release()
    }

    @Test
    fun sessionReleaseWaitsForInFlightAcquireAndAbandonsLease() {
        val requestEntered = CountDownLatch(1)
        val continueRequest = CountDownLatch(1)
        val gateway = FakeGateway(
            requestEntered = requestEntered,
            continueRequest = continueRequest
        )
        val controller = AsrkbRecordingAudioFocusController(gateway) { }
        val owner = AsrkbRecordingAudioFocusSessionOwner()

        val acquireThread = thread { owner.acquire(controller) }
        assertTrue(requestEntered.await(5, TimeUnit.SECONDS))
        val releaseThread = thread { owner.release() }
        continueRequest.countDown()
        acquireThread.join(5_000)
        releaseThread.join(5_000)

        assertFalse(acquireThread.isAlive)
        assertFalse(releaseThread.isAlive)
        assertFalse(owner.owns(controller))
        assertEquals(gateway.granted, gateway.abandoned)
    }

    @Test
    fun releasedSessionDoesNotOwnStaleController() {
        val first = AsrkbRecordingAudioFocusController(FakeGateway()) { }
        val second = AsrkbRecordingAudioFocusController(FakeGateway()) { }
        val owner = AsrkbRecordingAudioFocusSessionOwner()

        assertTrue(owner.acquire(first))
        owner.release()
        assertTrue(owner.acquire(second))

        assertFalse(owner.owns(first))
        assertTrue(owner.owns(second))
    }

    private class FakeGateway(
        private val grantRequests: Boolean = true,
        private val requestEntered: CountDownLatch? = null,
        private val continueRequest: CountDownLatch? = null
    ) : AsrkbRecordingAudioFocusGateway {
        val granted = mutableListOf<AsrkbRecordingAudioFocusHandle>()
        val abandoned = mutableListOf<AsrkbRecordingAudioFocusHandle>()
        private var listener: ((Int) -> Unit)? = null

        override fun requestFocus(onFocusChange: (Int) -> Unit): AsrkbRecordingAudioFocusHandle? {
            listener = onFocusChange
            requestEntered?.countDown()
            continueRequest?.await(5, TimeUnit.SECONDS)
            if (!grantRequests) return null
            return FakeHandle(granted.size + 1).also(granted::add)
        }

        override fun abandonFocus(handle: AsrkbRecordingAudioFocusHandle) {
            abandoned += handle
        }

        fun emit(change: Int) {
            listener?.invoke(change)
        }
    }

    private data class FakeHandle(
        val id: Int
    ) : AsrkbRecordingAudioFocusHandle
}

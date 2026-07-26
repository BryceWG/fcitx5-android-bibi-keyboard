/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings.behavior

import android.content.SharedPreferences
import androidx.appcompat.app.AlertDialog
import androidx.preference.Preference
import androidx.preference.PreferenceManager
import androidx.preference.PreferenceScreen
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.prefs.ManagedPreferenceFragment
import org.fcitx.fcitx5.android.link.ASRKB_CLIPBOARD_SYNC_ENABLED_KEY
import org.fcitx.fcitx5.android.link.ASRKB_CLIPBOARD_SYNC_STATUS_KEY
import org.fcitx.fcitx5.android.link.AsrkbClipboardSyncPhase
import org.fcitx.fcitx5.android.link.AsrkbClipboardSyncStatus

class ClipboardSettingsFragment : ManagedPreferenceFragment(AppPrefs.getInstance().clipboard) {
    private lateinit var statusPreference: Preference
    private val statusListener =
        SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == ASRKB_CLIPBOARD_SYNC_ENABLED_KEY) showToggleNotice()
            if (key == ASRKB_CLIPBOARD_SYNC_ENABLED_KEY || key == ASRKB_CLIPBOARD_SYNC_STATUS_KEY) {
                updateStatus()
            }
        }

    override fun onPreferenceUiCreated(screen: PreferenceScreen) {
        statusPreference = Preference(screen.context).apply {
            key = "asrkb_clipboard_sync_status_display"
            isIconSpaceReserved = false
            isSelectable = false
            setTitle(R.string.asrkb_clipboard_sync_status)
        }
        screen.addPreference(statusPreference)
    }

    override fun onStart() {
        super.onStart()
        preferences().registerOnSharedPreferenceChangeListener(statusListener)
        updateStatus()
    }

    override fun onStop() {
        preferences().unregisterOnSharedPreferenceChangeListener(statusListener)
        super.onStop()
    }

    private fun updateStatus() {
        val prefs = preferences()
        if (!prefs.getBoolean(ASRKB_CLIPBOARD_SYNC_ENABLED_KEY, false)) {
            statusPreference.setSummary(R.string.asrkb_clipboard_sync_status_disabled)
            return
        }
        val status = AsrkbClipboardSyncStatus.decode(
            prefs.getString(ASRKB_CLIPBOARD_SYNC_STATUS_KEY, null),
        )
        statusPreference.summary = when (status.phase) {
            AsrkbClipboardSyncPhase.STOPPED -> getString(R.string.asrkb_clipboard_sync_status_stopped)
            AsrkbClipboardSyncPhase.DISABLED -> getString(R.string.asrkb_clipboard_sync_status_stopped)
            AsrkbClipboardSyncPhase.WAITING -> getString(R.string.asrkb_clipboard_sync_status_waiting)
            AsrkbClipboardSyncPhase.CONNECTING -> getString(R.string.asrkb_clipboard_sync_status_connecting)
            AsrkbClipboardSyncPhase.RECONNECTING -> getString(R.string.asrkb_clipboard_sync_status_reconnecting)
            AsrkbClipboardSyncPhase.CONNECTED ->
                getString(R.string.asrkb_clipboard_sync_status_connected, status.detail)
            AsrkbClipboardSyncPhase.OBSERVING ->
                getString(R.string.asrkb_clipboard_sync_status_observing, status.detail)
            AsrkbClipboardSyncPhase.ERROR ->
                getString(R.string.asrkb_clipboard_sync_status_error, status.detail)
        }
    }

    private fun showToggleNotice() {
        val enabled = preferences().getBoolean(ASRKB_CLIPBOARD_SYNC_ENABLED_KEY, false)
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.asrkb_clipboard_sync_enabled)
            .setMessage(
                if (enabled) R.string.asrkb_clipboard_sync_enabled_summary
                else R.string.asrkb_clipboard_sync_disabled_notice,
            )
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun preferences(): SharedPreferences =
        PreferenceManager.getDefaultSharedPreferences(requireContext())
}

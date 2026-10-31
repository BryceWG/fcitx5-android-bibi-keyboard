/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings.behavior

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.widget.EditText
import android.widget.LinearLayout
import androidx.appcompat.app.AlertDialog
import androidx.preference.Preference
import androidx.preference.PreferenceManager
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.prefs.ManagedPreferenceFragment
import org.fcitx.fcitx5.android.rustclipsync.RCS_ALLOW_SENSITIVE
import org.fcitx.fcitx5.android.rustclipsync.RCS_ENABLED
import org.fcitx.fcitx5.android.rustclipsync.RCS_DEVICE_ID
import org.fcitx.fcitx5.android.rustclipsync.RCS_PAIRED
import org.fcitx.fcitx5.android.rustclipsync.RCS_REVIEW_PENDING
import org.fcitx.fcitx5.android.rustclipsync.RCS_STATUS
import org.fcitx.fcitx5.android.rustclipsync.SyncSetup
import org.fcitx.fcitx5.android.rustclipsync.SyncStore

class ClipboardSettingsFragment : ManagedPreferenceFragment(AppPrefs.getInstance().clipboard) {
    private lateinit var statusPreference: Preference
    private lateinit var devicePreference: Preference
    private val main = Handler(Looper.getMainLooper())
    private val statusListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == RCS_ENABLED || key == RCS_STATUS || key == RCS_PAIRED || key == RCS_DEVICE_ID) updateStatus()
    }

    override fun onPreferenceUiCreated(screen: PreferenceScreen) {
        screen.addPreference(Preference(screen.context).apply {
            setTitle(R.string.rcs_pair_title)
            setSummary(R.string.rcs_pair_summary)
            isIconSpaceReserved = false
            setOnPreferenceClickListener { showPairDialog(); true }
        })
        screen.addPreference(SwitchPreferenceCompat(screen.context).apply {
            key = RCS_ENABLED
            setDefaultValue(false)
            setTitle(R.string.rcs_enabled)
            setSummary(R.string.rcs_enabled_summary)
            isIconSpaceReserved = false
            setOnPreferenceChangeListener { _, value ->
                if (value == true && !preferences().getBoolean(RCS_PAIRED, false)) {
                    showPairDialog()
                    false
                } else {
                    if (value == false) preferences().edit().putBoolean(RCS_REVIEW_PENDING, true).apply()
                    true
                }
            }
        })
        screen.addPreference(SwitchPreferenceCompat(screen.context).apply {
            key = RCS_ALLOW_SENSITIVE
            setDefaultValue(false)
            setTitle(R.string.rcs_allow_sensitive)
            setSummary(R.string.rcs_allow_sensitive_summary)
            isIconSpaceReserved = false
        })
        statusPreference = Preference(screen.context).apply {
            isIconSpaceReserved = false
            isSelectable = false
            setTitle(R.string.rcs_status)
        }
        screen.addPreference(statusPreference)
        devicePreference = Preference(screen.context).apply {
            setTitle(R.string.rcs_device_id)
            isIconSpaceReserved = false
            isSelectable = false
        }
        screen.addPreference(devicePreference)
        screen.addPreference(Preference(screen.context).apply {
            setTitle(R.string.rcs_resolve_title)
            setSummary(R.string.rcs_resolve_summary)
            isIconSpaceReserved = false
            setOnPreferenceClickListener { showResolveDialog(); true }
        })
        screen.addPreference(Preference(screen.context).apply {
            setTitle(R.string.rcs_disconnect)
            setSummary(R.string.rcs_disconnect_summary)
            isIconSpaceReserved = false
            setOnPreferenceClickListener { showDisconnectDialog(); true }
        })
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
        if (!::statusPreference.isInitialized) return
        val prefs = preferences()
        val label = if (!prefs.getBoolean(RCS_PAIRED, false)) R.string.rcs_status_unpaired
        else if (!prefs.getBoolean(RCS_ENABLED, false)) R.string.rcs_status_disabled
        else when (prefs.getString(RCS_STATUS, "")) {
            "connected" -> R.string.rcs_status_connected
            "offline" -> R.string.rcs_status_offline
            "conflict" -> R.string.rcs_status_conflict
            "expired" -> R.string.rcs_status_expired
            "re_pair" -> R.string.rcs_status_repair
            "storage_error" -> R.string.rcs_status_storage_error
            "unsupported" -> R.string.rcs_status_unsupported
            else -> R.string.rcs_status_waiting
        }
        statusPreference.setSummary(label)
        devicePreference.summary = prefs.getString(RCS_DEVICE_ID, null) ?: getString(R.string.rcs_status_unpaired)
    }

    private fun showPairDialog() {
        val context = requireContext()
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val padding = (24 * resources.displayMetrics.density).toInt()
            setPadding(padding, 0, padding, 0)
        }
        fun field(hint: Int, type: Int) = EditText(context).apply {
            setHint(hint)
            inputType = type
            isSingleLine = true
            content.addView(this)
        }
        val url = field(R.string.rcs_url, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)
        val user = field(R.string.rcs_user, InputType.TYPE_CLASS_TEXT)
        val password = field(R.string.rcs_password, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        val dialog = AlertDialog.Builder(context).setTitle(R.string.rcs_pair_title)
            .setMessage(R.string.rcs_pair_notice).setView(content)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.rcs_pair_action, null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val server = url.text.toString().trim()
                val username = user.text.toString().trim()
                val secret = password.text.toString()
                if (server.isBlank() || username.isBlank() || secret.isEmpty()) return@setOnClickListener
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = false
                background(context, {
                    val deviceId = SyncSetup.pair(it, server, username, secret)
                    preferencesFor(it).edit().putBoolean(RCS_PAIRED, true).putString(RCS_DEVICE_ID, deviceId).putString(RCS_STATUS, "connecting")
                        .putBoolean(RCS_ENABLED, true).putBoolean(RCS_REVIEW_PENDING, false).apply()
                }) { success ->
                    dialog.dismiss()
                    showResult(if (success) R.string.rcs_pair_success else R.string.rcs_pair_failed)
                }
            }
        }
        dialog.show()
    }

    private fun showResolveDialog() {
        AlertDialog.Builder(requireContext()).setTitle(R.string.rcs_resolve_title)
            .setMessage(R.string.rcs_resolve_notice)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.rcs_use_local) { _, _ ->
                background(requireContext(), {
                    SyncSetup.resolveWithLocal(it)
                    preferencesFor(it).edit().putString(RCS_STATUS, "connecting").apply()
                }) { success -> showResult(if (success) R.string.rcs_resolve_success else R.string.rcs_resolve_failed) }
            }.show()
    }

    private fun showDisconnectDialog() {
        AlertDialog.Builder(requireContext()).setTitle(R.string.rcs_disconnect)
            .setMessage(R.string.rcs_disconnect_notice)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.rcs_disconnect) { _, _ ->
                val context = requireContext()
                preferences().edit().putBoolean(RCS_ENABLED, false).apply()
                background(context, {
                    SyncStore(it).clear()
                    preferencesFor(it).edit().putBoolean(RCS_PAIRED, false)
                        .remove(RCS_DEVICE_ID).putBoolean(RCS_REVIEW_PENDING, false).putString(RCS_STATUS, "disabled").apply()
                }) { success -> showResult(if (success) R.string.rcs_disconnect_success else R.string.rcs_disconnect_failed) }
            }.show()
    }

    private fun background(context: Context, work: (Context) -> Unit, done: (Boolean) -> Unit) {
        val app = context.applicationContext
        Thread({
            val success = try { work(app); true } catch (_: Exception) { false }
            main.post { if (isAdded) done(success) }
        }, "RustClipSync-settings").start()
    }

    private fun showResult(message: Int) {
        if (isAdded) AlertDialog.Builder(requireContext()).setMessage(message)
            .setPositiveButton(android.R.string.ok, null).show()
    }

    private fun preferences(): SharedPreferences = preferencesFor(requireContext())
    private fun preferencesFor(context: Context): SharedPreferences = PreferenceManager.getDefaultSharedPreferences(context)
}

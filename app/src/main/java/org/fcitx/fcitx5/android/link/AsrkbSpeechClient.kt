/*
 * Minimal external AIDL client to link with Yanxi (asr-keyboard)
 * for connectivity test via vendorId = "mock".
 */
package org.fcitx.fcitx5.android.link

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import android.util.Log
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.input.FcitxInputMethodService
import org.fcitx.fcitx5.android.R
import timber.log.Timber

object AsrkbSpeechClient {
    private const val TAG = "AsrkbLink"
    private var bound = false
    private var connection: ServiceConnection? = null
    private var remote: IBinder? = null
    private var sessionId: Int = -1
    private var currentState: Int = STATE_IDLE
    @Volatile
    private var holding: Boolean = false
    private var ctxRef: Context? = null
    private var audioJob: kotlinx.coroutines.Job? = null
    private var audioRecord: android.media.AudioRecord? = null
    private val recordingAudioFocusOwner = AsrkbRecordingAudioFocusSessionOwner()
    private var hasPcmFrame: Boolean = false

    fun startHoldSession(service: FcitxInputMethodService) {
        if (bound && remote != null && sessionId > 0) {
            if (!holding) {
                Log.w(TAG, "reset stale session before starting new hold (state=$currentState)")
                unbind()
            } else {
                return
            }
        }
        val ctx = service
        ctxRef = ctx
        holding = true
        hasPcmFrame = false
        val conn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                try {
                    val b = binder ?: throw IllegalStateException("no binder")
                    remote = b
                    // 准备回调 Binder：仅处理 onFinal，其余忽略
                    val cbBinder = object : Binder() {
                        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                            return try {
                                when (code) {
                                    CB_onState -> {
                                        data.enforceInterface(DESCRIPTOR_CB)
                                        val _sid = data.readInt(); val s = data.readInt(); data.readString()
                                        currentState = s
                                        reply?.writeNoException(); true
                                    }
                                    CB_onPartial -> {
                                        data.enforceInterface(DESCRIPTOR_CB)
                                        val _sid = data.readInt(); val text = data.readString() ?: ""
                                        service.lifecycleScope.launch {
                                            service.currentInputConnection?.setComposingText(text, 1)
                                        }
                                        reply?.writeNoException(); true
                                    }
                                    CB_onFinal -> {
                                        data.enforceInterface(DESCRIPTOR_CB)
                                        val _sid = data.readInt()
                                        val text = data.readString() ?: ""
                                        service.lifecycleScope.launch {
                                            service.finishComposing()
                                            service.commitText(text)
                                            // 会话完成：通知覆盖层隐藏
                                            runCatching { VoiceOverlayUiBridge.onDone?.invoke() }
                                            unbind()
                                        }
                                        reply?.writeNoException(); true
                                    }
                                    CB_onError -> {
                                        data.enforceInterface(DESCRIPTOR_CB)
                                        val _sid = data.readInt()
                                        val codeVal = data.readInt()
                                        val msg = data.readString()
                                        toast(ctx, mapCallbackError(ctx, codeVal, msg))
                                        // 出错时也隐藏覆盖层
                                        runCatching { VoiceOverlayUiBridge.onDone?.invoke() }
                                        unbind()
                                        reply?.writeNoException(); true
                                    }
                                    CB_onAmplitude -> {
                                        data.enforceInterface(DESCRIPTOR_CB)
                                        val _sid = data.readInt(); val amp = data.readFloat()
                                        service.lifecycleScope.launch { runCatching { VoiceOverlayUiBridge.onAmplitude?.invoke(amp) } }
                                        reply?.writeNoException(); true
                                    }
                                    IBinder.INTERFACE_TRANSACTION -> { reply?.writeString(DESCRIPTOR_CB); true }
                                    else -> super.onTransact(code, data, reply, flags)
                                }
                            } catch (t: Throwable) {
                                Log.w(TAG, "callback transact handle failed", t)
                                false
                            }
                        }
                    }

                    val data = Parcel.obtain()
                    val reply = Parcel.obtain()
                    var sid = -999
                    try {
                        data.writeInterfaceToken(DESCRIPTOR_SVC)
                        // 推送PCM模式：presence=0 不传配置，服务端按当前设置决定走流/非流
                        data.writeInt(0)
                        data.writeStrongBinder(cbBinder)
                        b.transact(TRANSACTION_startPcmSession, data, reply, 0)
                        reply.readException()
                        sid = reply.readInt()
                    } finally {
                        try { data.recycle() } catch (_: Throwable) {}
                        try { reply.recycle() } catch (_: Throwable) {}
                    }
                    if (sid <= 0) {
                        toast(ctx, mapStartError(ctx, sid))
                        unbind()
                    } else {
                        sessionId = sid; currentState = STATE_RECORDING
                        startAudioStreaming(service)
                    }
                } catch (t: Throwable) {
                    Log.w(TAG, "bind/start failed", t)
                    toast(ctx, "无法连接言犀服务")
                    unbind()
                }
            }

            override fun onServiceDisconnected(name: ComponentName?) { unbind() }
        }
        connection = conn
        // 依次尝试 Pro 包与开源包
        val candidates = listOf(
            ComponentName("com.brycewg.asrkb.pro", "com.brycewg.asrkb.api.ExternalSpeechService"),
            ComponentName("com.brycewg.asrkb", "com.brycewg.asrkb.api.ExternalSpeechService")
        )
        for (c in candidates) {
            val intent = Intent().apply { component = c }
            try {
                bound = ctx.bindService(intent, conn, Context.BIND_AUTO_CREATE)
                if (bound) break
            } catch (t: Throwable) {
                Log.d(TAG, "bind attempt failed: ${c.packageName}", t)
            }
        }
        if (!bound) { toast(ctx, "未找到言犀服务（Pro/开源）"); unbind() }
    }

    fun stopHoldSession() {
        if (!holding) return
        holding = false
        when (currentState) {
            STATE_RECORDING -> if (hasPcmFrame) stopSession() else cancelSession()
            STATE_PROCESSING -> cancelSession()
            else -> cancelSession()
        }
    }

    fun isHolding(): Boolean = holding

    internal fun onServiceDestroyed(service: FcitxInputMethodService) {
        if (ctxRef !== service) return
        unbind()
    }

    // 与服务端保持一致的接口描述符与事务号
    private const val DESCRIPTOR_SVC = "com.brycewg.asrkb.aidl.IExternalSpeechService"
    private const val TRANSACTION_startSession = IBinder.FIRST_CALL_TRANSACTION + 0
    private const val TRANSACTION_stopSession = IBinder.FIRST_CALL_TRANSACTION + 1
    private const val TRANSACTION_cancelSession = IBinder.FIRST_CALL_TRANSACTION + 2
    private const val TRANSACTION_startPcmSession = IBinder.FIRST_CALL_TRANSACTION + 6
    private const val TRANSACTION_writePcm = IBinder.FIRST_CALL_TRANSACTION + 7
    private const val TRANSACTION_finishPcm = IBinder.FIRST_CALL_TRANSACTION + 8

    private const val DESCRIPTOR_CB = "com.brycewg.asrkb.aidl.ISpeechCallback"
    private const val CB_onState = IBinder.FIRST_CALL_TRANSACTION + 0
    private const val CB_onPartial = IBinder.FIRST_CALL_TRANSACTION + 1
    private const val CB_onFinal = IBinder.FIRST_CALL_TRANSACTION + 2
    private const val CB_onError = IBinder.FIRST_CALL_TRANSACTION + 3
    private const val CB_onAmplitude = IBinder.FIRST_CALL_TRANSACTION + 4

    private const val STATE_IDLE = 0
    private const val STATE_RECORDING = 1
    private const val STATE_PROCESSING = 2
    private const val STATE_ERROR = 3

    private fun unbind() {
        val ctx = ctxRef
        stopAudioStreaming()
        try { if (bound && connection != null && ctx != null) ctx.unbindService(connection!!) } catch (_: Throwable) {}
        bound = false
        connection = null
        remote = null
        sessionId = -1
        currentState = STATE_IDLE
        holding = false
        ctxRef = null
        hasPcmFrame = false
    }

    private fun stopSession() {
        val b = remote ?: return
        if (sessionId <= 0) return
        stopAudioStreaming()
        val data = Parcel.obtain(); val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(DESCRIPTOR_SVC)
            data.writeInt(sessionId)
            b.transact(TRANSACTION_finishPcm, data, reply, 0)
            reply.readException()
        } catch (t: Throwable) {
            Log.w(TAG, "stopSession failed", t)
        } finally { try { data.recycle() } catch (_: Throwable) {}; try { reply.recycle() } catch (_: Throwable) {} }
    }

    private fun cancelSession() {
        val b = remote ?: return
        if (sessionId <= 0) return
        stopAudioStreaming()
        val data = Parcel.obtain(); val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(DESCRIPTOR_SVC)
            data.writeInt(sessionId)
            b.transact(TRANSACTION_cancelSession, data, reply, 0)
            reply.readException()
        } catch (t: Throwable) {
            Log.w(TAG, "cancelSession failed", t)
        } finally { try { data.recycle() } catch (_: Throwable) {}; try { reply.recycle() } catch (_: Throwable) {} }
    }

    private fun startAudioStreaming(service: FcitxInputMethodService) {
        stopAudioStreaming()

        // 检查录音权限
        if (ContextCompat.checkSelfPermission(service, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            // 启动权限请求 Activity
            val intent = Intent(service, MicPermissionActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            try {
                service.startActivity(intent)
            } catch (t: Throwable) {
                Log.w(TAG, "Failed to start MicPermissionActivity", t)
                toast(service, service.getString(R.string.asrkb_client_need_mic_permission))
            }
            // 通知覆盖层隐藏
            runCatching { VoiceOverlayUiBridge.onDone?.invoke() }
            unbind()
            return
        }

        acquireRecordingAudioFocusIfEnabled(service)

        audioJob = service.lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val sr = 16000
                val ch = android.media.AudioFormat.CHANNEL_IN_MONO
                val fmt = android.media.AudioFormat.ENCODING_PCM_16BIT
                val minBuf = android.media.AudioRecord.getMinBufferSize(sr, ch, fmt)
                val bytesPerSample = 2
                val chunkBytes = (sr * 200 / 1000) * bytesPerSample
                val bufSize = kotlin.math.max(minBuf, chunkBytes * 2)
                var rec = android.media.AudioRecord(
                    android.media.MediaRecorder.AudioSource.VOICE_RECOGNITION,
                    sr, ch, fmt, bufSize
                )
                audioRecord = rec
                try { rec.startRecording() } catch (t: Throwable) {
                    Log.w(TAG, "AudioRecord start failed, fallback MIC", t)
                    try { rec.release() } catch (_: Throwable) {}
                    rec = android.media.AudioRecord(
                        android.media.MediaRecorder.AudioSource.MIC,
                        sr, ch, fmt, bufSize
                    )
                    audioRecord = rec
                    try { rec.startRecording() } catch (e: Throwable) {
                        Log.e(TAG, "AudioRecord MIC failed", e)
                        service.lifecycleScope.launch {
                            toast(service, service.getString(R.string.asrkb_err_audio_record_failed))
                            runCatching { VoiceOverlayUiBridge.onDone?.invoke() }
                            unbind()
                        }
                        return@launch
                    }
                }

                val chunk = ByteArray(chunkBytes)
                var notifiedRecordingStarted = false
                while (true) {
                    if (sessionId <= 0 || remote == null) break
                    val n = try { audioRecord?.read(chunk, 0, chunk.size) ?: -1 } catch (t: Throwable) { -1 }
                    if (n < 0) break
                    if (n == 0) {
                        delay(10)
                        continue
                    }
                    if (!notifiedRecordingStarted) {
                        notifiedRecordingStarted = true
                        runCatching { VoiceOverlayUiBridge.onRecordingStarted?.invoke() }
                    }
                    writePcmFrame(chunk, n, sr, 1)
                }
            } finally {
                recordingAudioFocusOwner.release()
            }
        }
    }

    private fun stopAudioStreaming() {
        try { audioJob?.cancel() } catch (_: Throwable) {}
        audioJob = null
        try { audioRecord?.stop() } catch (_: Throwable) {}
        try { audioRecord?.release() } catch (_: Throwable) {}
        audioRecord = null
        recordingAudioFocusOwner.release()
    }

    private fun acquireRecordingAudioFocusIfEnabled(service: FcitxInputMethodService) {
        if (!AppPrefs.getInstance().keyboard.asrkbDuckMediaOnRecord.getValue()) {
            Timber.d("ASRKB media avoidance disabled; skip audio focus request")
            return
        }

        val executor = ContextCompat.getMainExecutor(service)
        lateinit var controller: AsrkbRecordingAudioFocusController
        controller = AsrkbRecordingAudioFocusController(service) { loss ->
            Timber.w("ASRKB recording audio focus lost: $loss")
            executor.execute {
                if (recordingAudioFocusOwner.owns(controller) && holding) {
                    stopHoldSession()
                }
            }
        }
        if (!recordingAudioFocusOwner.acquire(controller)) {
            Timber.w("ASRKB recording continues without audio focus")
        }
    }

    private fun writePcmFrame(buf: ByteArray, len: Int, sr: Int, ch: Int) {
        val b = remote ?: return
        if (sessionId <= 0) return
        if (len > 0) hasPcmFrame = true
        val data = Parcel.obtain(); val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(DESCRIPTOR_SVC)
            data.writeInt(sessionId)
            if (len == buf.size) data.writeByteArray(buf) else data.writeByteArray(buf.copyOf(len))
            data.writeInt(sr)
            data.writeInt(ch)
            b.transact(TRANSACTION_writePcm, data, reply, 0)
            reply.readException()
        } catch (t: Throwable) {
            Log.w(TAG, "writePcm transact failed", t)
        } finally { try { data.recycle() } catch (_: Throwable) {}; try { reply.recycle() } catch (_: Throwable) {} }
    }

    private fun toast(ctx: Context, msg: String) {
        try {
            ContextCompat.getMainExecutor(ctx).execute {
                Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()
            }
        } catch (_: Throwable) { }
    }

    private fun mapStartError(ctx: Context, code: Int): String {
        return when (code) {
            -2 -> ctx.getString(R.string.asrkb_err_busy)
            -3 -> ctx.getString(R.string.asrkb_err_feature_disabled)
            // -4（麦克风权限）已不再由服务端触发；保底用通用提示
            -4 -> ctx.getString(R.string.asrkb_err_start_failed_with_code, code)
            else -> ctx.getString(R.string.asrkb_err_start_failed_with_code, code)
        }
    }

    private fun mapCallbackError(ctx: Context, code: Int, msg: String?): String {
        return when (code) {
            // 401（麦克风权限）不再用于推送PCM模式；保底用通用服务错误提示
            401 -> ctx.getString(R.string.asrkb_err_service_error_with_code, code)
            403 -> ctx.getString(R.string.asrkb_err_feature_disabled)
            else -> ctx.getString(R.string.asrkb_err_service_error_with_code, code)
        }
    }
}

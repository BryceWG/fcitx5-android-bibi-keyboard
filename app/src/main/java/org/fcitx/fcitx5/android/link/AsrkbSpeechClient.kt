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
import android.os.SystemClock
import android.util.Log
import android.view.inputmethod.InputConnection
import android.view.inputmethod.EditorInfo
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
    private var ctxRef: FcitxInputMethodService? = null
    private var audioJob: kotlinx.coroutines.Job? = null
    private var audioRecord: android.media.AudioRecord? = null
    private val recordingAudioFocusOwner = AsrkbRecordingAudioFocusSessionOwner()
    private var hasPcmFrame: Boolean = false
    private val editorGeneration = AsrkbEditorGenerationTracker()
    private var sessionEditorGeneration: Long = 0L
    private var editorInputType: Int = 0
    private var editorImeOptions: Int = 0
    private var targetInputConnection: InputConnection? = null
    private var initialInputContext: AsrkbCursorSnapshot? = null
    private var correctionReportingEnabled: Boolean = false
    private val correctionTracker = AsrkbCorrectionTracker()
    private var correctionJob: kotlinx.coroutines.Job? = null
    private var editorEventJob: kotlinx.coroutines.Job? = null
    private var negotiationJob: kotlinx.coroutines.Job? = null
    private val negotiationGate = AsrkbNegotiationGate()

    fun startHoldSession(service: FcitxInputMethodService) {
        if (bound && remote != null && sessionId > 0) {
            if (!holding) {
                Log.w(TAG, "reset stale session before starting new hold (state=$currentState)")
                val report = takeCorrectionReport("next_session")
                if (report != null && dispatchEditReport(report) { startHoldSession(service) }) return
                unbind()
            } else {
                return
            }
        }
        cancelNegotiation()
        val ctx = service
        ctxRef = ctx
        holding = true
        hasPcmFrame = false
        sessionEditorGeneration = editorGeneration.currentGeneration
        prepareInputTarget(service)
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
                                        val callbackSessionId = data.readInt()
                                        val text = data.readString() ?: ""
                                        service.lifecycleScope.launch {
                                            service.finishComposing()
                                            service.commitText(text)
                                            startCorrectionObservation(service, callbackSessionId, text)
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
                        val generation = sessionEditorGeneration
                        val inputConnection = targetInputConnection
                        val inputType = editorInputType
                        val imeOptions = editorImeOptions
                        val token = negotiationGate.begin(sid, generation, inputConnection)
                        negotiationJob = service.lifecycleScope.launch {
                            var negotiatedContext: AsrkbCursorSnapshot? = null
                            val correctionEnabled = negotiateOptionalInputThenContinue(
                                queryRequirements = {
                                    withContext(kotlinx.coroutines.Dispatchers.IO) {
                                        queryInputRequirements(b, sid)
                                    }
                                },
                                attachInputContext = { requirements ->
                                    withContext(kotlinx.coroutines.Dispatchers.IO) {
                                        attachInputContextIfRequested(
                                            binder = b,
                                            token = token,
                                            requirements = requirements,
                                            inputType = inputType,
                                            imeOptions = imeOptions
                                        )?.also { negotiatedContext = it } != null
                                    }
                                },
                                continueOriginalAsr = {
                                    if (negotiationGate.isCurrent(token)) startAudioStreaming(service)
                                }
                            )
                            if (negotiationGate.isCurrent(token)) {
                                initialInputContext = negotiatedContext
                                correctionReportingEnabled = correctionEnabled
                            }
                        }
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
    private const val TRANSACTION_getInputRequirements = IBinder.FIRST_CALL_TRANSACTION + 9
    private const val TRANSACTION_setInputContext = IBinder.FIRST_CALL_TRANSACTION + 10
    private const val TRANSACTION_reportEdit = IBinder.FIRST_CALL_TRANSACTION + 11

    private const val COMMIT_VERIFY_DELAY_MS = 40L
    private const val CORRECTION_POLL_MS = 1_000L

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
        cancelNegotiation()
        correctionJob?.cancel()
        correctionJob = null
        editorEventJob?.cancel()
        editorEventJob = null
        correctionTracker.cancel()
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
        correctionReportingEnabled = false
        targetInputConnection = null
        initialInputContext = null
    }

    private fun prepareInputTarget(service: FcitxInputMethodService) {
        correctionReportingEnabled = false
        correctionTracker.cancel()
        correctionJob?.cancel()
        correctionJob = null
        val info = service.currentInputEditorInfo
        editorInputType = info.inputType
        editorImeOptions = info.imeOptions
        if (sessionEditorGeneration <= 0L ||
            !AsrkbEditorPrivacy.isEligible(editorInputType, editorImeOptions)
        ) {
            targetInputConnection = null
            initialInputContext = null
            return
        }
        targetInputConnection = service.currentInputConnection
        initialInputContext = null
    }

    private fun queryInputRequirements(binder: IBinder, sid: Int): Int? {
        return optionalTransactionInt(binder, TRANSACTION_getInputRequirements) { data ->
            data.writeInt(sid)
        }
    }

    private fun attachInputContextIfRequested(
        binder: IBinder,
        token: AsrkbNegotiationToken,
        requirements: Int,
        inputType: Int,
        imeOptions: Int
    ): AsrkbCursorSnapshot? {
        if (requirements == 0 || !negotiationGate.isCurrent(token)) return null
        val connection = token.connection as? InputConnection ?: return null
        val inputContext = captureInputContext(connection) ?: return null
        if (!negotiationGate.isCurrent(token)) return null
        val attached = optionalTransactionInt(binder, TRANSACTION_setInputContext) { data ->
            data.writeInt(token.sessionId)
            data.writeLong(token.generation)
            data.writeInt(inputType)
            data.writeInt(imeOptions)
            data.writeString(inputContext.beforeCursor)
            data.writeString(inputContext.afterCursor)
        } == 1
        return inputContext.takeIf { attached }
    }

    private fun startCorrectionObservation(
        service: FcitxInputMethodService,
        callbackSessionId: Int,
        finalText: String
    ) {
        stopAudioStreaming()
        runCatching { VoiceOverlayUiBridge.onDone?.invoke() }
        holding = false
        currentState = STATE_IDLE
        val connection = targetInputConnection
        val initial = initialInputContext
        if (!correctionReportingEnabled || callbackSessionId != sessionId || connection == null || initial == null) {
            unbind()
            return
        }

        correctionJob = service.lifecycleScope.launch {
            delay(COMMIT_VERIFY_DELAY_MS)
            if (service.currentInputConnection !== connection ||
                editorGeneration.currentGeneration != sessionEditorGeneration
            ) {
                unbind()
                return@launch
            }
            val committed = withContext(kotlinx.coroutines.Dispatchers.IO) {
                captureInputContext(connection)
            }
            if (committed == null || !correctionTracker.start(
                    sessionId = callbackSessionId,
                    generation = sessionEditorGeneration,
                    initial = initial,
                    finalText = finalText,
                    committed = committed,
                    nowMs = SystemClock.uptimeMillis()
                )
            ) {
                unbind()
                return@launch
            }

            while (true) {
                delay(CORRECTION_POLL_MS)
                if (ctxRef !== service ||
                    service.currentInputConnection !== connection ||
                    editorGeneration.currentGeneration != sessionEditorGeneration
                ) {
                    val report = correctionTracker.finishLast(sessionEditorGeneration, "finish_input")
                    if (report != null && dispatchEditReport(report)) return@launch
                    unbind()
                    return@launch
                }
                val snapshot = withContext(kotlinx.coroutines.Dispatchers.IO) {
                    captureInputContext(connection)
                }
                if (snapshot == null) {
                    val report = correctionTracker.finishLast(sessionEditorGeneration, "finish_input")
                    if (report != null && dispatchEditReport(report)) return@launch
                    unbind()
                    return@launch
                }
                val report = correctionTracker.update(
                    generation = sessionEditorGeneration,
                    snapshot = snapshot,
                    nowMs = SystemClock.uptimeMillis()
                ) ?: continue
                if (!dispatchEditReport(report)) unbind()
                return@launch
            }
        }
    }

    private fun takeCorrectionReport(reason: String): AsrkbCorrectionReport? {
        if (!correctionReportingEnabled) return null
        val snapshot = targetInputConnection?.let(::captureInputContext)
        return if (snapshot != null) {
            correctionTracker.finish(sessionEditorGeneration, snapshot, reason)
        } else {
            correctionTracker.finishLast(sessionEditorGeneration, reason)
        }
    }

    internal fun onStartInput(info: EditorInfo, restarting: Boolean) {
        val previous = editorGeneration.currentGeneration
        val current = editorGeneration.onStartInput(info.asAsrkbEditorIdentity(), restarting)
        if (current == previous) return
        if (bound) {
            val report = takeCorrectionReport("next_session")
            if (report != null && dispatchEditReport(report)) return
            cancelSession()
            unbind()
            return
        }
        cancelNegotiation()
        correctionTracker.cancel()
        correctionJob?.cancel()
        editorEventJob?.cancel()
        correctionReportingEnabled = false
        targetInputConnection = null
        initialInputContext = null
    }

    internal fun onFinishInput() {
        val report = takeCorrectionReport("finish_input")
        val dispatched = report?.let(::dispatchEditReport) == true
        cancelNegotiation()
        editorGeneration.onFinishInput()
        correctionTracker.cancel()
        correctionJob?.cancel()
        editorEventJob?.cancel()
        if (!dispatched && !holding && bound) unbind()
    }

    internal fun onEditorAction() {
        val report = takeCorrectionReport("editor_action") ?: return
        if (!dispatchEditReport(report)) unbind()
    }

    internal fun onEditorEvent(service: FcitxInputMethodService) {
        if (!correctionTracker.isActive() ||
            editorGeneration.currentGeneration != sessionEditorGeneration
        ) {
            return
        }
        val connection = targetInputConnection ?: return
        val generation = sessionEditorGeneration
        val binder = remote ?: return
        if (service.currentInputConnection !== connection) return
        editorEventJob?.cancel()
        editorEventJob = service.lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val snapshot = captureInputContext(connection) ?: return@launch
            val report = correctionTracker.update(
                generation = generation,
                snapshot = snapshot,
                nowMs = SystemClock.uptimeMillis()
            ) ?: return@launch
            reportEdit(binder, report)
            withContext(kotlinx.coroutines.Dispatchers.Main) { unbind() }
        }
    }

    private fun dispatchEditReport(
        report: AsrkbCorrectionReport,
        afterUnbind: () -> Unit = {}
    ): Boolean {
        val binder = remote ?: return false
        val scope = ctxRef?.lifecycleScope ?: return false
        launchAsrkbEditReport(
            scope = scope,
            report = report,
            submit = { reportEdit(binder, it) },
            onComplete = {
                scope.launch(kotlinx.coroutines.Dispatchers.Main) {
                    if (remote === binder) unbind()
                    afterUnbind()
                }
            }
        )
        return true
    }

    private fun reportEdit(binder: IBinder, report: AsrkbCorrectionReport) {
        val result = optionalTransactionInt(binder, TRANSACTION_reportEdit) { data ->
            data.writeInt(report.sessionId)
            data.writeLong(report.generation)
            data.writeString(report.snapshot.beforeCursor)
            data.writeString(report.snapshot.afterCursor)
            data.writeString(report.reason)
        }
        if (result != 1) Log.w(TAG, "correction report rejected: reason=${report.reason}, result=$result")
    }

    private fun cancelNegotiation() {
        negotiationGate.invalidate()
        negotiationJob?.cancel()
        negotiationJob = null
    }

    private fun EditorInfo.asAsrkbEditorIdentity() = AsrkbEditorIdentity(
        packageName = packageName.orEmpty(),
        fieldId = fieldId,
        inputType = inputType,
        imeOptions = imeOptions
    )

    private fun captureInputContext(connection: InputConnection): AsrkbCursorSnapshot? {
        return try {
            val before = connection.getTextBeforeCursor(AsrkbCursorSnapshot.MAX_CONTEXT_CHARS, 0)
                ?.toString() ?: return null
            val after = connection.getTextAfterCursor(AsrkbCursorSnapshot.MAX_CONTEXT_CHARS, 0)
                ?.toString() ?: return null
            AsrkbCursorSnapshot(before, after).bounded()
        } catch (t: Throwable) {
            Log.d(TAG, "input context unavailable", t)
            null
        }
    }

    private inline fun optionalTransactionInt(
        binder: IBinder,
        code: Int,
        fill: (Parcel) -> Unit
    ): Int? {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(DESCRIPTOR_SVC)
            fill(data)
            if (!binder.transact(code, data, reply, 0)) return null
            reply.readException()
            reply.readInt()
        } catch (t: Throwable) {
            Log.d(TAG, "optional ASRKB transaction unsupported: code=$code", t)
            null
        } finally {
            data.recycle()
            reply.recycle()
        }
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

package com.voiceime

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.inputmethodservice.InputMethodService
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Voice-only input method: mic tap → record → LLM transcribe → clipboard + commitText.
 * The input view is inflated once in onCreateInputView; per-field refresh in onStartInputView.
 */
class VoiceImeService : InputMethodService() {

    private enum class State { IDLE, RECORDING, UPLOADING, ERROR }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private var rootView: View? = null
    private var micButton: ImageButton? = null
    private var statusText: TextView? = null
    private var timerText: TextView? = null
    private var levelBar: ProgressBar? = null
    private var stopButton: Button? = null
    private var cancelButton: Button? = null
    private var retryButton: Button? = null
    private var settingsButton: Button? = null

    private var state: State = State.IDLE
    private var recorder: AudioRecorder? = null
    private var transcribeJob: Job? = null
    private var timerRunnable: Runnable? = null
    private var recordStartMs: Long = 0L
    private var lastWav: ByteArray? = null
    private var passwordArmed = false
    private var passwordField = false

    private lateinit var settings: SettingsStore
    private val llm = LlmClient()

    override fun onCreate() {
        super.onCreate()
        settings = SettingsStore(this)
    }

    override fun onCreateInputView(): View {
        rootView?.let { return it }
        val view = layoutInflater.inflate(R.layout.ime_voice_view, null)
        micButton = view.findViewById(R.id.imeMic)
        statusText = view.findViewById(R.id.imeStatus)
        timerText = view.findViewById(R.id.imeTimer)
        levelBar = view.findViewById(R.id.imeLevel)
        stopButton = view.findViewById(R.id.imeStop)
        cancelButton = view.findViewById(R.id.imeCancel)
        retryButton = view.findViewById(R.id.imeRetry)
        settingsButton = view.findViewById(R.id.imeSettings)

        micButton?.setOnClickListener { onMicTapped() }
        stopButton?.setOnClickListener { finishRecording(upload = true) }
        cancelButton?.setOnClickListener { finishRecording(upload = false) }
        retryButton?.setOnClickListener { retryLast() }
        settingsButton?.setOnClickListener { openSettings() }

        rootView = view
        renderState()
        return view
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        passwordField = isPasswordField(info)
        passwordArmed = false
        renderState()
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        super.onFinishInputView(finishingInput)
        cancelWork()
    }

    override fun onWindowHidden() {
        super.onWindowHidden()
        cancelWork()
    }

    override fun onDestroy() {
        cancelWork()
        serviceScope.cancel()
        mainHandler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private fun onMicTapped() {
        if (state == State.RECORDING) {
            finishRecording(upload = true)
            return
        }
        if (passwordField && !passwordArmed) {
            passwordArmed = true
            statusText?.setText(R.string.ime_password_confirm)
            return
        }
        passwordArmed = false
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            val intent = Intent(this, MicPermissionActivity::class.java)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
            return
        }
        if (settings.apiKey.isEmpty()) {
            showError(getString(R.string.ime_error_no_key))
            return
        }
        startRecording()
    }

    private fun startRecording() {
        cancelWork()
        val rec = AudioRecorder(onLevel = { level ->
            mainHandler.post {
                levelBar?.progress = (level * 100).toInt().coerceIn(0, 100)
            }
        })
        recorder = rec
        try {
            rec.start()
        } catch (e: IllegalStateException) {
            recorder = null
            showError(getString(R.string.ime_error_mic))
            return
        }
        state = State.RECORDING
        recordStartMs = System.currentTimeMillis()
        renderState()
        statusText?.setText(R.string.ime_status_recording)
        startTimer()
        // 60 s hard cap in addition to the recorder's own auto-stop.
        mainHandler.postDelayed({ if (state == State.RECORDING) finishRecording(upload = true) }, 60_000)
    }

    private fun finishRecording(upload: Boolean) {
        if (state != State.RECORDING) return
        stopTimer()
        val rec = recorder
        recorder = null
        val wav = try {
            rec?.stop() ?: ByteArray(0)
        } catch (e: Exception) {
            ByteArray(0)
        }
        if (!upload) {
            lastWav = null
            state = State.IDLE
            renderState()
            return
        }
        lastWav = wav
        transcribeJob?.cancel()
        state = State.UPLOADING
        renderState()
        statusText?.setText(R.string.ime_status_uploading)
        val baseUrl = settings.baseUrl
        val apiKey = settings.apiKey
        val model = settings.model
        transcribeJob = serviceScope.launch {
            var transcript: String? = null
            var failure: String? = null
            try {
                transcript = withContext(Dispatchers.IO) {
                    llm.transcribe(wav, baseUrl, apiKey, model)
                }
            } catch (e: TranscribeException) {
                failure = e.message ?: getString(R.string.ime_error_network)
            } finally {
                lastWav = null
            }
            if (transcript != null) {
                deliverTranscript(transcript)
            } else {
                showError(failure ?: getString(R.string.ime_error_network))
            }
        }
    }

    private fun retryLast() {
        val wav = lastWav ?: return
        transcribeJob?.cancel()
        state = State.UPLOADING
        renderState()
        statusText?.setText(R.string.ime_status_uploading)
        val baseUrl = settings.baseUrl
        val apiKey = settings.apiKey
        val model = settings.model
        transcribeJob = serviceScope.launch {
            var transcript: String? = null
            var failure: String? = null
            try {
                transcript = withContext(Dispatchers.IO) {
                    llm.transcribe(wav, baseUrl, apiKey, model)
                }
            } catch (e: TranscribeException) {
                failure = e.message ?: getString(R.string.ime_error_network)
            }
            if (transcript != null) {
                lastWav = null
                deliverTranscript(transcript)
            } else {
                showError(failure ?: getString(R.string.ime_error_network))
            }
        }
    }

    private fun deliverTranscript(text: String) {
        if (text.isBlank()) {
            showError(getString(R.string.ime_error_empty))
            return
        }
        // Clipboard first so the text survives even if commit fails.
        try {
            val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("voice-ime", text))
        } catch (e: Exception) {
            // Clipboard unavailable; still try commitText.
        }
        val ic = currentInputConnection
        if (ic != null) {
            try {
                ic.finishComposingText()
                ic.beginBatchEdit()
                ic.commitText(text, 1)
                ic.endBatchEdit()
            } catch (e: Exception) {
                // Fall through to clipboard-only path with toast.
            }
        }
        state = State.IDLE
        renderState()
        statusText?.setText(R.string.ime_status_idle)
        Toast.makeText(this, R.string.ime_copied_paste, Toast.LENGTH_SHORT).show()
    }

    private fun showError(message: String) {
        state = State.ERROR
        renderState()
        statusText?.text = message
    }

    private fun openSettings() {
        val intent = Intent(this, SettingsActivity::class.java)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(intent)
    }

    private fun cancelWork() {
        stopTimer()
        transcribeJob?.cancel()
        transcribeJob = null
        llm.cancel()
        recorder?.discard()
        recorder = null
        lastWav = null
        if (state == State.RECORDING || state == State.UPLOADING) {
            state = State.IDLE
            mainHandler.post { renderState() }
        }
    }

    private fun renderState() {
        val recording = state == State.RECORDING
        val uploading = state == State.UPLOADING
        val error = state == State.ERROR
        micButton?.isEnabled = !uploading
        stopButton?.visibility = if (recording) View.VISIBLE else View.GONE
        cancelButton?.visibility = if (recording || uploading) View.VISIBLE else View.GONE
        retryButton?.visibility = if (error && lastWav != null) View.VISIBLE else View.GONE
        settingsButton?.visibility = if (error) View.VISIBLE else View.GONE
        if (state == State.IDLE) {
            statusText?.setText(R.string.ime_status_idle)
            timerText?.text = "0:00"
            levelBar?.progress = 0
        }
    }

    private fun startTimer() {
        stopTimer()
        val runnable = object : Runnable {
            override fun run() {
                val elapsed = System.currentTimeMillis() - recordStartMs
                val seconds = (elapsed / 1000).toInt().coerceAtLeast(0)
                timerText?.text = "%d:%02d".format(seconds / 60, seconds % 60)
                if (state == State.RECORDING) {
                    mainHandler.postDelayed(this, 500)
                }
            }
        }
        timerRunnable = runnable
        mainHandler.post(runnable)
    }

    private fun stopTimer() {
        timerRunnable?.let { mainHandler.removeCallbacks(it) }
        timerRunnable = null
        mainHandler.removeCallbacksAndMessages(null)
    }

    private fun isPasswordField(info: EditorInfo?): Boolean {
        if (info == null) return false
        val cls = info.inputType and InputType.TYPE_MASK_CLASS
        if (cls != InputType.TYPE_CLASS_TEXT && cls != InputType.TYPE_CLASS_NUMBER) return false
        val variation = info.inputType and InputType.TYPE_MASK_VARIATION
        return variation == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
            variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD ||
            variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD ||
            variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD
    }
}

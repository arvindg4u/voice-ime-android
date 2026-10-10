package com.voiceime

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Edits base URL, API keys (one per line), model, and custom prompt. Keys never leave this screen except real transcribe. */
class SettingsActivity : AppCompatActivity() {

    private lateinit var settings: SettingsStore
    private lateinit var baseUrlField: TextInputEditText
    private lateinit var apiKeyField: TextInputEditText
    private lateinit var modelField: TextInputEditText
    private lateinit var customPromptField: TextInputEditText
    private lateinit var keyboardStatus: TextView
    private val llm by lazy { LlmClient() }

    companion object {
        private val MODEL_PATTERN = Regex("^[A-Za-z0-9._-]+$")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        setContentView(R.layout.activity_settings)

        settings = SettingsStore(this)
        baseUrlField = findViewById(R.id.settingsBaseUrl)
        apiKeyField = findViewById(R.id.settingsApiKey)
        modelField = findViewById(R.id.settingsModel)
        customPromptField = findViewById(R.id.settingsCustomPrompt)
        keyboardStatus = findViewById(R.id.settingsKeyboardStatus)
        protectApiKeyField()

        baseUrlField.setText(settings.baseUrl)
        apiKeyField.setText(settings.apiKeys.joinToString("\n"))
        modelField.setText(settings.model)
        customPromptField.setText(settings.customPrompt)

        findViewById<MaterialButton>(R.id.settingsSave).setOnClickListener { save() }
        findViewById<MaterialButton>(R.id.settingsTest).setOnClickListener { test() }
        findViewById<MaterialButton>(R.id.settingsEnableKeyboard).setOnClickListener {
            openSystemSettings(
                Settings.ACTION_INPUT_METHOD_SETTINGS,
                R.string.settings_keyboard_settings_unavailable,
            )
        }
        findViewById<MaterialButton>(R.id.settingsEnableFloating).setOnClickListener {
            showFloatingServiceDisclosure()
        }
        val clipToggle = findViewById<MaterialSwitch>(R.id.settingsClipboardToggle)
        clipToggle.isChecked = ClipboardHistoryActivity.isEnabled(this)
        clipToggle.setOnCheckedChangeListener { _, checked ->
            setClipHistoryEnabled(checked)
        }
        findViewById<MaterialButton>(R.id.settingsOpenClipboard).setOnClickListener {
            startActivity(Intent(this, ClipboardHistoryActivity::class.java))
        }
        val smartSwitch = findViewById<MaterialSwitch>(R.id.settingsSmartToggle)
        smartSwitch.isChecked = settings.smartMode
        smartSwitch.setOnCheckedChangeListener { _, checked ->
            settings.smartMode = checked
        }
    }

    override fun onResume() {
        super.onResume()
        findViewById<MaterialSwitch>(R.id.settingsClipboardToggle)?.isChecked =
            ClipboardHistoryActivity.isEnabled(this)
        findViewById<MaterialSwitch>(R.id.settingsSmartToggle)?.isChecked = settings.smartMode
        findViewById<TextView>(R.id.settingsFloatingStatus)?.setText(
            if (isFloatingServiceEnabled()) {
                R.string.settings_floating_status_on
            } else {
                R.string.settings_floating_status_off
            },
        )
        findViewById<MaterialButton>(R.id.settingsEnableFloating)?.setText(
            if (isFloatingServiceEnabled()) {
                R.string.settings_manage_floating
            } else {
                R.string.settings_enable_floating
            },
        )
        if (::keyboardStatus.isInitialized) refreshKeyboardStatus()
    }

    /** Opens an OS settings page with a generic Settings fallback for OEM variants. */
    private fun openSystemSettings(action: String, unavailableMessage: Int) {
        try {
            startActivity(Intent(action))
        } catch (_: ActivityNotFoundException) {
            try {
                startActivity(Intent(Settings.ACTION_SETTINGS))
            } catch (_: ActivityNotFoundException) {
                Toast.makeText(this, unavailableMessage, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun showFloatingServiceDisclosure() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.settings_floating_disclosure_title)
            .setMessage(R.string.settings_floating_disclosure_message)
            .setNegativeButton(android.R.string.cancel, null)
            .setNeutralButton(R.string.settings_floating_disclosure_app_info) { _, _ ->
                openAppInfoSettings()
            }
            .setPositiveButton(R.string.settings_floating_disclosure_continue) { _, _ ->
                openSystemSettings(
                    Settings.ACTION_ACCESSIBILITY_SETTINGS,
                    R.string.settings_accessibility_settings_unavailable,
                )
            }
            .show()
    }

    /** Opens this app's details so sideloaded installs can allow restricted settings. */
    private fun openAppInfoSettings() {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", packageName, null)
        }
        try {
            startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(
                this,
                R.string.settings_app_info_unavailable,
                Toast.LENGTH_LONG,
            ).show()
        }
    }

    /**
     * Reports whether the optional focused-editor AccessibilityService is on.
     * Android settings remain the only place that can enable or disable it.
     */
    private fun isFloatingServiceEnabled(): Boolean {
        val expected = ComponentName(this, FloatingMicAccessibilityService::class.java)
        val enabled = try {
            Settings.Secure.getString(
                contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            ).orEmpty()
        } catch (_: SecurityException) {
            return false
        }
        return enabled.split(':').any { flattened ->
            ComponentName.unflattenFromString(flattened.trim()) == expected
        }
    }

    /** Reports enabled/current keyboard state without reading private settings. */
    private fun refreshKeyboardStatus() {
        val component = ComponentName(this, VoiceImeService::class.java)
        val inputMethodManager = getSystemService(InputMethodManager::class.java)
        val enabled = inputMethodManager?.enabledInputMethodList?.any { info ->
            ComponentName.unflattenFromString(info.id) == component
        } == true
        val currentId = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.DEFAULT_INPUT_METHOD,
        )
        val selected = currentId?.let { ComponentName.unflattenFromString(it) == component } == true

        val message = when {
            selected -> R.string.settings_keyboard_status_selected
            enabled -> R.string.settings_keyboard_status_enabled
            else -> R.string.settings_keyboard_status_disabled
        }
        keyboardStatus.setText(message)
    }

    /**
     * Protects the saved API-key input from obscured taps and, on Android 14+
     * (including Android 16), from accessibility services not designated as
     * assistive accessibility tools. Screen-reader tools remain supported.
     */
    private fun protectApiKeyField() {
        val keyLayout = findViewById<View>(R.id.settingsApiKeyLayout)
        keyLayout.filterTouchesWhenObscured = true
        apiKeyField.filterTouchesWhenObscured = true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            keyLayout.setAccessibilityDataSensitive(View.ACCESSIBILITY_DATA_SENSITIVE_YES)
            apiKeyField.setAccessibilityDataSensitive(View.ACCESSIBILITY_DATA_SENSITIVE_YES)
        }
    }

    private fun setClipHistoryEnabled(checked: Boolean) {
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                ClipboardHistoryActivity.setClipboardEnabled(this@SettingsActivity, checked)
            }
        }
    }

    private fun save() {
        val typed = baseUrlField.text?.toString().orEmpty()
        settings.baseUrl = typed
        val httpsReset = typed.isNotBlank() &&
            typed.trim().substringBefore("://", "").lowercase() != "https" &&
            settings.baseUrl == SettingsStore.DEFAULT_BASE_URL
        settings.apiKeys = apiKeyField.text?.toString().orEmpty()
            .split("\n").map { line -> line.trim() }.filter { line -> line.isNotEmpty() }
        val typedModel = modelField.text?.toString().orEmpty()
        val invalidModel = typedModel.isNotEmpty() && !MODEL_PATTERN.matches(typedModel)
        if (!invalidModel) {
            settings.model = typedModel
        }
        settings.customPrompt = customPromptField.text?.toString().orEmpty()
        baseUrlField.setText(settings.baseUrl)
        apiKeyField.setText(settings.apiKeys.joinToString("\n"))
        modelField.setText(settings.model)
        // The customPrompt write-back IS the truncation feedback: SettingsStore
        // caps at MAX_CUSTOM_PROMPT, so a shortened field shows what was kept.
        customPromptField.setText(settings.customPrompt)
        if (invalidModel) {
            Toast.makeText(this, R.string.settings_invalid_model, Toast.LENGTH_LONG).show()
        } else if (httpsReset) {
            Toast.makeText(this, R.string.settings_https_required, Toast.LENGTH_LONG).show()
        } else {
            Toast.makeText(this, R.string.settings_saved, Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Sends a 1 s 440 Hz test tone to the configured model, trying keys in
     * order, and shows the transcript (first 100 chars, with "key i/n") or
     * the exact error text.
     * Runs off the main thread; the button is re-enabled in all paths.
     */
    private fun test() {
        val testButton = findViewById<MaterialButton>(R.id.settingsTest)
        val keys = apiKeyField.text?.toString().orEmpty()
            .split("\n").map { it.trim() }.filter { it.isNotEmpty() }
        val url = baseUrlField.text?.toString().orEmpty()
        if (keys.isEmpty() || url.isEmpty()) {
            Toast.makeText(this, R.string.settings_missing_key, Toast.LENGTH_SHORT).show()
            return
        }
        val model = modelField.text?.toString().orEmpty().ifEmpty { settings.model }
        val customPrompt = customPromptField.text?.toString().orEmpty()
        testButton.isEnabled = false
        lifecycleScope.launch {
            val message = try {
                val (transcript, usedIndex) = llm.transcribe(
                    AudioRecorder.testToneWav(), keys, url, model,
                    customPrompt = customPrompt,
                    smartMode = settings.smartMode,
                )
                "${transcript.take(100)} (key ${usedIndex + 1}/${keys.size})"
            } catch (e: TranscribeException) {
                e.message.orEmpty()
            } catch (e: Exception) {
                e.message.orEmpty().ifEmpty { "Transcription failed" }
            } finally {
                testButton.isEnabled = true
            }
            Toast.makeText(this@SettingsActivity, message, Toast.LENGTH_LONG).show()
        }
    }
}

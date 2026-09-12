package com.voiceime

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.WindowManager
import android.widget.Button
import android.widget.Switch
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
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
    private val llm = LlmClient()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        setContentView(R.layout.activity_settings)

        settings = SettingsStore(this)
        baseUrlField = findViewById(R.id.settingsBaseUrl)
        apiKeyField = findViewById(R.id.settingsApiKey)
        modelField = findViewById(R.id.settingsModel)
        customPromptField = findViewById(R.id.settingsCustomPrompt)

        baseUrlField.setText(settings.baseUrl)
        apiKeyField.setText(settings.apiKeys.joinToString("\n"))
        modelField.setText(settings.model)
        customPromptField.setText(settings.customPrompt)

        findViewById<Button>(R.id.settingsSave).setOnClickListener { save() }
        findViewById<Button>(R.id.settingsTest).setOnClickListener { test() }
        findViewById<Button>(R.id.settingsEnableKeyboard).setOnClickListener {
            startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
        }
        val clipToggle = findViewById<Switch>(R.id.settingsClipboardToggle)
        clipToggle.isChecked = ClipboardHistoryActivity.isEnabled(this)
        clipToggle.setOnCheckedChangeListener { _, checked ->
            setClipHistoryEnabled(checked)
        }
        findViewById<Button>(R.id.settingsOpenClipboard).setOnClickListener {
            startActivity(Intent(this, ClipboardHistoryActivity::class.java))
        }
        val smartSwitch = findViewById<Switch>(R.id.settingsSmartToggle)
        smartSwitch.isChecked = settings.smartMode
        smartSwitch.setOnCheckedChangeListener { _, checked ->
            settings.smartMode = checked
        }
    }

    override fun onResume() {
        super.onResume()
        findViewById<Switch>(R.id.settingsClipboardToggle)?.isChecked =
            ClipboardHistoryActivity.isEnabled(this)
        findViewById<Switch>(R.id.settingsSmartToggle)?.isChecked = settings.smartMode
    }

    private fun setClipHistoryEnabled(checked: Boolean) {
        getSharedPreferences(
            ClipboardHistoryActivity.PREFS,
            android.content.Context.MODE_PRIVATE,
        ).edit().putBoolean(ClipboardHistoryActivity.KEY_ENABLED, checked).apply()
    }

    private fun save() {
        settings.baseUrl = baseUrlField.text?.toString().orEmpty()
        settings.apiKeys = apiKeyField.text?.toString().orEmpty()
            .split("\n").map { line -> line.trim() }.filter { line -> line.isNotEmpty() }
        settings.model = modelField.text?.toString().orEmpty()
        settings.customPrompt = customPromptField.text?.toString().orEmpty()
        baseUrlField.setText(settings.baseUrl)
        modelField.setText(settings.model)
        customPromptField.setText(settings.customPrompt)
        Toast.makeText(this, R.string.settings_saved, Toast.LENGTH_SHORT).show()
    }

    /**
     * Sends a 1 s 440 Hz test tone to the configured model, trying keys in
     * order, and shows the transcript (first 100 chars, with "key i/n") or
     * the exact error text.
     * Runs off the main thread; the button is re-enabled in all paths.
     */
    private fun test() {
        val testButton = findViewById<Button>(R.id.settingsTest)
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
                val (transcript, usedIndex) = withContext(Dispatchers.IO) {
                    llm.transcribe(
                        AudioRecorder.testToneWav(), keys, url, model,
                        customPrompt = customPrompt,
                        smartMode = settings.smartMode,
                    )
                }
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

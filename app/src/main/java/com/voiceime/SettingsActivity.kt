package com.voiceime

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.WindowManager
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.textfield.TextInputEditText

/** Edits base URL, API key, and model. API key never leaves this screen except real transcribe. */
class SettingsActivity : AppCompatActivity() {

    private lateinit var settings: SettingsStore
    private lateinit var baseUrlField: TextInputEditText
    private lateinit var apiKeyField: TextInputEditText
    private lateinit var modelField: TextInputEditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        setContentView(R.layout.activity_settings)

        settings = SettingsStore(this)
        baseUrlField = findViewById(R.id.settingsBaseUrl)
        apiKeyField = findViewById(R.id.settingsApiKey)
        modelField = findViewById(R.id.settingsModel)

        baseUrlField.setText(settings.baseUrl)
        apiKeyField.setText(settings.apiKey)
        modelField.setText(settings.model)

        findViewById<Button>(R.id.settingsSave).setOnClickListener { save() }
        findViewById<Button>(R.id.settingsTest).setOnClickListener { test() }
        findViewById<Button>(R.id.settingsEnableKeyboard).setOnClickListener {
            startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
        }
    }

    private fun save() {
        settings.baseUrl = baseUrlField.text?.toString().orEmpty()
        settings.apiKey = apiKeyField.text?.toString().orEmpty()
        settings.model = modelField.text?.toString().orEmpty()
        baseUrlField.setText(settings.baseUrl)
        modelField.setText(settings.model)
        Toast.makeText(this, R.string.settings_saved, Toast.LENGTH_SHORT).show()
    }

    /**
     * Local sanity check only: verifies key + URL are present.
     * Never transmits the key anywhere; real transcription happens from the keyboard.
     */
    private fun test() {
        val key = apiKeyField.text?.toString().orEmpty()
        val url = baseUrlField.text?.toString().orEmpty()
        if (key.isEmpty() || url.isEmpty()) {
            Toast.makeText(this, R.string.settings_missing_key, Toast.LENGTH_SHORT).show()
            return
        }
        Toast.makeText(this, R.string.settings_saved_test_hint, Toast.LENGTH_SHORT).show()
    }
}

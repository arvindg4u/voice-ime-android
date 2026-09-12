# Voice IME release rules. R8 full-mode handles the rest (kotlinx.serialization
# not used; org.json is Android framework and needs no rules).

# IME entry point + activities referenced from AndroidManifest.xml.
-keep public class com.voiceime.VoiceImeService
-keep public class com.voiceime.SettingsActivity
-keep public class com.voiceime.MicPermissionActivity
-keep public class com.voiceime.ClipboardHistoryActivity

# Custom view inflated from XML by name.
-keep public class com.voiceime.WaveformView {
    public <init>(...);
}

# EncryptedSharedPreferences / Tink reflection used by androidx.security.
-keep class com.google.crypto.tink.** { *; }
-dontwarn com.google.crypto.tink.**

# OkHttp / Okio service files + platform detection.
-keep class okhttp3.** { *; }
-keep class okio.** { *; }
-dontwarn okhttp3.**
-dontwarn okio.**

# Coroutines debug/production helpers.
-keep class kotlinx.coroutines.** { *; }
-dontwarn kotlinx.coroutines.**

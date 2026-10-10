plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.voiceime"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.voiceime"
        minSdk = 26
        targetSdk = 34
        versionCode = 15
        versionName = "1.6.2"
    }

    signingConfigs {
        create("release") {
            // CI provides these via VOICEIME_* secrets; local builds without
            // them fall back to the debug keystore (assembleRelease unsigned
            // locally is not supported — use CI artifacts).
            val ksFile = System.getenv("VOICEIME_KEYSTORE_FILE")
            if (!ksFile.isNullOrEmpty()) {
                storeFile = file(ksFile)
                storePassword = System.getenv("VOICEIME_STORE_PASSWORD")
                keyAlias = System.getenv("VOICEIME_KEY_ALIAS")
                keyPassword = System.getenv("VOICEIME_KEY_PASSWORD")
            }
        }
    }

    buildFeatures {
        buildConfig = true
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfig = signingConfigs.getByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
        unitTests.isIncludeAndroidResources = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    // Direct pin of the exact tink-android version security-crypto:1.1.0-alpha06
    // already resolves (runtime scope there, so not on our compile classpath).
    // Needed to compile the fenced JVM-test fallback in HistoryCrypto.kt.
    implementation("com.google.crypto.tink:tink-android:1.8.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20231013")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("org.robolectric:robolectric:4.12.1")
    testImplementation("androidx.test:core:1.6.1")
}

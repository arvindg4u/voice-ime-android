package com.voiceime

import android.content.Context
import androidx.security.crypto.EncryptedFile
import androidx.security.crypto.MasterKey
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets

/**
 * Seal/open for history payload bytes.
 *
 * `open` throws on undecryptable input; callers fall back to legacy plaintext parse.
 */
interface HistoryCrypto {
    fun seal(plain: ByteArray): ByteArray
    fun open(sealed: ByteArray): ByteArray
}

/**
 * Identity transform. Test-seam default only — production must never use
 * this: [ClipboardStore.forContext] always wires [EncryptedFileHistoryCrypto].
 */
object NoOpHistoryCrypto : HistoryCrypto {
    override fun seal(plain: ByteArray): ByteArray = plain
    override fun open(sealed: ByteArray): ByteArray = sealed
}

/**
 * [HistoryCrypto] backed by Jetpack Security [EncryptedFile] (Tink
 * AES256-GCM-HKDF-4KB streaming) with an AES256-GCM [MasterKey] — the same
 * master-key pattern as [SettingsStore].
 *
 * Why a fixed scratch file: [EncryptedFile] binds its authentication tag to
 * the file NAME (AAD = `File.getName()` bytes — verified by disassembling
 * `EncryptedFile` from security-crypto-1.1.0-alpha06; see the Task 5
 * report), so sealing through a swap file and renaming it afterwards would
 * break decryption (AAD written as "*.tmp", read back as the primary name).
 * This impl therefore always seals and opens through the SAME fixed scratch
 * filename, so AAD matches in both directions, while [ClipboardStore] keeps
 * its crash-safe swap+rename for the resulting opaque ciphertext bytes.
 *
 * All seal/open traffic serializes on a process-wide lock because every
 * instance shares the fixed scratch filename.
 *
 * Robolectric fallback (JVM tests only): the AndroidKeyStore provider does
 * not exist under `RobolectricTestRunner` (`KeyStoreException:
 * AndroidKeyStore not found`, upstream robolectric/robolectric#1518 still
 * open), so `MasterKey` construction always fails there. In that case — and
 * only when the failure is a missing keystore provider — this class falls
 * back to a process-cached software Tink keyset driving the SAME streaming
 * primitive (`AES256_GCM_HKDF_4KB`) with the SAME filename AAD convention as
 * [EncryptedFile]. The catch is deliberately narrow (`KeyStoreException` /
 * `NoSuchAlgorithmException` / `NoSuchProviderException`): any other failure
 * (locked keystore, corrupted keyset) still throws, and callers stay
 * fail-closed. On a real device the AndroidKeyStore provider is always
 * present, so production traffic never touches the software path; the
 * software key is process-local only and never persisted, which is exactly
 * the right scope for a green test run.
 */
class EncryptedFileHistoryCrypto(
    context: Context,
    scratchDir: File,
) : HistoryCrypto {

    companion object {
        /**
         * Fixed scratch name (AAD stability, see above). Removed after every
         * op; [ClipboardStore] also deletes residue on clear/disable.
         */
        const val SCRATCH_NAME = "clipboard_history.crypto.tmp"

        private val CRYPTO_LOCK = Any()
    }

    private val appContext = context.applicationContext
    private val scratch = File(scratchDir, SCRATCH_NAME)

    private val sealer: CryptoFileSealer by lazy {
        try {
            val masterKey = MasterKey.Builder(appContext)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            val encryptedFile = EncryptedFile.Builder(
                appContext,
                scratch,
                masterKey,
                EncryptedFile.FileEncryptionScheme.AES256_GCM_HKDF_4KB,
            ).build()
            object : CryptoFileSealer {
                override fun sealTo(plain: ByteArray, file: File) {
                    encryptedFile.openFileOutput().use { out -> out.write(plain) }
                }

                override fun openFrom(file: File): ByteArray {
                    encryptedFile.openFileInput().use { input -> return input.readBytes() }
                }
            }
        } catch (e: Exception) {
            if (!isMissingKeystoreProvider(e)) throw e
            SoftwareTinkSealer()
        }
    }

    private fun isMissingKeystoreProvider(e: Throwable): Boolean {
        var cause: Throwable? = e
        while (cause != null) {
            if (cause is java.security.KeyStoreException ||
                cause is java.security.NoSuchAlgorithmException ||
                cause is java.security.NoSuchProviderException
            ) {
                return true
            }
            cause = cause.cause
        }
        return false
    }

    override fun seal(plain: ByteArray): ByteArray {
        synchronized(CRYPTO_LOCK) {
            if (scratch.exists()) scratch.delete()
            sealer.sealTo(plain, scratch)
            try {
                return scratch.readBytes()
            } finally {
                scratch.delete()
            }
        }
    }

    override fun open(sealed: ByteArray): ByteArray {
        synchronized(CRYPTO_LOCK) {
            if (scratch.exists()) scratch.delete()
            scratch.writeBytes(sealed)
            try {
                return sealer.openFrom(scratch)
            } finally {
                scratch.delete()
            }
        }
    }
}

/**
 * JVM-test-only software fallback for [EncryptedFileHistoryCrypto]: Tink
 * `AES256_GCM_HKDF_4KB` streaming AEAD over a process-cached random keyset,
 * with AAD bound to the target file's name bytes — mirroring [EncryptedFile].
 * Never persisted, never reachable when the AndroidKeyStore provider exists.
 */
private interface CryptoFileSealer {
    fun sealTo(plain: ByteArray, file: File)
    fun openFrom(file: File): ByteArray
}

private class SoftwareTinkSealer : CryptoFileSealer {
    override fun sealTo(plain: ByteArray, file: File) {
        SoftwareTinkKeys.aead.newEncryptingStream(
            file.outputStream(),
            file.name.toByteArray(StandardCharsets.UTF_8),
        ).use { out: OutputStream -> out.write(plain) }
    }

    override fun openFrom(file: File): ByteArray {
        SoftwareTinkKeys.aead.newDecryptingStream(
            file.inputStream(),
            file.name.toByteArray(StandardCharsets.UTF_8),
        ).use { input: InputStream -> return input.readBytes() }
    }
}

private object SoftwareTinkKeys {
    val aead: com.google.crypto.tink.StreamingAead by lazy {
        com.google.crypto.tink.streamingaead.StreamingAeadConfig.register()
        // generateNew(KeyTemplate) is deprecated in Tink 1.8.0 in favor of a
        // Parameters-based builder that has no streaming-AEAD Parameters type
        // yet, so this remains the only key-generation path for the primitive.
        @Suppress("DEPRECATION")
        val handle = com.google.crypto.tink.KeysetHandle.generateNew(
            com.google.crypto.tink.streamingaead.StreamingAeadKeyTemplates.AES256_GCM_HKDF_4KB,
        )
        handle.getPrimitive(com.google.crypto.tink.StreamingAead::class.java)
    }
}

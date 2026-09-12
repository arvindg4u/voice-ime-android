package com.voiceime

import android.content.Context
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Offline text-only clipboard history backed by a plain-file JSON store in
 * the app-private no-backup dir (excluded from backup; allowBackup=false
 * already set). Writes go to a .tmp swap file then atomically rename onto
 * the primary, with a .bak copy of the last-good payload as fallback read.
 *
 * Deliberately NOT android.util.AtomicFile: framework classes are stubbed
 * under JVM unit tests (isReturnDefaultValues), so plain java.io keeps the
 * store fully testable. Pattern origin: FUTO android-keyboard clipboard
 * history (AtomicFile + backup/prune/pin-exemption) — reimplemented here
 * from scratch.
 *
 * All IO runs on the caller's thread; callers must use Dispatchers.IO. The
 * in-memory snapshot is a CopyOnWriteArrayList so UI threads read without
 * blocking.
 */
class ClipboardStore private constructor(private val dir: File) {

    companion object {
        const val FILE_NAME = "clipboard_history.json"

        /** Production entry point. */
        fun forContext(context: Context): ClipboardStore =
            ClipboardStore(context.noBackupFilesDir)

        /** Test seam: any writable dir (e.g. a JUnit temp folder). */
        fun forDirectory(dir: File): ClipboardStore = ClipboardStore(dir)
    }

    private val primary = File(dir, FILE_NAME)
    private val backup = File(dir, "$FILE_NAME.bak")
    private val swap = File(dir, "$FILE_NAME.tmp")

    private val entries = CopyOnWriteArrayList<ClipboardEntry>()

    var maxUnpinned: Int = ClipboardPolicy.DEFAULT_MAX_UNPINNED
        set(value) {
            field = ClipboardPolicy.clampMaxUnpinned(value)
        }
    var maxPinned: Int = ClipboardPolicy.DEFAULT_MAX_PINNED
    var ttlMs: Long = ClipboardPolicy.DEFAULT_TTL_MS
    var enabled: Boolean = false

    /** Immutable copy of the current in-memory snapshot (newest first). */
    fun snapshot(): List<ClipboardEntry> = entries.toList()

    /** Loads primary (falls back to .bak), prunes, and refreshes the snapshot. */
    fun load(nowMs: Long = System.currentTimeMillis()): List<ClipboardEntry> {
        val loaded = readFile() ?: emptyList()
        val pruned = ClipboardPolicy.prune(loaded, nowMs, maxUnpinned, maxPinned, ttlMs)
        entries.clear()
        entries.addAll(pruned)
        return snapshot()
    }

    fun add(text: String, nowMs: Long = System.currentTimeMillis(), isSensitive: Boolean = false): List<ClipboardEntry> {
        if (!enabled) return snapshot()
        if (!ClipboardPolicy.shouldCapture(text)) return snapshot()
        val updated = ClipboardPolicy.addClip(
            entries.toList(), text, nowMs, maxUnpinned, maxPinned, ttlMs, isSensitive,
        )
        entries.clear()
        entries.addAll(updated)
        persist()
        return snapshot()
    }

    fun togglePin(entryId: String, nowMs: Long = System.currentTimeMillis()): List<ClipboardEntry> {
        val target = entries.find { it.id == entryId } ?: return snapshot()
        val updated = if (target.isPinned) {
            ClipboardPolicy.unpin(entries.toList(), entryId, nowMs)
        } else {
            val pinnedCount = entries.count { it.isPinned }
            val withPin = entries.map { entry ->
                if (entry.id == entryId) entry.copy(isPinned = true) else entry
            }
            if (pinnedCount + 1 > maxPinned.coerceAtLeast(0)) {
                // FIFO: drop the oldest pinned entry other than the one just pinned.
                val others = withPin.filter { it.isPinned && it.id != entryId }
                val dropId = others.minByOrNull { it.createdAtMs }?.id
                withPin.filter { it.id != dropId }
            } else {
                withPin
            }
        }
        entries.clear()
        entries.addAll(updated)
        persist()
        return snapshot()
    }

    fun deleteAt(entryId: String): List<ClipboardEntry> {
        if (entries.removeIf { it.id == entryId }) persist()
        return snapshot()
    }

    /** Removes every unpinned entry; returns the surviving snapshot. */
    fun clearUnpinned(): List<ClipboardEntry> {
        if (entries.removeIf { !it.isPinned }) persist()
        return snapshot()
    }

    /** Removes everything and deletes the store files. */
    fun clearAll() {
        entries.clear()
        deleteFiles()
    }

    /** Disabling history deletes the store file + .bak. */
    fun setEnabled(value: Boolean, nowMs: Long = System.currentTimeMillis()) {
        enabled = value
        if (!value) {
            entries.clear()
            deleteFiles()
        } else {
            load(nowMs)
        }
    }

    private fun persist() {
        val array = JSONArray()
        for (entry in entries) {
            array.put(
                JSONObject()
                    .put("id", entry.id)
                    .put("text", entry.text)
                    .put("createdAtMs", entry.createdAtMs)
                    .put("isPinned", entry.isPinned)
                    .put("isSensitive", entry.isSensitive)
                    .put("mimeType", entry.mimeType),
            )
        }
        val payload = array.toString().toByteArray(Charsets.UTF_8)
        try {
            // Swap-write then atomic rename; keep last-good .bak first.
            if (primary.exists()) {
                primary.copyTo(backup, overwrite = true)
            }
            swap.writeBytes(payload)
            if (!swap.renameTo(primary)) {
                // Rename failed (e.g. cross-volume): fall back to direct write.
                swap.copyTo(primary, overwrite = true)
                swap.delete()
            }
        } catch (e: Exception) {
            try {
                swap.delete()
            } catch (ignored: Exception) {
            }
        }
    }

    private fun readFile(): List<ClipboardEntry>? {
        val parsed = try {
            if (!primary.exists()) null else parse(primary.readText(Charsets.UTF_8))
        } catch (e: Exception) {
            null
        }
        if (parsed != null) return parsed
        return try {
            if (!backup.exists()) null else parse(backup.readText(Charsets.UTF_8))
        } catch (e: Exception) {
            null
        }
    }

    private fun parse(raw: String): List<ClipboardEntry>? {
        return try {
            val array = JSONArray(raw)
            val out = ArrayList<ClipboardEntry>(array.length())
            for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i) ?: continue
                val text = obj.optString("text", "")
                if (text.isBlank()) continue
                out.add(
                    ClipboardEntry(
                        id = obj.optString("id", "").ifEmpty { java.util.UUID.randomUUID().toString() },
                        text = text,
                        createdAtMs = obj.optLong("createdAtMs", System.currentTimeMillis()),
                        isPinned = obj.optBoolean("isPinned", false),
                        isSensitive = obj.optBoolean("isSensitive", false),
                        mimeType = obj.optString("mimeType", "text/plain").ifEmpty { "text/plain" },
                    ),
                )
            }
            out
        } catch (e: Exception) {
            null
        }
    }

    private fun deleteFiles() {
        try {
            primary.delete()
        } catch (e: Exception) {
        }
        try {
            if (backup.exists()) backup.delete()
        } catch (e: Exception) {
        }
        try {
            if (swap.exists()) swap.delete()
        } catch (e: Exception) {
        }
    }

    /** IO convenience for UI call sites. */
    suspend fun loadAsync(nowMs: Long = System.currentTimeMillis()): List<ClipboardEntry> =
        withContext(Dispatchers.IO) { load(nowMs) }

    suspend fun addAsync(
        text: String,
        nowMs: Long = System.currentTimeMillis(),
        isSensitive: Boolean = false,
    ): List<ClipboardEntry> = withContext(Dispatchers.IO) { add(text, nowMs, isSensitive) }
}

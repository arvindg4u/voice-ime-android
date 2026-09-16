package com.voiceime

import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.Filter
import android.widget.Filterable
import android.widget.ImageButton
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.materialswitch.MaterialSwitch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Full clipboard-history manager (M3 chrome over a ListView adapter).
 * FLAG_SECURE: history never appears in screenshots or the recent-apps thumbnail.
 */
class ClipboardHistoryActivity : AppCompatActivity() {

    private lateinit var store: ClipboardStore
    private lateinit var adapter: EntryAdapter
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        setContentView(R.layout.activity_clipboard_history)

        store = ClipboardStore.forContext(this)
        store.enabled = historyEnabled()
        adapter = EntryAdapter(
            context = this,
            onTap = { entry -> commitEntry(entry) },
            onPin = { entry ->
                scope.launch {
                    val updated = store.togglePinAsync(entry.id)
                    adapter.setEntries(updated)
                    updateEmptyState()
                }
            },
            onDelete = { entry ->
                scope.launch {
                    val updated = store.deleteAtAsync(entry.id)
                    adapter.setEntries(updated)
                    updateEmptyState()
                }
            },
        )

        findViewById<ListView>(R.id.clipList).adapter = adapter

        val enableSwitch = findViewById<MaterialSwitch>(R.id.clipEnable)
        enableSwitch.isChecked = store.enabled
        enableSwitch.setOnCheckedChangeListener { _, checked ->
            scope.launch {
                withContext(Dispatchers.IO) {
                    setClipboardEnabled(this@ClipboardHistoryActivity, checked)
                    // The helper constructs its own store instance, so sync this screen's in-memory snapshot.
                    store.setEnabledAsync(checked)
                }
                adapter.setEntries(store.snapshot())
                updateEmptyState()
            }
        }

        findViewById<EditText>(R.id.clipSearch).addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                adapter.filter.filter(s?.toString().orEmpty())
            }
        })

        updateEmptyState()
        findViewById<MaterialButton>(R.id.clipClearUnpinned).setOnClickListener {
            scope.launch {
                val updated = store.clearUnpinnedAsync()
                adapter.setEntries(updated)
                updateEmptyState()
            }
        }
        findViewById<MaterialButton>(R.id.clipClearAll).setOnClickListener {
            AlertDialog.Builder(this)
                .setMessage(R.string.clipboard_clear_all_confirm)
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    scope.launch {
                        store.clearAllAsync()
                        adapter.setEntries(store.snapshot())
                        updateEmptyState()
                    }
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
    }

    override fun onResume() {
        super.onResume()
        scope.launch {
            val entries = store.loadAsync()
            adapter.setEntries(entries)
            updateEmptyState()
        }
    }

    /** Shows the teaching empty state when the visible list is empty. */
    private fun updateEmptyState() {
        val empty = adapter.isEmpty
        findViewById<View>(R.id.clipEmpty)?.visibility =
            if (empty) View.VISIBLE else View.GONE
        findViewById<ListView>(R.id.clipList)?.visibility =
            if (empty) View.GONE else View.VISIBLE
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun commitEntry(entry: ClipboardEntry) {
        try {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("voice-ime", entry.text))
        } catch (e: Exception) {
            // Clipboard unavailable; nothing to commit.
            return
        }
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.S_V2) {
            Toast.makeText(this, R.string.ime_copied_paste, Toast.LENGTH_SHORT).show()
        }
        finish()
    }

    private fun historyEnabled(): Boolean =
        getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, false)

    companion object {
        const val PREFS = "voice_ime_clipboard"
        const val KEY_ENABLED = "history_enabled"
        const val KEY_MAX_COUNT = "history_max_count"
        const val KEY_TTL_MS = "history_ttl_ms"

        fun isEnabled(context: Context): Boolean =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, false)

        /**
         * Single toggle both screens route through: flips the pref and applies
         * it to the store (disabling deletes history files). Synchronous file
         * IO — callers must be off the main thread.
         */
        fun setClipboardEnabled(context: Context, checked: Boolean) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_ENABLED, checked).apply()
            ClipboardStore.forContext(context).setEnabled(checked)
        }

        fun maxCount(context: Context): Int =
            ClipboardPolicy.clampMaxUnpinned(
                context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .getInt(KEY_MAX_COUNT, ClipboardPolicy.DEFAULT_MAX_UNPINNED),
            )

        fun ttlMs(context: Context): Long =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getLong(KEY_TTL_MS, ClipboardPolicy.DEFAULT_TTL_MS)
    }

    private class EntryAdapter(
        private val context: Context,
        private val onTap: (ClipboardEntry) -> Unit,
        private val onPin: (ClipboardEntry) -> Unit,
        private val onDelete: (ClipboardEntry) -> Unit,
    ) : BaseAdapter(), Filterable {

        private var all: List<ClipboardEntry> = emptyList()
        private var shown: List<ClipboardEntry> = emptyList()
        private val inflater = LayoutInflater.from(context)

        fun setEntries(entries: List<ClipboardEntry>) {
            all = entries
            shown = entries
            filter.filter(currentQuery)
            notifyDataSetChanged()
        }

        private var currentQuery: String = ""

        override fun getCount(): Int = shown.size
        override fun getItem(position: Int): ClipboardEntry = shown[position]
        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = convertView
                ?: inflater.inflate(R.layout.item_clipboard_entry, parent, false)
            val entry = getItem(position)
            view.findViewById<TextView>(R.id.clipText).text = ClipboardPolicy.sanitizePreview(entry)
            view.findViewById<TextView>(R.id.clipState).text = if (entry.isPinned) {
                context.getString(R.string.clipboard_pinned)
            } else {
                context.getString(R.string.clipboard_unpinned)
            }
            val pin = view.findViewById<ImageButton>(R.id.clipPin)
            pin.contentDescription = if (entry.isPinned) {
                context.getString(R.string.clipboard_unpin)
            } else {
                context.getString(R.string.clipboard_pin)
            }
            // Pinned rows glow with the dynamic accent; unpinned stay neutral.
            pin.imageTintList = android.content.res.ColorStateList.valueOf(
                if (entry.isPinned) {
                    androidx.core.content.ContextCompat.getColor(context, R.color.ime_accent)
                } else {
                    androidx.core.content.ContextCompat.getColor(context, android.R.color.darker_gray)
                },
            )
            pin.setOnClickListener { onPin(entry) }
            view.findViewById<ImageButton>(R.id.clipDelete).setOnClickListener { onDelete(entry) }
            view.setOnClickListener { onTap(entry) }
            return view
        }

        override fun getFilter(): Filter = object : Filter() {
            override fun performFiltering(constraint: CharSequence?): FilterResults {
                currentQuery = constraint?.toString().orEmpty()
                val q = currentQuery.trim()
                val result = if (q.isEmpty()) all else all.filter { it.text.contains(q, ignoreCase = true) }
                return FilterResults().apply {
                    values = result
                    count = result.size
                }
            }

            @Suppress("UNCHECKED_CAST")
            override fun publishResults(constraint: CharSequence?, results: FilterResults?) {
                shown = (results?.values as? List<ClipboardEntry>) ?: all
                notifyDataSetChanged()
            }
        }
    }
}

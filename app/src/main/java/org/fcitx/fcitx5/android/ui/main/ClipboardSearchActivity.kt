/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main

import android.app.Activity
import android.content.ClipData
import android.os.Build
import android.os.Bundle
import android.os.PersistableBundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.clipboard.ClipboardManager
import org.fcitx.fcitx5.android.data.clipboard.db.ClipboardEntry
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.input.clipboard.ClipboardAdapter
import org.fcitx.fcitx5.android.utils.clipboardManager
import org.fcitx.fcitx5.android.utils.inputMethodManager

/**
 * Standalone clipboard search panel.
 *
 * The query field is an ordinary [EditText], so it receives input through the regular
 * Android input pipeline (the current IME) instead of the clipboard window's embedded
 * keyboard and candidate strip. Selecting a result copies it to the system clipboard and
 * closes the panel; the input method returns to the previous editor.
 */
class ClipboardSearchActivity : Activity() {

    private val scope: CoroutineScope = MainScope()
    private var searchJob: Job? = null

    private val adapter = SearchAdapter { entry ->
        copyAndFinish(entry)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setGravity(Gravity.TOP)
        window.setLayout(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        setContentView(R.layout.activity_clipboard_search)
        val input = findViewById<EditText>(R.id.clipboard_search_input)
        val results = findViewById<RecyclerView>(R.id.clipboard_search_results)
        val empty = findViewById<TextView>(R.id.clipboard_search_empty)
        results.layoutManager = LinearLayoutManager(this)
        results.adapter = adapter
        input.doAfterTextChanged {
            observe(input.text.toString(), results, empty)
        }
        observe("", results, empty)
        input.requestFocus()
        input.post {
            inputMethodManager.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    private fun observe(query: String, results: RecyclerView, empty: TextView) {
        searchJob?.cancel()
        searchJob = scope.launch {
            ClipboardManager.searchEntries(query).collect { entries ->
                adapter.submitList(entries)
                results.isVisible = entries.isNotEmpty()
                empty.isVisible = entries.isEmpty()
            }
        }
    }

    private fun copyAndFinish(entry: ClipboardEntry) {
        val clip = ClipData.newPlainText("", entry.text)
        if (entry.sensitive && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            clip.description.extras = PersistableBundle().apply {
                putBoolean(ClipboardEntry.IS_SENSITIVE, true)
            }
        }
        clipboardManager.setPrimaryClip(clip)
        Toast.makeText(this, R.string.clipboard_search_copied, Toast.LENGTH_SHORT).show()
        finish()
    }

    override fun onStop() {
        super.onStop()
        finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }

    private class SearchAdapter(
        private val onSelect: (ClipboardEntry) -> Unit
    ) : ListAdapter<ClipboardEntry, SearchAdapter.ViewHolder>(diffCallback) {

        class ViewHolder(val text: TextView) : RecyclerView.ViewHolder(text)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val text = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_clipboard_search, parent, false) as TextView
            return ViewHolder(text)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val entry = getItem(position)
            val maskSensitive = AppPrefs.getInstance().clipboard.clipboardMaskSensitive.getValue()
            holder.text.text = ClipboardAdapter.excerptText(
                entry.text,
                mask = entry.sensitive && maskSensitive,
                lines = 3,
                chars = 160
            )
            holder.text.setOnClickListener { onSelect(entry) }
        }
    }

    companion object {
        private val diffCallback = object : DiffUtil.ItemCallback<ClipboardEntry>() {
            override fun areItemsTheSame(oldItem: ClipboardEntry, newItem: ClipboardEntry) =
                oldItem.id == newItem.id

            override fun areContentsTheSame(oldItem: ClipboardEntry, newItem: ClipboardEntry) =
                oldItem == newItem
        }
    }
}

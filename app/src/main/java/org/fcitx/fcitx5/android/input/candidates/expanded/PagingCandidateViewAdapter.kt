/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2026 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.input.candidates.expanded

import android.view.ViewGroup
import androidx.paging.PagingDataAdapter
import androidx.recyclerview.widget.DiffUtil
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.input.candidates.CandidateItemUi
import org.fcitx.fcitx5.android.input.candidates.CandidateViewHolder

open class PagingCandidateViewAdapter(val theme: Theme) :
    PagingDataAdapter<IndexedCandidate, CandidateViewHolder>(diffCallback) {

    companion object {
        /**
         * Identify items by their absolute index so that unchanged candidates survive
         * a refresh in place, and only the candidates that actually changed are
         * re-bound. This keeps the scrolling position of the expanded candidate
         * window when the list is refreshed (e.g. while a cloud pinyin request is in
         * flight), instead of replacing every item.
         */
        private val diffCallback = object : DiffUtil.ItemCallback<IndexedCandidate>() {
            override fun areItemsTheSame(oldItem: IndexedCandidate, newItem: IndexedCandidate) =
                oldItem.index == newItem.index

            override fun areContentsTheSame(oldItem: IndexedCandidate, newItem: IndexedCandidate) =
                oldItem.candidate == newItem.candidate
        }
    }

    var offset = 0
        private set

    fun refreshWithOffset(offset: Int) {
        this.offset = offset
        refresh()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CandidateViewHolder {
        return CandidateViewHolder(CandidateItemUi(parent.context, theme))
    }

    override fun onBindViewHolder(holder: CandidateViewHolder, position: Int) {
        val item = getItem(position)
        if (item == null) {
            holder.clear()
        } else {
            holder.update(item.index, item.candidate)
        }
    }
}

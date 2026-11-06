/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2024 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.candidates.expanded

import androidx.paging.PagingSource
import androidx.paging.PagingState
import org.fcitx.fcitx5.android.core.CandidateWord
import org.fcitx.fcitx5.android.daemon.FcitxConnection
import timber.log.Timber

/**
 * A candidate together with its absolute index in the whole candidate list.
 *
 * Paging pages are re-loaded from different keys when the list is refreshed, so the
 * adapter position alone is not enough to identify a candidate. Carrying the
 * absolute index keeps [PagingCandidateViewAdapter]'s item identity stable across
 * refreshes (which is what allows scrolling position to be preserved) and also
 * makes `idx` in [org.fcitx.fcitx5.android.input.candidates.CandidateViewHolder]
 * correct regardless of which page happens to be loaded.
 */
data class IndexedCandidate(val index: Int, val candidate: CandidateWord)

class CandidatesPagingSource(val fcitx: FcitxConnection, val total: Int, val offset: Int) :
    PagingSource<Int, IndexedCandidate>() {

    companion object {
        const val PAGE_SIZE = 48
    }

    override suspend fun load(params: LoadParams<Int>): LoadResult<Int, IndexedCandidate> {
        // use candidate index for key, null means load from beginning (with offset)
        val startIndex = params.key ?: offset
        val pageSize = params.loadSize
        Timber.d("getCandidates(offset=$startIndex, limit=$pageSize)")
        val candidates = fcitx.runOnReady {
            getCandidates(startIndex, pageSize)
        }
        val prevKey = if (startIndex > offset) startIndex - pageSize else null
        val nextKey = if (total > 0) {
            if (startIndex + pageSize + 1 >= total) null else startIndex + pageSize
        } else {
            if (candidates.size < pageSize) null else startIndex + pageSize
        }
        return LoadResult.Page(
            candidates.mapIndexed { i, candidate ->
                IndexedCandidate(startIndex + i, candidate)
            },
            prevKey, nextKey
        )
    }

    override fun getRefreshKey(state: PagingState<Int, IndexedCandidate>): Int? {
        // Re-load around the item currently visible instead of jumping back to the
        // beginning, so that a refresh (e.g. the cloud pinyin candidate arriving)
        // does not throw away the user's scrolling position.
        val anchor = state.anchorPosition ?: return null
        val item = state.closestItemToPosition(anchor) ?: return null
        if (item.index <= offset) return offset
        // Keep pages aligned to `offset` so that prepended pages stay contiguous.
        return offset + (item.index - offset) / PAGE_SIZE * PAGE_SIZE
    }

}

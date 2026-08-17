/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.clipboard

import android.content.Context
import android.graphics.Typeface
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.ViewAnimator
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.transition.Fade
import androidx.transition.TransitionManager
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.core.CandidateWord
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.data.theme.ThemeManager
import org.fcitx.fcitx5.android.input.AutoScaleTextView
import org.fcitx.fcitx5.android.input.bar.ui.ToolButton
import splitties.dimensions.dp
import splitties.views.backgroundColor
import splitties.views.dsl.core.Ui
import splitties.views.dsl.core.add
import splitties.views.dsl.core.frameLayout
import splitties.views.dsl.core.horizontalLayout
import splitties.views.dsl.core.lParams
import splitties.views.dsl.core.matchParent
import splitties.views.dsl.core.view
import splitties.views.dsl.core.verticalLayout
import splitties.views.dsl.core.wrapContent
import splitties.views.dsl.recyclerview.recyclerView
import splitties.views.padding
import timber.log.Timber

class ClipboardUi(override val ctx: Context, private val theme: Theme) : Ui {

    val recyclerView = recyclerView {
        addItemDecoration(SpacesItemDecoration(dp(4)))
    }

    val enableUi = ClipboardInstructionUi.Enable(ctx, theme)

    val emptyUi = ClipboardInstructionUi.Empty(ctx, theme)

    val viewAnimator = view(::ViewAnimator) {
        add(recyclerView, lParams(matchParent, matchParent))
        add(emptyUi.root, lParams(matchParent, matchParent))
        add(enableUi.root, lParams(matchParent, matchParent))
    }

    private val keyBorder by ThemeManager.prefs.keyBorder
    private val disableAnimation by AppPrefs.getInstance().advanced.disableAnimation

    val searchEditText = EditText(ctx).apply {
        hint = ctx.getString(R.string.search_clipboard)
        setTextColor(theme.popupTextColor)
        setHintTextColor(theme.popupTextColor and 0x00FFFFFF or 0x80000000.toInt())
        textSize = 16f
        typeface = Typeface.DEFAULT
        setBackgroundColor(theme.popupBackgroundColor)
        val paddingH = dp(12)
        val paddingV = dp(8)
        setPadding(paddingH, paddingV, paddingH, paddingV)
        isVisible = false
    }

    val deleteAllButton = ToolButton(ctx, R.drawable.ic_baseline_delete_sweep_24, theme).apply {
        contentDescription = ctx.getString(R.string.delete_all)
    }

    val searchIcon = ToolButton(ctx, R.drawable.ic_baseline_search_24, theme)

    val extension = horizontalLayout {
        add(searchIcon, lParams(dp(40), dp(40)))
        add(searchEditText, lParams(matchParent, dp(40)) {
            marginStart = dp(4)
        })
    }

    /**
     * Container for the (embedded) Fcitx keyboard, shown below the clipboard grid
     * while searching. Hidden otherwise.
     */
    val keyboardContainer: FrameLayout by lazy {
        frameLayout {
            isVisible = false
        }
    }

    private val candidateContent = horizontalLayout {}

    private val candidateViews = mutableListOf<TextView>()

    /**
     * Horizontal, scrollable row of fcitx candidates shown above the embedded keyboard
     * while searching. Hidden when there are no candidates.
     */
    val candidateStrip: HorizontalScrollView by lazy {
        ctx.view(::HorizontalScrollView) {
            isVisible = false
            isHorizontalScrollBarEnabled = false
            add(candidateContent, lParams(wrapContent, matchParent))
        }
    }

    override val root = verticalLayout {
        if (!keyBorder) {
            backgroundColor = theme.barColor
        }
        // the grid has weight 1 and absorbs all the space that the (fixed height) keyboard
        // and the (wrap content) candidate strip do not use, so the keyboard stays glued to
        // the bottom even when the candidate strip appears/disappears
        add(viewAnimator, lParams(matchParent, 0, weight = 1f))
        add(candidateStrip, lParams(matchParent, wrapContent))
        add(keyboardContainer, lParams(matchParent, 0))
    }

    /**
     * Show/hide the embedded keyboard area. When visible, [keyboardHeightPx] keeps the
     * keyboard its normal (100%) height and the clipboard grid above it expands to fill the
     * rest of the (enlarged) window.
     */
    fun setSearchKeyboardVisible(visible: Boolean, keyboardHeightPx: Int = 0) {
        keyboardContainer.isVisible = visible
        if (visible) {
            val keyboardHeight = if (keyboardHeightPx > 0) keyboardHeightPx else root.height / 2
            keyboardContainer.updateLayoutParams<LinearLayout.LayoutParams> {
                height = keyboardHeight
                weight = 0f
            }
        }
    }

    fun setCandidates(candidates: Array<CandidateWord>, onSelect: (Int) -> Unit = {}) {
        candidateContent.removeAllViews()
        candidateViews.clear()
        if (candidates.isEmpty()) {
            candidateStrip.isVisible = false
            return
        }
        candidates.forEachIndexed { index, word ->
            val tv = view(::AutoScaleTextView).apply {
                scaleMode = AutoScaleTextView.Mode.Proportional
                text = word.textWithComment()
                textSize = 20f // matches CandidateItemUi
                isSingleLine = true
                gravity = android.view.Gravity.CENTER
                setTextColor(theme.candidateTextColor)
                setPadding(dp(12), dp(4), dp(12), dp(4))
                setOnClickListener { onSelect(index) }
            }
            candidateViews.add(tv)
            candidateContent.add(
                tv,
                android.widget.LinearLayout.LayoutParams(
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT
                )
            )
        }
        candidateStrip.isVisible = true
        candidateStrip.scrollTo(0, 0)
    }

    companion object {
        /**
         * Extra height (dp) added to the IME input area while the search keyboard is
         * embedded: the clipboard grid gets this much room on top of the normal keyboard.
         */
        const val SearchGridMaxHeightDp = 160
    }

    fun switchUiByState(state: ClipboardStateMachine.State) {
        Timber.d("Switch clipboard to $state")
        if (!disableAnimation)
            TransitionManager.beginDelayedTransition(root, Fade().apply { duration = 100L })
        when (state) {
            ClipboardStateMachine.State.Normal -> {
                viewAnimator.displayedChild = 0
                setDeleteButtonShown(true)
            }
            ClipboardStateMachine.State.AddMore -> {
                viewAnimator.displayedChild = 1
                setDeleteButtonShown(false)
            }
            ClipboardStateMachine.State.EnableListening -> {
                viewAnimator.displayedChild = 2
                setDeleteButtonShown(false)
            }
        }
    }

    private fun setDeleteButtonShown(enabled: Boolean) {
        deleteAllButton.visibility = if (enabled) View.VISIBLE else View.INVISIBLE
    }
}
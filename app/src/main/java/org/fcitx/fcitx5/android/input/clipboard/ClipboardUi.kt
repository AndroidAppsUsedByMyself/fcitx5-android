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
import android.widget.ViewAnimator
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.transition.Fade
import androidx.transition.TransitionManager
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.data.theme.ThemeManager
import org.fcitx.fcitx5.android.input.bar.ui.ToolButton
import splitties.dimensions.dp
import splitties.views.backgroundColor
import splitties.views.dsl.coordinatorlayout.coordinatorLayout
import splitties.views.dsl.coordinatorlayout.defaultLParams
import splitties.views.dsl.core.Ui
import splitties.views.dsl.core.add
import splitties.views.dsl.core.horizontalLayout
import splitties.views.dsl.core.lParams
import splitties.views.dsl.core.matchParent
import splitties.views.dsl.core.view
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

    override val root = coordinatorLayout {
        if (!keyBorder) {
            backgroundColor = theme.barColor
        }
        add(viewAnimator, defaultLParams(matchParent, matchParent))
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
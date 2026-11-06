/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2024 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.input.candidates.floating

import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.widget.TextView
import androidx.core.text.buildSpannedString
import androidx.core.text.color
import org.fcitx.fcitx5.android.core.CandidateWord
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.input.candidates.CloudBadgeView
import splitties.dimensions.dp
import splitties.views.backgroundColor
import splitties.views.dsl.core.Ui
import splitties.views.dsl.core.add
import splitties.views.dsl.core.frameLayout
import splitties.views.dsl.core.lParams
import splitties.views.dsl.core.textView
import splitties.views.dsl.core.wrapContent

class LabeledCandidateItemUi(
    override val ctx: Context,
    val theme: Theme,
    setupTextView: TextView.() -> Unit
) : Ui {

    private val text = textView {
        setupTextView(this)
    }

    private val basePaddingRight = text.paddingRight

    private val badge = CloudBadgeView(ctx).apply {
        visibility = View.GONE
    }

    override val root = frameLayout {
        add(text, lParams(wrapContent, wrapContent))
        add(badge, lParams(ctx.dp(10), ctx.dp(10)) {
            gravity = Gravity.TOP or Gravity.END
            topMargin = ctx.dp(2)
            rightMargin = ctx.dp(2)
        })
    }

    fun update(candidate: CandidateWord, active: Boolean) {
        val labelFg = if (active) theme.genericActiveForegroundColor else theme.candidateLabelColor
        val fg = if (active) theme.genericActiveForegroundColor else theme.candidateTextColor
        val altFg = if (active) theme.genericActiveForegroundColor else theme.candidateCommentColor
        text.text = buildSpannedString {
            color(labelFg) {
                append(candidate.label)
            }
            color(fg) {
                append(candidate.text)
            }
            if (candidate.comment.isNotBlank()) {
                if (candidate.spaceBetweenComment) {
                    append(" ")
                }
                color(altFg) {
                    append(candidate.comment)
                }
            }
        }
        // Reserve room for the badge so it never covers a single character.
        text.setPadding(
            text.paddingLeft, text.paddingTop,
            basePaddingRight + if (candidate.fromCloud) ctx.dp(12) else 0,
            text.paddingBottom
        )
        badge.badgeColor = altFg
        badge.visibility = if (candidate.fromCloud) View.VISIBLE else View.GONE
        val bg = if (active) theme.genericActiveBackgroundColor else Color.TRANSPARENT
        root.backgroundColor = bg
    }
}

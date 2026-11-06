/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.input.candidates

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View

/**
 * Small cloud silhouette drawn at the top-right corner of cloud pinyin candidates.
 */
class CloudBadgeView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    var badgeColor: Int = Color.GRAY
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w == 0f || h == 0f) return
        paint.color = badgeColor
        canvas.drawCircle(w * 0.32f, h * 0.60f, h * 0.20f, paint)
        canvas.drawCircle(w * 0.51f, h * 0.45f, h * 0.26f, paint)
        canvas.drawCircle(w * 0.70f, h * 0.58f, h * 0.22f, paint)
        canvas.drawRoundRect(
            w * 0.16f, h * 0.60f, w * 0.86f, h * 0.84f,
            h * 0.12f, h * 0.12f, paint
        )
    }
}

/*
 * Copyright (c) 2026 Auxio Project
 * AlbumRatingView.kt is part of Auxio.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package org.oxycblt.auxio.rating

import android.content.Context
import android.graphics.Typeface
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.R as MR
import org.oxycblt.auxio.R
import org.oxycblt.auxio.util.getAttrColorCompat

/**
 * Fork pessoal: seletor compacto de nota de álbum: `0 ★ ★ ★ ★ ★`.
 * - Tocar numa estrela dá aquela nota; tocar no "0" dá nota zero.
 * - Tocar de novo na nota atual remove a nota (fica "sem nota").
 */
class AlbumRatingView
@JvmOverloads
constructor(context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0) :
    LinearLayout(context, attrs, defStyleAttr) {
    private val zero: TextView
    private val stars: List<ImageView>
    private val activeColor = context.getAttrColorCompat(androidx.appcompat.R.attr.colorPrimary)
    private val inactiveColor = context.getAttrColorCompat(MR.attr.colorOnSurfaceVariant)
    private var rating: Int? = null

    /** Chamado quando o usuário escolhe uma nota (null = sem nota). */
    var onRatingChanged: ((Int?) -> Unit)? = null

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        val cell = dp(40)
        val ripple =
            TypedValue()
                .also {
                    context.theme.resolveAttribute(
                        android.R.attr.selectableItemBackgroundBorderless,
                        it,
                        true,
                    )
                }
                .resourceId

        zero =
            TextView(context).apply {
                text = context.getString(R.string.lbl_rating_zero)
                gravity = Gravity.CENTER
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
                setBackgroundResource(ripple)
                contentDescription = context.getString(R.string.desc_rate, 0)
                setOnClickListener { choose(0) }
            }
        addView(zero, LayoutParams(cell, cell))

        stars =
            (1..AlbumRatings.MAX).map { value ->
                ImageView(context).apply {
                    scaleType = ImageView.ScaleType.CENTER
                    setBackgroundResource(ripple)
                    contentDescription = context.getString(R.string.desc_rate, value)
                    setOnClickListener { choose(value) }
                    addView(this, LayoutParams(cell, cell))
                }
            }
        render()
    }

    fun setRating(value: Int?) {
        rating = value
        render()
    }

    private fun choose(value: Int) {
        val next = if (rating == value) null else value
        rating = next
        render()
        onRatingChanged?.invoke(next)
    }

    private fun render() {
        val current = rating
        zero.setTextColor(if (current == 0) activeColor else inactiveColor)
        zero.setTypeface(null, if (current == 0) Typeface.BOLD else Typeface.NORMAL)
        stars.forEachIndexed { i, star ->
            val filled = current != null && i < current
            star.setImageResource(if (filled) R.drawable.ic_star_24 else R.drawable.ic_star_outline_24)
            star.imageTintList = if (filled) activeColor else inactiveColor
        }
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}

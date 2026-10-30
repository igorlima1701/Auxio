/*
 * Copyright (c) 2026 Auxio Project
 * ClearText.kt is part of Auxio.
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

package org.oxycblt.auxio.util

import android.annotation.SuppressLint
import android.view.MotionEvent
import android.widget.EditText
import androidx.core.content.ContextCompat
import androidx.core.widget.doAfterTextChanged
import com.google.android.material.color.MaterialColors
import com.google.android.material.textfield.TextInputLayout
import org.oxycblt.auxio.R

/**
 * Fork pessoal: um "x" no fim de um campo de texto que apaga tudo de uma vez. Aparece só quando há
 * texto. Num campo do Material (TextInputLayout), usa o "x" próprio dele.
 */
object ClearText {
    @SuppressLint("ClickableViewAccessibility")
    fun attach(edit: EditText) {
        // Campo do Material: o próprio layout desenha o "x".
        val til = edit.parent?.parent as? TextInputLayout
        if (til != null) {
            if (til.endIconMode == TextInputLayout.END_ICON_NONE) {
                til.endIconMode = TextInputLayout.END_ICON_CLEAR_TEXT
            }
            return
        }
        val context = edit.context
        val size = (20 * context.resources.displayMetrics.density).toInt()
        val icon =
            ContextCompat.getDrawable(context, R.drawable.ic_close_24)?.mutate()?.apply {
                setBounds(0, 0, size, size)
                setTint(
                    MaterialColors.getColor(
                        edit,
                        com.google.android.material.R.attr.colorOnSurfaceVariant,
                    )
                )
            } ?: return
        if (edit.compoundDrawablePadding == 0) {
            edit.compoundDrawablePadding = (8 * context.resources.displayMetrics.density).toInt()
        }

        fun update() {
            val d = edit.compoundDrawablesRelative
            val show = !edit.text.isNullOrEmpty() && edit.isEnabled
            if ((d[2] === icon) == show) return
            edit.setCompoundDrawablesRelative(d[0], d[1], if (show) icon else null, d[3])
        }
        edit.doAfterTextChanged { update() }
        update()

        edit.setOnTouchListener { _, event ->
            if (edit.compoundDrawablesRelative[2] !== icon) return@setOnTouchListener false
            // Área de toque generosa em volta do "x" (fim do campo).
            val rtl = edit.layoutDirection == android.view.View.LAYOUT_DIRECTION_RTL
            val zone = size + edit.compoundDrawablePadding + size / 2
            val inZone =
                if (rtl) event.x <= edit.paddingStart + zone
                else event.x >= edit.width - edit.paddingEnd - zone
            if (!inZone) return@setOnTouchListener false
            if (event.action == MotionEvent.ACTION_UP) {
                edit.text?.clear()
                edit.requestFocus()
            }
            true
        }
    }
}

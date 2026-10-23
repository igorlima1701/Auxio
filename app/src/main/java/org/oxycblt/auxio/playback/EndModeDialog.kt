/*
 * Copyright (c) 2026 Auxio Project
 * EndModeDialog.kt is part of Auxio.
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

package org.oxycblt.auxio.playback

import android.content.Context
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.view.isVisible
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.oxycblt.auxio.R
import org.oxycblt.auxio.playback.state.RepeatMode
import org.oxycblt.auxio.playback.ui.EndGlyph
import org.oxycblt.auxio.playback.ui.EndGlyphDrawable

/**
 * Fork pessoal: janela "o que fazer quando acaba", com os modos Simples e Avançado (como no Music
 * OLED). Só vale ao tocar em OK.
 */
object EndModeDialog {
    fun show(context: Context) {
        var draft = EndMode.state.value
        val ID_NONE = View.generateViewId()
        val ID_ALL = View.generateViewId()
        val ID_TRACK = View.generateViewId()
        val density = context.resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()
        val textColor =
            MaterialColors.getColor(context, com.google.android.material.R.attr.colorOnSurface, 0)
        val accent =
            MaterialColors.getColor(context, androidx.appcompat.R.attr.colorPrimary, textColor)

        fun radio(group: RadioGroup, label: Int, glyph: EndGlyph, id: Int): RadioButton =
            RadioButton(context).apply {
                this.id = id
                setText(label)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                setPadding(dp(8), dp(10), 0, dp(10))
                compoundDrawablePadding = dp(14)
                setCompoundDrawablesRelativeWithIntrinsicBounds(
                    EndGlyphDrawable(context, listOf(glyph), dp(26), textColor),
                    null,
                    null,
                    null,
                )
                group.addView(this)
            }

        fun title(label: Int) =
            TextView(context).apply {
                setText(label)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
                setTextColor(accent)
                setPadding(0, dp(12), 0, dp(4))
            }

        val root = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        root.setPadding(dp(24), dp(8), dp(24), 0)

        // --- SIMPLES ---
        val simple = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val simpleGroup = RadioGroup(context)
        radio(simpleGroup, R.string.lbl_end_play_next, EndGlyph.SONG_PLAY_NEXT, ID_NONE)
        radio(simpleGroup, R.string.lbl_end_repeat_queue, EndGlyph.QUEUE_REPEAT, ID_ALL)
        radio(simpleGroup, R.string.lbl_end_repeat_song, EndGlyph.SONG_REPEAT, ID_TRACK)
        simple.addView(simpleGroup)

        // --- AVANÇADO ---
        val advanced = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        advanced.addView(title(R.string.lbl_end_song_title))
        val songGroup = RadioGroup(context)
        val songIds = EndMode.SongEnd.entries.associateWith { View.generateViewId() }
        radio(songGroup, R.string.lbl_end_stop, EndGlyph.SONG_STOP, songIds.getValue(EndMode.SongEnd.STOP))
        radio(songGroup, R.string.lbl_end_load_next, EndGlyph.SONG_LOAD_NEXT, songIds.getValue(EndMode.SongEnd.LOAD_NEXT))
        radio(songGroup, R.string.lbl_end_play_next, EndGlyph.SONG_PLAY_NEXT, songIds.getValue(EndMode.SongEnd.PLAY_NEXT))
        radio(songGroup, R.string.lbl_end_repeat_song, EndGlyph.SONG_REPEAT, songIds.getValue(EndMode.SongEnd.REPEAT))
        advanced.addView(songGroup)
        advanced.addView(
            View(context).apply {
                setBackgroundColor(textColor)
                alpha = 0.2f
                layoutParams =
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)).apply {
                        topMargin = dp(8)
                    }
            }
        )
        advanced.addView(title(R.string.lbl_end_queue_title))
        val first =
            CheckBox(context).apply {
                setText(R.string.lbl_end_to_first)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            }
        val reshuffle =
            CheckBox(context).apply {
                setText(R.string.lbl_end_reshuffle)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            }
        advanced.addView(first)
        advanced.addView(reshuffle)
        advanced.addView(
            TextView(context).apply {
                setText(R.string.lbl_end_and_then)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                setPadding(dp(4), dp(8), 0, 0)
            }
        )
        val queueGroup = RadioGroup(context)
        val queueIds = EndMode.QueueEnd.entries.associateWith { View.generateViewId() }
        radio(queueGroup, R.string.lbl_end_stop, EndGlyph.QUEUE_STOP, queueIds.getValue(EndMode.QueueEnd.STOP))
        radio(queueGroup, R.string.lbl_end_next_queue, EndGlyph.QUEUE_NEXT, queueIds.getValue(EndMode.QueueEnd.NEXT_QUEUE))
        radio(queueGroup, R.string.lbl_end_repeat_queue, EndGlyph.QUEUE_REPEAT, queueIds.getValue(EndMode.QueueEnd.REPEAT))
        advanced.addView(queueGroup)

        // --- Simples | Avançado ---
        val toggle =
            MaterialButtonToggleGroup(context).apply {
                isSingleSelection = true
                isSelectionRequired = true
            }
        val simpleButton =
            MaterialButton(context, null, com.google.android.material.R.attr.materialButtonOutlinedStyle)
                .apply {
                    id = View.generateViewId()
                    setText(R.string.lbl_end_simple)
                }
        val advancedButton =
            MaterialButton(context, null, com.google.android.material.R.attr.materialButtonOutlinedStyle)
                .apply {
                    id = View.generateViewId()
                    setText(R.string.lbl_end_advanced)
                }
        toggle.addView(
            simpleButton,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        toggle.addView(
            advancedButton,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )

        root.addView(simple)
        root.addView(advanced)

        // Rodapé fixo (fora da rolagem): Simples|Avançado e, embaixo, Padrão, Cancelar e OK, todos
        // sempre à vista.
        fun action(label: Int, filled: Boolean) =
            MaterialButton(
                    context,
                    null,
                    if (filled) com.google.android.material.R.attr.materialButtonStyle
                    else com.google.android.material.R.attr.materialButtonOutlinedStyle,
                )
                .apply { setText(label) }
        val reset = action(R.string.lbl_end_reset, false)
        val cancel = action(R.string.lbl_cancel, false)
        val ok = action(R.string.lbl_ok, true)
        val actions =
            LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                for ((i, b) in listOf(reset, cancel, ok).withIndex()) {
                    addView(
                        b,
                        LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                            if (i > 0) marginStart = dp(8)
                        },
                    )
                }
            }
        val footer =
            LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(20), dp(8), dp(20), dp(16))
                addView(
                    toggle,
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    ),
                )
                addView(
                    actions,
                    LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                        )
                        .apply { topMargin = dp(4) },
                )
            }
        // A lista rola sozinha, até 60% da tela; o rodapé fica sempre embaixo.
        val scroll =
            object : ScrollView(context) {
                override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                    val max = (resources.displayMetrics.heightPixels * 0.6f).toInt()
                    super.onMeasure(
                        widthMeasureSpec,
                        View.MeasureSpec.makeMeasureSpec(max, View.MeasureSpec.AT_MOST),
                    )
                }
            }
        scroll.addView(root)
        val content =
            LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(scroll)
                addView(footer)
            }

        var dialog: AlertDialog? = null
        var binding = false

        fun render() {
            binding = true
            simple.isVisible = !draft.advanced
            advanced.isVisible = draft.advanced
            toggle.check(if (draft.advanced) advancedButton.id else simpleButton.id)
            simpleGroup.check(
                when (draft.repeatMode) {
                    RepeatMode.NONE -> ID_NONE
                    RepeatMode.ALL -> ID_ALL
                    RepeatMode.TRACK -> ID_TRACK
                }
            )
            songGroup.check(songIds.getValue(draft.songEnd))
            queueGroup.check(queueIds.getValue(draft.queueEnd))
            first.isChecked = draft.toFirst
            reshuffle.isChecked = draft.reshuffle
            binding = false
        }

        simpleGroup.setOnCheckedChangeListener { _, id ->
            if (binding) return@setOnCheckedChangeListener
            val mode =
                when (id) {
                    ID_ALL -> RepeatMode.ALL
                    ID_TRACK -> RepeatMode.TRACK
                    else -> RepeatMode.NONE
                }
            draft = EndMode.simpleState(draft, mode)
        }
        songGroup.setOnCheckedChangeListener { _, id ->
            if (binding) return@setOnCheckedChangeListener
            songIds.entries.firstOrNull { it.value == id }?.let { draft = draft.copy(songEnd = it.key) }
        }
        queueGroup.setOnCheckedChangeListener { _, id ->
            if (binding) return@setOnCheckedChangeListener
            queueIds.entries.firstOrNull { it.value == id }?.let { draft = draft.copy(queueEnd = it.key) }
        }
        first.setOnCheckedChangeListener { _, checked ->
            if (!binding) draft = draft.copy(toFirst = checked)
        }
        reshuffle.setOnCheckedChangeListener { _, checked ->
            if (!binding) draft = draft.copy(reshuffle = checked)
        }
        toggle.addOnButtonCheckedListener { _, id, checked ->
            if (binding || !checked) return@addOnButtonCheckedListener
            draft = draft.copy(advanced = id == advancedButton.id)
            render()
        }

        val dlg = MaterialAlertDialogBuilder(context).setView(content).create()
        dialog = dlg
        reset.setOnClickListener {
            draft = EndMode.DEFAULT.copy(advanced = draft.advanced)
            render()
        }
        cancel.setOnClickListener { dlg.dismiss() }
        ok.setOnClickListener {
            EndMode.set(draft)
            dlg.dismiss()
        }
        render()
        dlg.show()
    }

}

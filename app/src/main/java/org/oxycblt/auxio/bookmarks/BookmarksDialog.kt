/*
 * Copyright (c) 2026 Auxio Project
 * BookmarksDialog.kt is part of Auxio.
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

package org.oxycblt.auxio.bookmarks

import android.content.Context
import android.content.Intent
import android.graphics.Paint
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.oxycblt.auxio.R
import org.oxycblt.auxio.music.resolve
import org.oxycblt.musikr.Song

/** Fork pessoal: janela "Marcadores/Notas" do player. */
object BookmarksDialog {
    /** O que a janela precisa do player. */
    interface Player {
        fun positionMs(): Long

        fun seekTo(ms: Long)

        fun togglePlaying()

        fun isPlaying(): Boolean

        /** Pede acesso à pasta da música. */
        fun requestAccess(song: Song)
    }

    fun show(context: Context, scope: CoroutineScope, song: Song, player: Player) {
        scope.launch {
            val sheet = withContext(Dispatchers.IO) { Bookmarks.load(context, song) }
            showSheet(context, scope, song, player, sheet)
        }
    }

    private fun dp(context: Context, v: Int) = (v * context.resources.displayMetrics.density).toInt()

    private fun showSheet(
        context: Context,
        scope: CoroutineScope,
        song: Song,
        player: Player,
        sheet: Bookmarks.Sheet,
    ) {
        val accent = MaterialColors.getColor(context, androidx.appcompat.R.attr.colorPrimary, 0)
        var dialog: AlertDialog? = null
        fun reopen() {
            dialog?.dismiss()
            show(context, scope, song, player)
        }
        fun persist(marks: List<Bookmarks.Mark>) {
            scope.launch {
                val error = withContext(Dispatchers.IO) { Bookmarks.save(context, song, sheet, marks) }
                if (error != null) {
                    Toast.makeText(
                            context,
                            context.getString(R.string.fmt_bookmark_error, error),
                            Toast.LENGTH_LONG,
                        )
                        .show()
                }
                reopen()
            }
        }

        val root =
            LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(context, 24), dp(context, 8), dp(context, 24), dp(context, 8))
            }
        root.addView(
            TextView(context).apply {
                text = sheet.fileName
                alpha = 0.7f
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            }
        )

        // Botões: Adicionar | ...
        val bar =
            LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(context, 12), 0, dp(context, 8))
            }
        val add =
            MaterialButton(context).apply {
                setText(R.string.lbl_bookmark_add)
                setIconResource(R.drawable.ic_add_24)
                setOnClickListener {
                    if (sheet.needsAccess) {
                        player.requestAccess(song)
                        return@setOnClickListener
                    }
                    edit(context, player, null) { mark ->
                        persist(sheet.marks + mark)
                    }
                }
            }
        bar.addView(add)
        bar.addView(View(context), LinearLayout.LayoutParams(0, 1, 1f))
        val more =
            MaterialButton(context, null, com.google.android.material.R.attr.materialButtonOutlinedStyle)
                .apply {
                    setIconResource(R.drawable.ic_more_24)
                    iconPadding = 0
                    setOnClickListener { v ->
                        PopupMenu(context, v).apply {
                            menu.add(0, 1, 0, R.string.lbl_bookmark_info)
                            menu.add(0, 2, 1, R.string.lbl_bookmark_share).isEnabled = sheet.exists
                            menu.add(0, 3, 2, R.string.lbl_bookmark_save_as).isEnabled = sheet.exists
                            menu.add(0, 4, 3, R.string.lbl_bookmark_delete).isEnabled = sheet.exists
                            setOnMenuItemClickListener { item ->
                                when (item.itemId) {
                                    1 -> info(context, song, sheet)
                                    2 -> share(context, song, sheet)
                                    3 -> saveAs(context, scope, song, sheet)
                                    4 ->
                                        confirmDelete(context) {
                                            scope.launch {
                                                val error =
                                                    withContext(Dispatchers.IO) {
                                                        Bookmarks.delete(context, song)
                                                    }
                                                if (error != null) {
                                                    Toast.makeText(
                                                            context,
                                                            context.getString(R.string.fmt_bookmark_error, error),
                                                            Toast.LENGTH_LONG,
                                                        )
                                                        .show()
                                                }
                                                reopen()
                                            }
                                        }
                                }
                                true
                            }
                            show()
                        }
                    }
                }
        bar.addView(more)
        root.addView(bar)

        if (sheet.needsAccess) {
            root.addView(
                TextView(context).apply {
                    setText(R.string.lng_bookmark_need_access)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                    setPadding(0, dp(context, 4), 0, dp(context, 4))
                }
            )
        }
        if (sheet.header.isNotBlank()) {
            root.addView(
                TextView(context).apply {
                    text = sheet.header
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                    setTextIsSelectable(true)
                    setPadding(0, dp(context, 4), 0, dp(context, 8))
                }
            )
        }
        if (sheet.marks.isEmpty() && !sheet.needsAccess) {
            root.addView(
                TextView(context).apply {
                    setText(R.string.lng_bookmark_empty)
                    alpha = 0.7f
                    setPadding(0, dp(context, 4), 0, dp(context, 4))
                }
            )
        }
        for ((i, mark) in sheet.marks.withIndex()) {
            val row =
                LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    setPadding(0, dp(context, 10), 0, dp(context, 10))
                    val ripple = TypedValue()
                    context.theme.resolveAttribute(android.R.attr.selectableItemBackground, ripple, true)
                    setBackgroundResource(ripple.resourceId)
                    setOnClickListener {
                        player.seekTo(mark.timeMs)
                        if (!player.isPlaying()) player.togglePlaying()
                        dialog?.dismiss()
                    }
                    setOnLongClickListener {
                        MaterialAlertDialogBuilder(context)
                            .setItems(
                                arrayOf<CharSequence>(
                                    context.getString(R.string.lbl_bookmark_edit),
                                    context.getString(R.string.lbl_bookmark_remove),
                                )
                            ) { _, which ->
                                if (which == 0) {
                                    edit(context, player, mark) { edited ->
                                        persist(sheet.marks.toMutableList().also { it[i] = edited })
                                    }
                                } else {
                                    persist(sheet.marks.filterIndexed { j, _ -> j != i })
                                }
                            }
                            .show()
                        true
                    }
                }
            row.addView(
                TextView(context).apply {
                    text = Bookmarks.time(mark.timeMs)
                    setTextColor(accent)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                    minWidth = dp(context, 56)
                }
            )
            row.addView(
                TextView(context).apply {
                    text = mark.note.ifBlank { "—" }
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
            )
            root.addView(row)
        }

        dialog =
            MaterialAlertDialogBuilder(context)
                .setTitle(R.string.lbl_bookmarks)
                .setView(ScrollView(context).apply { addView(root) })
                .setPositiveButton(R.string.lbl_ok, null)
                .show()
    }

    /** Janela de adicionar/editar marcador: tempo com − e +, nota e controles do player. */
    private fun edit(
        context: Context,
        player: Player,
        mark: Bookmarks.Mark?,
        onSave: (Bookmarks.Mark) -> Unit,
    ) {
        var timeMs = mark?.timeMs ?: (player.positionMs() / 1000) * 1000
        val accent = MaterialColors.getColor(context, androidx.appcompat.R.attr.colorPrimary, 0)
        val root =
            LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(context, 24), dp(context, 16), dp(context, 24), 0)
            }

        fun small(text: String, onClick: () -> Unit) =
            MaterialButton(context, null, com.google.android.material.R.attr.materialButtonOutlinedStyle)
                .apply {
                    this.text = text
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
                    setOnClickListener { onClick() }
                }

        val timeView =
            TextView(context).apply {
                gravity = Gravity.CENTER
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
            }
        fun renderTime() {
            timeView.text = Bookmarks.time(timeMs)
        }
        renderTime()
        val timeRow =
            LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
        val minus =
            small("−") {
                timeMs = (timeMs - 1000).coerceAtLeast(0)
                renderTime()
            }
        val plus =
            small("+") {
                timeMs += 1000
                renderTime()
            }
        timeRow.addView(minus, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        timeRow.addView(timeView, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.4f))
        timeRow.addView(plus, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(timeRow)

        val noteLayout =
            TextInputLayout(
                    context,
                    null,
                    com.google.android.material.R.attr.textInputOutlinedStyle,
                )
                .apply {
                    hint = context.getString(R.string.lbl_bookmark_note)
                    setPadding(0, dp(context, 12), 0, 0)
                }
        val note =
            TextInputEditText(noteLayout.context).apply {
                setText(mark?.note ?: "")
                inputType =
                    InputType.TYPE_CLASS_TEXT or
                        InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                        InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                minLines = 3
                gravity = Gravity.TOP or Gravity.START
            }
        noteLayout.addView(note)
        org.oxycblt.auxio.util.ClearText.attach(note)
        root.addView(noteLayout)

        // Controles: voltar 5 s, tocar/pausar, avançar 5 s e o tempo atual (tocar copia para cima).
        val controls =
            LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(context, 12), 0, 0)
            }
        controls.addView(
            small("⏪") { player.seekTo((player.positionMs() - 5000).coerceAtLeast(0)) },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        val playButton = small(if (player.isPlaying()) "⏸" else "▶") {}
        playButton.setOnClickListener {
            player.togglePlaying()
            playButton.postDelayed({ playButton.text = if (player.isPlaying()) "⏸" else "▶" }, 150)
        }
        controls.addView(playButton, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        controls.addView(
            small("⏩") { player.seekTo(player.positionMs() + 5000) },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        val now =
            TextView(context).apply {
                setTextColor(accent)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
                paintFlags = paintFlags or Paint.UNDERLINE_TEXT_FLAG
                gravity = Gravity.CENTER
                setOnClickListener {
                    timeMs = (player.positionMs() / 1000) * 1000
                    renderTime()
                }
            }
        controls.addView(now, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.3f))
        root.addView(controls)

        val handler = Handler(Looper.getMainLooper())
        val tick =
            object : Runnable {
                override fun run() {
                    now.text = "⏱ " + Bookmarks.time(player.positionMs())
                    handler.postDelayed(this, 500)
                }
            }
        tick.run()

        MaterialAlertDialogBuilder(context)
            .setView(ScrollView(context).apply { addView(root) })
            .setPositiveButton(if (mark == null) R.string.lbl_bookmark_add else R.string.lbl_bookmark_save) { _, _ ->
                onSave(Bookmarks.Mark(timeMs, note.text?.toString().orEmpty().trim()))
            }
            .setNegativeButton(R.string.lbl_cancel, null)
            .setOnDismissListener { handler.removeCallbacks(tick) }
            .show()
        note.requestFocus()
    }

    private fun info(context: Context, song: Song, sheet: Bookmarks.Sheet) {
        val folder = song.path.directory.resolve(context)
        val message =
            context.getString(
                R.string.fmt_bookmark_info,
                sheet.fileName,
                folder,
                sheet.marks.size,
                if (sheet.exists) context.getString(R.string.lbl_bookmark_yes)
                else context.getString(R.string.lbl_bookmark_no),
            )
        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.lbl_bookmark_info)
            .setMessage(message)
            .setPositiveButton(R.string.lbl_ok, null)
            .show()
    }

    private fun share(context: Context, song: Song, sheet: Bookmarks.Sheet) {
        val text =
            song.name.resolve(context) + "\n\n" + Bookmarks.format(sheet.marks, sheet.header)
        val intent =
            Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, sheet.fileName)
                putExtra(Intent.EXTRA_TEXT, text)
            }
        context.startActivity(Intent.createChooser(intent, context.getString(R.string.lbl_bookmark_share)))
    }

    private fun saveAs(context: Context, scope: CoroutineScope, song: Song, sheet: Bookmarks.Sheet) {
        val base = sheet.fileName.substringBeforeLast('.')
        val input =
            EditText(context).apply {
                setText("$base (cópia)")
                setSelection(text.length)
                isSingleLine = true
            }
        org.oxycblt.auxio.util.ClearText.attach(input)
        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.lbl_bookmark_save_as)
            .setView(
                FrameLayout(context).apply {
                    setPadding(dp(context, 24), dp(context, 8), dp(context, 24), 0)
                    addView(input)
                }
            )
            .setPositiveButton(R.string.lbl_ok) { _, _ ->
                val name = input.text.toString().trim()
                if (name.isEmpty()) return@setPositiveButton
                scope.launch {
                    val error = withContext(Dispatchers.IO) { Bookmarks.saveAs(context, song, sheet, name) }
                    Toast.makeText(
                            context,
                            if (error == null) context.getString(R.string.lng_bookmark_saved)
                            else context.getString(R.string.fmt_bookmark_error, error),
                            Toast.LENGTH_LONG,
                        )
                        .show()
                }
            }
            .setNegativeButton(R.string.lbl_cancel, null)
            .show()
    }

    private fun confirmDelete(context: Context, onYes: () -> Unit) {
        MaterialAlertDialogBuilder(context)
            .setMessage(R.string.lng_bookmark_delete_confirm)
            .setPositiveButton(R.string.lbl_bookmark_delete) { _, _ -> onYes() }
            .setNegativeButton(R.string.lbl_cancel, null)
            .show()
    }
}

/*
 * Copyright (c) 2026 Auxio Project
 * SleepTimerDialog.kt is part of Auxio.
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
import android.graphics.Paint
import android.os.SystemClock
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.text.DateFormat
import java.util.Date
import org.oxycblt.auxio.R
import org.oxycblt.auxio.music.resolve
import org.oxycblt.musikr.Song

/**
 * Fork pessoal: janela do timer (o reloginho do player). Parar depois de um tempo, depois de N
 * músicas ou depois de uma música da fila.
 */
object SleepTimerDialog {
    /** [queue]/[index]: a fila atual e a posição da música tocando, para "depois de uma música". */
    fun show(context: Context, queue: List<Song>, index: Int) {
        val density = context.resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()
        val accent =
            MaterialColors.getColor(context, androidx.appcompat.R.attr.colorPrimary, 0)
        var dialog: AlertDialog? = null

        val root =
            LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(24), dp(20), dp(24), dp(8))
            }

        fun outlined(text: String, accentText: Boolean = false, onClick: () -> Unit) =
            MaterialButton(context, null, com.google.android.material.R.attr.materialButtonOutlinedStyle)
                .apply {
                    this.text = text
                    isAllCaps = false
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
                    if (accentText) setTextColor(accent)
                    setOnClickListener {
                        onClick()
                    }
                }

        fun row(vararg buttons: View) =
            LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                for (b in buttons) {
                    addView(
                        b,
                        LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                            marginStart = dp(4)
                            marginEnd = dp(4)
                        },
                    )
                }
            }

        fun done(message: String) {
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
            dialog?.dismiss()
        }

        fun startTime(ms: Long) {
            SleepTimer.startTime(ms)
            val at = System.currentTimeMillis() + ms
            done(
                context.getString(
                    R.string.fmt_timer_time_set,
                    DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(at)),
                )
            )
        }

        fun startSongs(n: Int) {
            SleepTimer.startSongs(n)
            done(context.resources.getQuantityString(R.plurals.fmt_timer_songs_set, n, n))
        }

        // Situação atual (se houver timer ligado).
        val status = statusText(context, queue)
        if (status != null) {
            root.addView(
                TextView(context).apply {
                    text = status
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                    gravity = Gravity.CENTER
                    setPadding(0, 0, 0, dp(8))
                }
            )
            root.addView(
                row(
                    outlined(context.getString(R.string.lbl_timer_cancel), accentText = true) {
                        SleepTimer.cancel()
                        StopAfterSong.set(null)
                        done(context.getString(R.string.lng_timer_cancelled))
                    }
                )
            )
            root.addView(divider(context, dp(16)))
        }

        // Depois de um tempo.
        root.addView(header(context, R.string.lbl_timer_time, accent, dp(8)))
        root.addView(
            row(
                outlined("0:30:00") { startTime(30 * 60_000L) },
                outlined("0:15:00") { startTime(15 * 60_000L) },
            )
        )
        root.addView(
            row(
                outlined("h : mm ?") {
                    ask(context, R.string.lbl_timer_time, "h:mm", InputType.TYPE_CLASS_DATETIME or InputType.TYPE_DATETIME_VARIATION_TIME) { text ->
                        val ms = parseTime(text)
                        if (ms == null || ms <= 0) {
                            Toast.makeText(context, R.string.lng_timer_invalid_time, Toast.LENGTH_LONG)
                                .show()
                        } else {
                            startTime(ms)
                        }
                    }
                }
            )
        )
        root.addView(divider(context, dp(16)))

        // Depois de N músicas.
        root.addView(header(context, R.string.lbl_timer_songs, accent, dp(8)))
        root.addView(
            row(
                outlined("N = 5") { startSongs(5) },
                outlined("N = 10") { startSongs(10) },
                outlined("N = ?") {
                    ask(context, R.string.lbl_timer_songs, "N", InputType.TYPE_CLASS_NUMBER) { text ->
                        val n = text.trim().toIntOrNull()
                        if (n == null || n <= 0) {
                            Toast.makeText(context, R.string.lng_timer_invalid_songs, Toast.LENGTH_LONG)
                                .show()
                        } else {
                            startSongs(n)
                        }
                    }
                },
            )
        )

        // Depois de uma música específica.
        root.addView(
            TextView(context).apply {
                setText(R.string.lbl_timer_specific)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
                paintFlags = paintFlags or Paint.UNDERLINE_TEXT_FLAG
                gravity = Gravity.CENTER
                setPadding(0, dp(16), 0, dp(8))
                setOnClickListener {
                    pickSong(context, queue, index) { song ->
                        StopAfterSong.set(song.uid)
                        done(
                            context.getString(R.string.fmt_timer_after_song, song.name.resolve(context))
                        )
                    }
                }
            }
        )

        dialog =
            MaterialAlertDialogBuilder(context)
                .setView(android.widget.ScrollView(context).apply { addView(root) })
                .show()
    }

    /** Texto do que está ligado agora, ou null. */
    private fun statusText(context: Context, queue: List<Song>): String? {
        val lines = mutableListOf<String>()
        when (val mode = SleepTimer.mode.value) {
            is SleepTimer.Mode.Time -> {
                val leftMs = (mode.atMs - SystemClock.elapsedRealtime()).coerceAtLeast(0)
                val at = System.currentTimeMillis() + leftMs
                val minutes = ((leftMs + 59_999) / 60_000).toInt()
                lines +=
                    context.getString(
                        R.string.fmt_timer_status_time,
                        DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(at)),
                        minutes,
                    )
            }
            is SleepTimer.Mode.Songs ->
                lines +=
                    context.resources.getQuantityString(
                        R.plurals.fmt_timer_status_songs,
                        mode.left,
                        mode.left,
                    )
            null -> {}
        }
        StopAfterSong.target.value?.let { uid ->
            val name = queue.firstOrNull { it.uid == uid }?.name?.resolve(context)
            if (name != null) lines += context.getString(R.string.fmt_timer_after_song, name)
        }
        return lines.takeIf { it.isNotEmpty() }?.joinToString("\n")
    }

    private fun header(context: Context, label: Int, color: Int, pad: Int) =
        TextView(context).apply {
            setText(label)
            setTextColor(color)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            gravity = Gravity.CENTER
            setPadding(0, pad, 0, pad)
        }

    private fun divider(context: Context, margin: Int) =
        View(context).apply {
            setBackgroundColor(
                MaterialColors.getColor(context, com.google.android.material.R.attr.colorOnSurface, 0)
            )
            alpha = 0.2f
            layoutParams =
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 2).apply {
                    topMargin = margin
                    bottomMargin = margin / 2
                }
        }

    /** "1:30" = 1h30; "45" = 45 min; "0:05" = 5 min. */
    fun parseTime(text: String): Long? {
        val t = text.trim().replace('.', ':').replace('h', ':').trimEnd(':')
        if (t.isEmpty()) return null
        val parts = t.split(':')
        return when (parts.size) {
            1 -> parts[0].toLongOrNull()?.let { it * 60_000L }
            2 -> {
                val h = parts[0].ifEmpty { "0" }.toLongOrNull() ?: return null
                val m = parts[1].toLongOrNull() ?: return null
                if (m >= 60) null else (h * 60 + m) * 60_000L
            }
            else -> null
        }
    }

    private fun ask(
        context: Context,
        title: Int,
        hint: String,
        type: Int,
        onOk: (String) -> Unit,
    ) {
        val input =
            EditText(context).apply {
                this.hint = hint
                inputType = type
                imeOptions = EditorInfo.IME_ACTION_DONE
                isSingleLine = true
                gravity = Gravity.CENTER
            }
        org.oxycblt.auxio.util.ClearText.attach(input)
        val pad = context.resources.getDimensionPixelSize(R.dimen.spacing_large)
        MaterialAlertDialogBuilder(context)
            .setTitle(title)
            .setView(FrameLayout(context).apply {
                setPadding(pad, pad / 2, pad, 0)
                addView(input)
            })
            .setPositiveButton(R.string.lbl_ok) { _, _ -> onOk(input.text.toString()) }
            .setNegativeButton(R.string.lbl_cancel, null)
            .show()
        input.requestFocus()
    }

    /** Lista da fila, a partir da música atual, para escolher depois de qual parar. */
    private fun pickSong(context: Context, queue: List<Song>, index: Int, onPick: (Song) -> Unit) {
        val start = index.coerceIn(0, (queue.size - 1).coerceAtLeast(0))
        val songs = queue.drop(start)
        if (songs.isEmpty()) return
        val labels =
            songs
                .mapIndexed { i, song ->
                    val prefix = if (i == 0) "▶ " else "${i + 1}. "
                    prefix + song.name.resolve(context)
                }
                .toTypedArray<CharSequence>()
        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.lbl_timer_specific)
            .setItems(labels) { _, which -> onPick(songs[which]) }
            .setNegativeButton(R.string.lbl_cancel, null)
            .show()
    }
}

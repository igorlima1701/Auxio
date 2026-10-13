/*
 * Copyright (c) 2026 Auxio Project
 * QueueTabs.kt is part of Auxio.
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

package org.oxycblt.auxio.playback.queue

import android.content.Context
import android.graphics.Typeface
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.oxycblt.auxio.R
import org.oxycblt.auxio.util.getAttrColorCompat
import org.oxycblt.auxio.util.showToast
import org.oxycblt.musikr.Song

/** Fork pessoal: a pergunta "em qual fila?" do "Adicionar à fila". */
object QueueTabs {
    private fun songCount(context: Context, n: Int) =
        if (n == 1) context.getString(R.string.fmt_queue_one_song)
        else context.getString(R.string.fmt_queue_songs, n)

    /** Pergunta em qual fila pôr as músicas pendentes (ou se cria uma nova). */
    fun showPicker(context: Context, songs: List<Song>, multiQueue: MultiQueue): AlertDialog {
        val info = multiQueue.info.value
        val items = mutableListOf<String>()
        for (i in 0 until info.count) {
            val base = info.names.getOrElse(i) { context.getString(R.string.lbl_queue_n, i + 1) }
            val playing = if (i == info.active) " " + context.getString(R.string.lng_queue_playing) else ""
            items += "$base$playing · ${songCount(context, info.sizes.getOrElse(i) { 0 })}"
        }
        val canCreate = info.count < MultiQueue.MAX
        if (canCreate) items += context.getString(R.string.lbl_queue_new)
        val title =
            context.getString(R.string.lbl_add_to_queue) +
                " (" + songCount(context, songs.size) + ")"
        val dialog =
            MaterialAlertDialogBuilder(context)
                .setTitle(title)
                .setItems(items.toTypedArray()) { _, which ->
                    if (which < info.count) {
                        if (multiQueue.addPendingTo(which)) {
                            context.showToast(
                                context.getString(R.string.lng_queue_added_to, info.names.getOrElse(which) { "" })
                            )
                        }
                    } else {
                        val n = multiQueue.addPendingToNew()
                        if (n > 0) {
                            context.showToast(
                                context.getString(R.string.lng_queue_created_named, multiQueue.nameOf(n - 1))
                            )
                        } else {
                            context.showToast(R.string.err_queue_max)
                        }
                    }
                }
                .setNegativeButton(R.string.lbl_cancel, null)
                .setOnDismissListener { multiQueue.clearPending() }
                .show()
        dialog.window?.setWindowAnimations(0)
        return dialog
    }
}

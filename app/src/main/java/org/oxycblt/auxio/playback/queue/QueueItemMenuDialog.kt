/*
 * Copyright (c) 2026 Auxio Project
 * QueueItemMenuDialog.kt is part of Auxio.
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

import android.app.Dialog
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.os.bundleOf
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.FragmentManager
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import com.google.android.material.R as MR
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.divider.MaterialDivider
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import org.oxycblt.auxio.R
import org.oxycblt.auxio.music.MusicRepository
import org.oxycblt.auxio.music.MusicViewModel
import org.oxycblt.auxio.music.resolve
import org.oxycblt.auxio.playback.PlaybackViewModel
import org.oxycblt.auxio.playback.StopAfterSong
import org.oxycblt.auxio.tagedit.SongInfoDialog
import org.oxycblt.auxio.util.getAttrColorCompat
import org.oxycblt.auxio.util.showToast
import org.oxycblt.musikr.Music
import org.oxycblt.musikr.Song

/**
 * Fork pessoal: opções de uma música da fila, no estilo do Musicolet (abre segurando o dedo na
 * música).
 */
@AndroidEntryPoint
class QueueItemMenuDialog : DialogFragment() {
    @Inject lateinit var musicRepository: MusicRepository
    private val queueModel: QueueViewModel by viewModels({ requireParentFragment() })
    private val playbackModel: PlaybackViewModel by activityViewModels()
    private val musicModel: MusicViewModel by activityViewModels()

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val args = requireArguments()
        val index = args.getInt(ARG_INDEX)
        val song =
            args.getString(ARG_UID)?.let(Music.UID::fromString)?.let {
                musicRepository.find(it) as? Song
            }
        val context = requireContext()
        val builder = MaterialAlertDialogBuilder(context)
        if (song == null) {
            return builder.setMessage(R.string.lbl_info_unknown).create()
        }
        builder.setTitle(song.name.resolve(context))

        val list =
            LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, dp(8), 0, dp(8))
            }
        fun option(icon: Int, label: String, action: () -> Unit) {
            list.addView(row(icon, label) {
                action()
                dismiss()
            })
        }
        fun divider() {
            list.addView(
                MaterialDivider(context).apply {
                    layoutParams =
                        LinearLayout.LayoutParams(
                                LinearLayout.LayoutParams.MATCH_PARENT,
                                LinearLayout.LayoutParams.WRAP_CONTENT,
                            )
                            .apply { setMargins(dp(64), dp(4), dp(24), dp(4)) }
                }
            )
        }

        val activity = requireActivity()
        option(R.drawable.ic_info_24, getString(R.string.lbl_song_info)) {
            SongInfoDialog.show(activity, song)
        }
        option(R.drawable.ic_remove_circle_24, getString(R.string.lbl_queue_remove)) {
            queueModel.removeQueueDataItem(index)
        }
        divider()
        option(R.drawable.ic_skip_next_24, getString(R.string.lbl_play_after_current)) {
            // Na fila, a música é movida para logo depois da que está tocando.
            val current = queueModel.index.value
            if (index != current) {
                val target = if (index > current) current + 1 else current
                queueModel.moveQueueDataItems(index, target)
            }
        }
        option(R.drawable.ic_queue_24, getString(R.string.lbl_add_to_queue)) {
            playbackModel.addToQueue(song)
        }
        option(R.drawable.ic_playlist_add_24, getString(R.string.lbl_add_to_playlists)) {
            musicModel.addToPlaylist(song)
        }
        divider()
        val stopping = StopAfterSong.target.value == song.uid
        option(
            R.drawable.ic_stop_after_24,
            getString(if (stopping) R.string.lbl_stop_after_cancel else R.string.lbl_stop_after),
        ) {
            StopAfterSong.toggle(song.uid)
        }

        return builder.setView(list).create()
    }

    private fun row(icon: Int, label: String, onClick: () -> Unit) =
        LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(56)
            setPadding(dp(24), 0, dp(24), 0)
            val ripple = TypedValue()
            context.theme.resolveAttribute(android.R.attr.selectableItemBackground, ripple, true)
            setBackgroundResource(ripple.resourceId)
            setOnClickListener { onClick() }
            addView(
                ImageView(context).apply {
                    setImageResource(icon)
                    imageTintList = context.getAttrColorCompat(MR.attr.colorOnSurfaceVariant)
                },
                LinearLayout.LayoutParams(dp(24), dp(24)),
            )
            addView(
                TextView(context).apply {
                    text = label
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
                    setPadding(dp(24), 0, 0, 0)
                }
            )
        }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val TAG = "auxio.fork.QUEUE_ITEM_MENU"
        private const val ARG_UID = "uid"
        private const val ARG_INDEX = "index"

        fun show(manager: FragmentManager, song: Song, index: Int) {
            QueueItemMenuDialog()
                .apply { arguments = bundleOf(ARG_UID to song.uid.toString(), ARG_INDEX to index) }
                .show(manager, TAG)
        }
    }
}

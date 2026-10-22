/*
 * Copyright (c) 2026 Auxio Project
 * SongInfoDialog.kt is part of Auxio.
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

package org.oxycblt.auxio.tagedit

import android.graphics.Typeface
import android.os.Bundle
import android.text.format.Formatter
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.os.bundleOf
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.divider.MaterialDivider
import dagger.hilt.android.AndroidEntryPoint
import java.text.DateFormat
import java.util.Date as JDate
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.oxycblt.auxio.R
import org.oxycblt.auxio.databinding.DialogSongInfoBinding
import org.oxycblt.auxio.detail.DetailViewModel
import org.oxycblt.auxio.music.MusicRepository
import org.oxycblt.auxio.music.resolve
import org.oxycblt.auxio.playback.PlayHistory
import org.oxycblt.auxio.playback.formatDurationMs
import org.oxycblt.auxio.ui.ViewBindingMaterialDialogFragment
import org.oxycblt.musikr.Music
import org.oxycblt.musikr.Song
import org.oxycblt.musikr.tag.edit.TagEditor

/**
 * Fork pessoal: "Informações da música", no estilo do Music OLED. Abre pelo menu das músicas (listas,
 * álbum, player) e pelo menu da fila.
 */
@AndroidEntryPoint
class SongInfoDialog : ViewBindingMaterialDialogFragment<DialogSongInfoBinding>() {
    @Inject lateinit var musicRepository: MusicRepository
    private val detailModel: DetailViewModel by activityViewModels()

    private val song: Song?
        get() =
            requireArguments().getString(ARG_UID)?.let(Music.UID::fromString)?.let {
                musicRepository.find(it) as? Song
            }

    override fun onCreateBinding(inflater: LayoutInflater) = DialogSongInfoBinding.inflate(inflater)

    override fun onConfigDialog(builder: AlertDialog.Builder) {
        super.onConfigDialog(builder)
        builder
            .setTitle(R.string.lbl_song_info)
            .setIcon(R.drawable.ic_info_24)
            .setPositiveButton(R.string.lbl_info_done, null)
            .setNeutralButton(R.string.lbl_edit_tags) { _, _ ->
                song?.let { TagEditDialog.show(requireActivity(), it) }
            }
    }

    override fun onBindingCreated(binding: DialogSongInfoBinding, savedInstanceState: Bundle?) {
        super.onBindingCreated(binding, savedInstanceState)
        val song = song
        if (song == null) {
            dismiss()
            return
        }
        binding.infoCover.bind(song)
        binding.infoFileName.text = song.path.name ?: song.uri.toString()
        render(binding, song, emptyMap())
        // Compositor, letrista, comentário e detalhes técnicos vêm direto do arquivo.
        viewLifecycleOwner.lifecycleScope.launch {
            val info =
                withContext(Dispatchers.IO) { TagEditor(requireContext().applicationContext).info(song.uri) }
            render(requireBinding(), song, info)
        }
    }

    private fun render(binding: DialogSongInfoBinding, song: Song, info: Map<String, String>) {
        val context = requireContext()
        val rows = binding.infoRows
        rows.removeAllViews()
        val unknown = getString(R.string.lbl_info_unknown)
        fun text(value: String?) = value?.takeIf { it.isNotBlank() } ?: unknown

        row(rows, R.string.lbl_info_location, song.path.directory.resolve(context))
        divider(rows)
        row(rows, R.string.lbl_info_title, song.name.resolve(context))
        row(rows, R.string.lbl_info_album, song.album.name.resolve(context)) {
            detailModel.showAlbum(song.album)
            dismiss()
        }
        row(rows, R.string.lbl_info_artist, song.artists.joinToString("; ") { it.name.resolve(context) }) {
            detailModel.showArtist(song)
            dismiss()
        }
        row(
            rows,
            R.string.lbl_info_album_artist,
            song.album.artists.joinToString("; ") { it.name.resolve(context) },
        ) {
            detailModel.showArtist(song.album)
            dismiss()
        }
        row(rows, R.string.lbl_info_composer, text(info["COMPOSER"]))
        val genre = song.genres.firstOrNull()
        row(rows, R.string.lbl_info_genre, text(song.genres.joinToString("; ") { it.name.resolve(context) }),
            if (genre != null) {
                {
                    detailModel.showGenre(genre)
                    dismiss()
                }
            } else {
                null
            },
        )
        row(rows, R.string.lbl_info_lyricist, text(info["LYRICIST"]))
        row(rows, R.string.lbl_info_track, song.track?.toString() ?: unknown)
        row(rows, R.string.lbl_info_disc, song.disc?.number?.toString() ?: unknown)
        row(rows, R.string.lbl_info_year, song.date?.toString() ?: unknown)
        row(rows, R.string.lbl_info_comment, text(info["COMMENT"]))
        // Fork pessoal: a nota do álbum como está gravada no arquivo (tag ALBUMRATING).
        row(
            rows,
            R.string.lbl_info_rating_tag,
            info["ALBUMRATING"]?.takeIf { it.isNotBlank() }
                ?: if (info.isEmpty()) "…" else getString(R.string.lng_info_rating_tag_none),
        )
        // Fork pessoal: as coleções do álbum como estão gravadas no arquivo (tag ALBUMCOLLECTION).
        row(
            rows,
            R.string.lbl_info_collection_tag,
            info["ALBUMCOLLECTION"]?.takeIf { it.isNotBlank() }
                ?: if (info.isEmpty()) "…" else getString(R.string.lng_info_rating_tag_none),
        )
        divider(rows)
        row(rows, R.string.lbl_info_duration, song.durationMs.formatDurationMs(true))
        row(rows, R.string.lbl_info_bitrate, getString(R.string.fmt_bitrate, song.bitrateKbps))
        row(rows, R.string.lbl_info_sample_rate, getString(R.string.fmt_sample_rate, song.sampleRateHz))
        row(rows, R.string.lbl_info_bits, text(info["BITS"]))
        row(rows, R.string.lbl_info_format, song.format.resolve(context))
        row(rows, R.string.lbl_info_encoding, text(info["ENCODING"]))
        row(rows, R.string.lbl_info_channels, text(info["CHANNELS"]))
        row(rows, R.string.lbl_info_size, Formatter.formatFileSize(context, song.size))
        divider(rows)
        fun db(value: Float?) = value?.let { String.format("%+.2f dB", it) } ?: unknown
        row(
            rows,
            R.string.lbl_info_replaygain,
            getString(
                R.string.fmt_info_rg,
                db(song.replayGainAdjustment.track),
                db(song.replayGainAdjustment.album),
            ),
        )
        divider(rows)
        val dateFormat = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
        row(rows, R.string.lbl_info_added, dateFormat.format(JDate(song.addedMs)))
        row(rows, R.string.lbl_info_modified, dateFormat.format(JDate(song.modifiedMs)))
        val history = PlayHistory.get(song.uid)
        row(
            rows,
            R.string.lbl_info_last_played,
            history?.let { dateFormat.format(JDate(it.lastPlayedMs)) } ?: getString(R.string.lng_info_never),
        )
        row(
            rows,
            R.string.lbl_info_played,
            when (val count = history?.count ?: 0) {
                1 -> getString(R.string.fmt_info_played_once)
                else -> getString(R.string.fmt_info_played, count)
            },
        )
        if (history != null) {
            rows.addView(
                MaterialButton(context, null, androidx.appcompat.R.attr.borderlessButtonStyle)
                    .apply {
                        setText(R.string.lbl_info_clear_history)
                        setOnClickListener {
                            PlayHistory.clear(song.uid)
                            render(requireBinding(), song, info)
                        }
                    }
            )
        }
    }

    private fun row(
        rows: LinearLayout,
        label: Int,
        value: String,
        onClick: (() -> Unit)? = null,
    ) {
        val context = rows.context
        val container =
            LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, dp(8), 0, dp(4))
            }
        container.addView(
            TextView(context).apply {
                text = if (onClick != null) "${getString(label)}  ↗" else getString(label)
                setTypeface(typeface, Typeface.BOLD)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            }
        )
        container.addView(
            TextView(context).apply {
                text = value
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                setTextIsSelectable(onClick == null)
                alpha = 0.75f
            }
        )
        if (onClick != null) {
            val ripple = TypedValue()
            context.theme.resolveAttribute(android.R.attr.selectableItemBackground, ripple, true)
            container.setBackgroundResource(ripple.resourceId)
            container.setOnClickListener { onClick() }
        }
        rows.addView(container)
    }

    private fun divider(rows: LinearLayout) {
        rows.addView(
            MaterialDivider(rows.context).apply {
                layoutParams =
                    LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT,
                        )
                        .apply { setMargins(0, dp(8), 0, dp(4)) }
            }
        )
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val TAG = "auxio.fork.SONG_INFO"
        private const val ARG_UID = "uid"

        fun show(activity: FragmentActivity, song: Song) {
            SongInfoDialog()
                .apply { arguments = bundleOf(ARG_UID to song.uid.toString()) }
                .show(activity.supportFragmentManager, TAG)
        }
    }
}

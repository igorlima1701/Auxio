/*
 * Copyright (c) 2026 Auxio Project
 * TagEditDialog.kt is part of Auxio.
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

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.os.bundleOf
import androidx.core.view.isVisible
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.viewModels
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import dagger.hilt.android.AndroidEntryPoint
import org.oxycblt.auxio.R
import org.oxycblt.auxio.databinding.DialogTagEditBinding
import org.oxycblt.auxio.ui.ViewBindingMaterialDialogFragment
import org.oxycblt.auxio.util.collectImmediately
import org.oxycblt.musikr.Album
import org.oxycblt.musikr.Music
import org.oxycblt.musikr.Song
import org.oxycblt.musikr.tag.edit.TagField

/**
 * Fork pessoal: editor de tags. Só os campos que você mudar são gravados; o resto do arquivo fica
 * como está (ver [org.oxycblt.musikr.tag.edit.TagEditor]).
 */
@AndroidEntryPoint
class TagEditDialog : ViewBindingMaterialDialogFragment<DialogTagEditBinding>() {
    private val model: TagEditViewModel by viewModels()
    private var initial: Map<TagField, FieldValue> = emptyMap()
    private var shownReady = false

    private val isBatch
        get() = requireArguments().getBoolean(ARG_BATCH)

    override fun onCreateBinding(inflater: LayoutInflater) = DialogTagEditBinding.inflate(inflater)

    override fun onConfigDialog(builder: AlertDialog.Builder) {
        super.onConfigDialog(builder)
        builder
            .setTitle(if (isBatch) R.string.lbl_edit_album_tags else R.string.lbl_edit_tags)
            .setNegativeButton(R.string.lbl_cancel, null)
            // O clique é tratado em onStart para o diálogo não fechar antes de gravar.
            .setPositiveButton(R.string.lbl_save, null)
    }

    override fun onBindingCreated(binding: DialogTagEditBinding, savedInstanceState: Bundle?) {
        super.onBindingCreated(binding, savedInstanceState)
        val uids =
            requireArguments().getStringArrayList(ARG_UIDS).orEmpty().mapNotNull {
                Music.UID.fromString(it)
            }
        model.load(uids)
        // Título e número da faixa são diferentes em cada faixa: não aparecem na edição em lote.
        binding.tagTitleLayout.isVisible = !isBatch
        binding.tagTrackLayout.isVisible = !isBatch
        collectImmediately(model.state, ::updateState)
    }

    override fun onStart() {
        super.onStart()
        (requireDialog() as AlertDialog).getButton(AlertDialog.BUTTON_POSITIVE).apply {
            isEnabled = model.state.value is TagEditState.Ready
            setOnClickListener { model.save(collectEdits()) }
        }
    }

    private fun inputs(binding: DialogTagEditBinding): Map<TagField, Pair<TextInputLayout, TextInputEditText>> =
        mapOf(
            TagField.TITLE to (binding.tagTitleLayout to binding.tagTitle),
            TagField.ARTIST to (binding.tagArtistLayout to binding.tagArtist),
            TagField.ALBUM_ARTIST to (binding.tagAlbumArtistLayout to binding.tagAlbumArtist),
            TagField.ALBUM to (binding.tagAlbumLayout to binding.tagAlbum),
            TagField.DATE to (binding.tagDateLayout to binding.tagDate),
            TagField.ORIGINAL_DATE to (binding.tagOriginalDateLayout to binding.tagOriginalDate),
            TagField.TRACK to (binding.tagTrackLayout to binding.tagTrack),
            TagField.DISC to (binding.tagDiscLayout to binding.tagDisc),
            TagField.GENRE to (binding.tagGenreLayout to binding.tagGenre),
            TagField.COMMENT to (binding.tagCommentLayout to binding.tagComment),
        )

    private fun collectEdits(): Map<TagField, String> {
        val binding = requireBinding()
        val edits = mutableMapOf<TagField, String>()
        for ((field, views) in inputs(binding)) {
            if (!views.first.isVisible) continue
            val text = views.second.text?.toString() ?: ""
            val before = initial[field] ?: continue
            if (before.mixed) {
                // Campo com valores diferentes: em branco = manter o de cada faixa.
                if (text.isNotBlank()) edits[field] = text
            } else if (text.trim() != before.value.trim()) {
                edits[field] = text
            }
        }
        return edits
    }

    private fun updateState(state: TagEditState) {
        val binding = requireBinding()
        val positive = (dialog as? AlertDialog)?.getButton(AlertDialog.BUTTON_POSITIVE)
        when (state) {
            is TagEditState.Loading -> {
                binding.tagProgress.isVisible = true
                binding.tagStatus.setText(R.string.lng_tags_loading)
                positive?.isEnabled = false
            }
            is TagEditState.Ready -> {
                binding.tagProgress.isVisible = false
                binding.tagStatus.text =
                    if (state.songCount > 1) {
                        getString(R.string.fmt_tags_batch, state.songCount, state.formats)
                    } else {
                        state.formats
                    }
                if (!shownReady) {
                    shownReady = true
                    initial = state.fields
                    for ((field, views) in inputs(binding)) {
                        val value = state.fields[field] ?: continue
                        views.second.setText(value.value)
                        if (value.mixed) views.first.helperText = getString(R.string.lbl_tag_mixed)
                    }
                }
                binding.tagFields.isVisible = true
                positive?.isEnabled = true
            }
            is TagEditState.Saving -> {
                binding.tagProgress.isVisible = true
                binding.tagStatus.text = getString(R.string.fmt_tags_saving, state.done + 1, state.total)
                binding.tagFields.isEnabled = false
                positive?.isEnabled = false
            }
            is TagEditState.Finished -> {
                binding.tagProgress.isVisible = false
                if (state.failures.isEmpty()) {
                    Toast.makeText(
                            requireContext(),
                            if (state.total == 0) R.string.lng_tags_nothing
                            else R.string.lng_tags_saved,
                            Toast.LENGTH_SHORT,
                        )
                        .show()
                    dismiss()
                } else {
                    binding.tagFields.isVisible = false
                    binding.tagStatus.text =
                        getString(
                            R.string.fmt_tags_failed,
                            state.failures.size,
                            state.total,
                            state.failures.joinToString("\n"),
                        )
                    positive?.visibility = View.GONE
                }
            }
            is TagEditState.Error -> {
                binding.tagProgress.isVisible = false
                binding.tagFields.isVisible = false
                binding.tagStatus.text = state.message
                positive?.visibility = View.GONE
            }
        }
    }

    companion object {
        private const val TAG = "auxio.fork.TAG_EDIT"
        private const val ARG_UIDS = "uids"
        private const val ARG_BATCH = "batch"

        fun show(activity: FragmentActivity, song: Song) = show(activity, song.uid, false)

        fun show(activity: FragmentActivity, album: Album) = show(activity, album.uid, true)

        private fun show(activity: FragmentActivity, uid: Music.UID, batch: Boolean) {
            TagEditDialog()
                .apply {
                    arguments = bundleOf(ARG_UIDS to arrayListOf(uid.toString()), ARG_BATCH to batch)
                }
                .show(activity.supportFragmentManager, TAG)
        }
    }
}

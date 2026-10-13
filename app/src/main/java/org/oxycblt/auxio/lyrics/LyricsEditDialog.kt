/*
 * Copyright (c) 2026 Auxio Project
 * LyricsEditDialog.kt is part of Auxio.
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

package org.oxycblt.auxio.lyrics

import android.os.Bundle
import android.view.LayoutInflater
import androidx.appcompat.app.AlertDialog
import androidx.core.os.bundleOf
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.oxycblt.auxio.R
import org.oxycblt.auxio.databinding.DialogLyricsEditBinding
import org.oxycblt.auxio.music.MusicRepository
import org.oxycblt.auxio.music.resolve
import org.oxycblt.auxio.playback.PlaybackViewModel
import org.oxycblt.auxio.ui.ViewBindingMaterialDialogFragment
import org.oxycblt.auxio.util.showToast
import org.oxycblt.musikr.Music
import org.oxycblt.musikr.Song

/**
 * Fork pessoal: escreve ou altera o .lrc da música, dentro do player.
 * - "Inserir tempo" põe o [mm:ss.xx] do ponto em que a música está no começo da linha do cursor
 *   (trocando o que já houver) e passa para a linha seguinte: dá para sincronizar ouvindo.
 * - Salvar grava o .lrc ao lado da música (cria se não existir). O MP3 não é alterado.
 */
@AndroidEntryPoint
class LyricsEditDialog : ViewBindingMaterialDialogFragment<DialogLyricsEditBinding>() {
    @Inject lateinit var musicRepository: MusicRepository
    private val playbackModel: PlaybackViewModel by activityViewModels()
    private var loaded = false

    private val song: Song?
        get() =
            requireArguments().getString(ARG_UID)?.let(Music.UID::fromString)?.let {
                musicRepository.find(it) as? Song
            }

    override fun onCreateBinding(inflater: LayoutInflater) = DialogLyricsEditBinding.inflate(inflater)

    override fun onConfigDialog(builder: AlertDialog.Builder) {
        super.onConfigDialog(builder)
        builder
            .setTitle(R.string.lbl_lyrics_edit)
            // Os cliques são tratados em onStart para o diálogo não fechar sozinho.
            .setPositiveButton(R.string.lbl_tag_save, null)
            .setNegativeButton(R.string.lbl_cancel, null)
            .setNeutralButton(R.string.lbl_lyrics_stamp, null)
    }

    override fun onBindingCreated(binding: DialogLyricsEditBinding, savedInstanceState: Bundle?) {
        super.onBindingCreated(binding, savedInstanceState)
        val song = song
        if (song == null) {
            dismiss()
            return
        }
        binding.lyricsSearch.setOnClickListener { askSearch(song) }
        binding.lyricsEditNote.text = getString(R.string.lng_lyrics_loading)
        loaded = savedInstanceState != null
        if (loaded) {
            binding.lyricsEditNote.text = noteFor(null, song)
            return
        }
        viewLifecycleOwner.lifecycleScope.launch {
            val result =
                withContext(Dispatchers.IO) { LyricsFiles.load(requireContext().applicationContext, song) }
            val b = binding
            b.lyricsEditText.setText(result.text.replace("\r\n", "\n"))
            b.lyricsEditNote.text = noteFor(result.source, song)
            loaded = true
            updateButtons()
        }
    }

    /** Pergunta a palavra extra (padrão "Genius", a última usada fica lembrada) e abre a busca. */
    private fun askSearch(song: Song) {
        val context = requireContext()
        val prefs = context.getSharedPreferences("lyrics_search", android.content.Context.MODE_PRIVATE)
        val input =
            android.widget.EditText(context).apply {
                setText(prefs.getString(KEY_WORD, "Genius"))
                setSingleLine()
                selectAll()
            }
        val pad = (20 * resources.displayMetrics.density).toInt()
        val frame =
            android.widget.FrameLayout(context).apply {
                setPadding(pad, pad / 2, pad, 0)
                addView(input)
            }
        val title = song.name.resolve(context)
        val artist = song.artists.firstOrNull()?.name?.resolve(context) ?: ""
        com.google.android.material.dialog.MaterialAlertDialogBuilder(context)
            .setTitle(R.string.lbl_lyrics_search)
            .setMessage(getString(R.string.lng_lyrics_search_word, "$title $artist".trim()))
            .setView(frame)
            .setPositiveButton(R.string.lbl_lyrics_search_go) { _, _ ->
                val word = input.text?.toString()?.trim() ?: ""
                prefs.edit().putString(KEY_WORD, word).apply()
                val query = listOf(title, artist, word).filter { it.isNotBlank() }.joinToString(" ")
                val uri =
                    android.net.Uri.parse(
                        "https://www.google.com/search?q=" + android.net.Uri.encode(query)
                    )
                try {
                    startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, uri))
                } catch (e: Exception) {
                    context.showToast(R.string.err_no_app)
                }
            }
            .setNegativeButton(R.string.lbl_cancel, null)
            .show()
            .window
            ?.setWindowAnimations(0)
    }

    private fun noteFor(source: LyricsSource?, song: Song): String {
        val base = song.path.name?.substringBeforeLast('.') ?: "?"
        return when (source) {
            LyricsSource.EMBEDDED -> getString(R.string.lng_lyrics_from_embedded, "$base.lrc")
            LyricsSource.NONE -> getString(R.string.lng_lyrics_new_file, "$base.lrc")
            else -> getString(R.string.lng_lyrics_file, "$base.lrc")
        }
    }

    override fun onStart() {
        super.onStart()
        val dialog = requireDialog() as AlertDialog
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener { insertStamp() }
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener { save() }
        updateButtons()
    }

    private fun updateButtons() {
        val dialog = dialog as? AlertDialog ?: return
        dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.isEnabled = loaded
        // Só faz sentido marcar tempo quando a música em edição é a que está tocando.
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL)?.isEnabled =
            loaded && playbackModel.song.value?.uid == song?.uid
    }

    private fun insertStamp() {
        val edit = requireBinding().lyricsEditText
        val text = edit.text ?: return
        val cursor = edit.selectionStart.coerceIn(0, text.length)
        val lineStart = text.lastIndexOf('\n', (cursor - 1).coerceAtLeast(0)).let {
            if (cursor == 0) 0 else it + 1
        }
        var lineEnd = text.indexOf('\n', lineStart)
        if (lineEnd < 0) lineEnd = text.length
        // Tira as marcações que a linha já tiver no começo.
        var contentStart = lineStart
        val stampRegex = Regex("""^\s*\[\d{1,3}:\d{1,2}(?:[.:]\d{1,3})?]""")
        while (true) {
            val m = stampRegex.find(text.subSequence(contentStart, lineEnd)) ?: break
            contentStart += m.range.last + 1
        }
        val stamp = Lyrics.stamp(playbackModel.positionMs())
        text.replace(lineStart, contentStart, stamp)
        // Vai para o começo da linha seguinte (cria uma se for a última).
        val newLineEnd = text.indexOf('\n', lineStart).let { if (it < 0) text.length else it }
        if (newLineEnd >= text.length) {
            text.append('\n')
        }
        edit.setSelection((newLineEnd + 1).coerceAtMost(text.length))
    }

    private fun save() {
        val song = song ?: return
        val text = requireBinding().lyricsEditText.text?.toString() ?: ""
        val context = requireContext().applicationContext
        val dialog = requireDialog() as AlertDialog
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = false
        viewLifecycleOwner.lifecycleScope.launch {
            val error = withContext(Dispatchers.IO) { LyricsFiles.save(context, song, text) }
            if (error == null) {
                context.showToast(R.string.lng_lyrics_saved)
                dismiss()
            } else {
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = true
                requireBinding().lyricsEditNote.text = getString(R.string.err_lyrics_save, error)
            }
        }
    }

    companion object {
        private const val TAG = "auxio.fork.LYRICS_EDIT"
        private const val ARG_UID = "uid"
        private const val KEY_WORD = "word"

        fun show(activity: FragmentActivity, song: Song) {
            LyricsEditDialog()
                .apply { arguments = bundleOf(ARG_UID to song.uid.toString()) }
                .show(activity.supportFragmentManager, TAG)
        }
    }
}

/*
 * Copyright (c) 2026 Auxio Project
 * TagEditViewModel.kt is part of Auxio.
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

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.oxycblt.auxio.music.MusicRepository
import org.oxycblt.auxio.playback.state.PlaybackStateManager
import org.oxycblt.auxio.rating.AlbumRatings
import org.oxycblt.musikr.Album
import org.oxycblt.musikr.Music
import org.oxycblt.musikr.Song
import org.oxycblt.musikr.tag.edit.TagEditor
import org.oxycblt.musikr.tag.edit.TagField
import org.oxycblt.musikr.tag.edit.TagReadResult

/** Valor de um campo no editor: [mixed] = as faixas têm valores diferentes. */
data class FieldValue(val value: String, val mixed: Boolean)

sealed interface TagEditState {
    data object Loading : TagEditState

    data class Ready(
        val songCount: Int,
        val formats: String,
        val fields: Map<TagField, FieldValue>,
    ) : TagEditState

    data class Saving(val done: Int, val total: Int) : TagEditState

    /** [failures] vazio = tudo gravado e conferido. */
    data class Finished(val total: Int, val failures: List<String>) : TagEditState

    data class Error(val message: String) : TagEditState
}

/** Fork pessoal: lê e grava tags de uma faixa ou de um álbum inteiro. */
@HiltViewModel
class TagEditViewModel
@Inject
constructor(
    @ApplicationContext private val context: Context,
    private val musicRepository: MusicRepository,
    private val playbackManager: PlaybackStateManager,
) : ViewModel() {
    private val editor = TagEditor(context)
    private var songs = listOf<Song>()
    private var album: Album? = null
    private var loaded = false

    val state: StateFlow<TagEditState>
        field = MutableStateFlow<TagEditState>(TagEditState.Loading)

    fun load(uids: List<Music.UID>) {
        if (loaded) return
        loaded = true
        val found = uids.mapNotNull { musicRepository.find(it) }
        album = found.filterIsInstance<Album>().firstOrNull()
        songs = found.flatMap {
            when (it) {
                is Song -> listOf(it)
                is Album -> it.songs
                else -> emptyList()
            }
        }
        if (songs.isEmpty()) {
            state.value = TagEditState.Error("Música não encontrada")
            return
        }
        viewModelScope.launch {
            state.value =
                withContext(Dispatchers.IO) {
                    val formats = linkedSetOf<String>()
                    val perSong = mutableListOf<Map<TagField, String>>()
                    for (song in songs) {
                        when (val result = editor.read(song.uri, song.path.name ?: "")) {
                            is TagReadResult.Ok -> {
                                formats += result.tags.format
                                perSong += result.tags.values
                            }
                            is TagReadResult.Unsupported ->
                                return@withContext TagEditState.Error(
                                    "${song.path.name ?: song.uri}: ${result.reason}"
                                )
                        }
                    }
                    val fields =
                        TagField.entries.associateWith { field ->
                            val values = perSong.map { it[field] ?: "" }.distinct()
                            if (values.size == 1) FieldValue(values[0], false)
                            else FieldValue("", true)
                        }
                    TagEditState.Ready(songs.size, formats.joinToString(", "), fields)
                }
        }
    }

    /**
     * Grava os campos alterados. [edits] só deve conter o que o usuário mudou de fato.
     */
    fun save(edits: Map<TagField, String>) {
        if (state.value !is TagEditState.Ready) return
        if (edits.isEmpty()) {
            state.value = TagEditState.Finished(0, emptyList())
            return
        }
        val albumChanges = edits.containsKey(TagField.ALBUM) || edits.containsKey(TagField.ALBUM_ARTIST)
        viewModelScope.launch {
            // Nome/artista do álbum mudam o identificador do álbum: guarda a nota para reaplicar.
            if (albumChanges) {
                songs.map { it.album }.distinct().forEach {
                    AlbumRatings.rememberForRetag(it)
                    org.oxycblt.auxio.rating.AlbumCollections.rememberForRetag(it)
                }
            }
            val failures = mutableListOf<String>()
            // Gravar o arquivo que o player está usando pode interromper a música; essa fica de fora.
            val playing = playbackManager.currentSong
            withContext(Dispatchers.IO) {
                songs.forEachIndexed { i, song ->
                    state.value = TagEditState.Saving(i, songs.size)
                    if (song == playing) {
                        failures +=
                            "• ${song.path.name ?: song.uri}: está tocando agora; " +
                                "passe para outra música e grave de novo"
                        return@forEachIndexed
                    }
                    val error = editor.write(song.uri, song.path.name ?: "", edits)
                    if (error != null) failures += "• ${song.path.name ?: song.uri}: $error"
                }
            }
            if (failures.size < songs.size) {
                // Relê a biblioteca para o app mostrar as tags novas.
                musicRepository.requestIndex(true)
            }
            state.value = TagEditState.Finished(songs.size, failures)
        }
    }
}

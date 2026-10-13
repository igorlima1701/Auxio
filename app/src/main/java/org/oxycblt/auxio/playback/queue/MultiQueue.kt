/*
 * Copyright (c) 2026 Auxio Project
 * MultiQueue.kt is part of Auxio.
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
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import org.oxycblt.auxio.music.MusicRepository
import org.oxycblt.auxio.playback.persist.PersistenceRepository
import org.oxycblt.auxio.playback.state.PlaybackStateManager
import org.oxycblt.auxio.playback.state.QueueChange
import org.oxycblt.musikr.Music
import org.oxycblt.musikr.MusicParent
import org.oxycblt.musikr.Song
import timber.log.Timber as L

/**
 * Fork pessoal: várias filas, como no Musicolet (até [MAX]).
 *
 * A fila ativa é a do player (e é salva pelo próprio Auxio). As outras ficam guardadas aqui, cada uma
 * com suas músicas, a música em que parou e a posição dentro dela, num arquivo próprio. Trocar de fila
 * guarda a atual e carrega a escolhida no player; se estava tocando, continua tocando.
 */
@Singleton
class MultiQueue
@Inject
constructor(
    @ApplicationContext private val context: Context,
    private val playbackManager: PlaybackStateManager,
    private val musicRepository: MusicRepository,
    private val persistenceRepository: PersistenceRepository,
) : PlaybackStateManager.Listener {
    /** Uma fila guardada (não ativa). */
    private data class Stored(
        val heap: List<String>,
        val mapping: List<Int>,
        val index: Int,
        val songUid: String?,
        val positionMs: Long,
        val parentUid: String?,
    ) {
        /** Música em que a fila parou, pela ordem de reprodução. */
        val currentHeapIndex: Int
            get() = if (mapping.isNotEmpty()) mapping.getOrElse(index) { -1 } else index
    }

    /** Resumo para a tela: quantas filas, qual está ativa e o tamanho de cada uma. */
    data class Info(val count: Int, val active: Int, val sizes: List<Int>, val currentUids: List<String?>)

    private val file = File(context.filesDir, "multi_queue.json")
    // Uma gravação por vez, na ordem.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))
    private val slots = mutableListOf<Stored?>(null)
    private var active = 0

    val info: StateFlow<Info>
        field = MutableStateFlow(Info(1, 0, listOf(0), listOf(null)))

    /** Músicas esperando a escolha da fila ("Adicionar à fila"). */
    val pendingAdd: StateFlow<List<Song>?>
        field = MutableStateFlow<List<Song>?>(null)

    init {
        load()
        playbackManager.addListener(this)
        publish()
    }

    // --- O QUE A TELA CHAMA ---

    /** Pede para adicionar [songs] a uma fila: a tela pergunta qual. */
    fun requestAdd(songs: List<Song>) {
        if (songs.isEmpty()) return
        pendingAdd.value = songs
    }

    fun clearPending() {
        pendingAdd.value = null
    }

    /** Adiciona as músicas pendentes ao fim da fila [slot]. */
    fun addPendingTo(slot: Int): Boolean {
        val songs = pendingAdd.value ?: return false
        pendingAdd.value = null
        if (slot !in slots.indices) return false
        if (slot == active) {
            playbackManager.addToQueue(songs)
        } else {
            val stored = slots[slot]
            val uids = songs.map { it.uid.toString() }
            slots[slot] =
                if (stored == null || stored.heap.isEmpty()) {
                    Stored(uids, emptyList(), 0, uids.first(), 0, null)
                } else {
                    val start = stored.heap.size
                    Stored(
                        heap = stored.heap + uids,
                        // Fila embaralhada: as novas entram no fim da ordem de reprodução.
                        mapping =
                            if (stored.mapping.isEmpty()) emptyList()
                            else stored.mapping + uids.indices.map { start + it },
                        index = stored.index,
                        songUid = stored.songUid,
                        positionMs = stored.positionMs,
                        parentUid = stored.parentUid,
                    )
                }
            persist()
        }
        publish()
        return true
    }

    /** Cria uma fila nova com as músicas pendentes. Retorna o número dela (0 = já tem [MAX]). */
    fun addPendingToNew(): Int {
        val songs = pendingAdd.value ?: return 0
        if (slots.size >= MAX) return 0
        pendingAdd.value = null
        val uids = songs.map { it.uid.toString() }
        slots.add(Stored(uids, emptyList(), 0, uids.first(), 0, null))
        persist()
        publish()
        return slots.size
    }

    /** Passa a tocar a fila [slot]. Retorna false se a biblioteca ainda não carregou. */
    fun switchTo(slot: Int): Boolean {
        if (slot == active || slot !in slots.indices) return true
        val library = musicRepository.library ?: return false
        val wasPlaying = playbackManager.progression.isPlaying
        slots[active] = playbackManager.toSavedState()?.let(::fromSaved)
        val target = slots[slot]
        slots[slot] = null
        active = slot

        val heap = target?.heap?.map { uid -> Music.UID.fromString(uid)?.let(library::findSong) }
        if (target != null && heap != null && heap.any { it != null }) {
            val songUid =
                target.songUid?.let(Music.UID::fromString)
                    ?: heap.getOrNull(target.currentHeapIndex)?.uid
                    ?: heap.firstNotNullOf { it }.uid
            val parent =
                target.parentUid?.let(Music.UID::fromString)?.let {
                    musicRepository.find(it) as? MusicParent
                }
            playbackManager.applySavedState(
                PlaybackStateManager.SavedState(
                    positionMs = target.positionMs,
                    repeatMode = playbackManager.repeatMode,
                    parent = parent,
                    heap = heap,
                    shuffledMapping = target.mapping,
                    index = target.index,
                    songUid = songUid,
                ),
                true,
            )
            if (wasPlaying) playbackManager.playing(true)
        } else {
            // Fila vazia: esvazia o player.
            playbackManager.currentSong?.let { current ->
                playbackManager.applySavedState(
                    PlaybackStateManager.SavedState(
                        positionMs = 0,
                        repeatMode = playbackManager.repeatMode,
                        parent = null,
                        heap = emptyList(),
                        shuffledMapping = emptyList(),
                        index = -1,
                        songUid = current.uid,
                    ),
                    true,
                )
            }
        }
        saveEngineNow()
        persist()
        publish()
        return true
    }

    /** Apaga a fila [slot]. A última fila não pode ser apagada. */
    fun delete(slot: Int): Boolean {
        if (slots.size <= 1 || slot !in slots.indices) return false
        if (slot == active) {
            if (!switchTo(if (slot > 0) slot - 1 else 1)) return false
        }
        slots.removeAt(slot)
        if (active > slot) active--
        persist()
        publish()
        return true
    }

    // --- ACOMPANHA O PLAYER (para os números da tela) ---

    override fun onQueueChanged(queue: List<Song>, index: Int, change: QueueChange) = publish()

    override fun onNewPlayback(parent: MusicParent?, queue: List<Song>, index: Int, isShuffled: Boolean) =
        publish()

    override fun onIndexMoved(index: Int) = publish()

    override fun onQueueReordered(queue: List<Song>, index: Int, isShuffled: Boolean) = publish()

    private fun publish() {
        val sizes = slots.mapIndexed { i, s -> if (i == active) playbackManager.queue.size else s?.heap?.size ?: 0 }
        val current =
            slots.mapIndexed { i, s ->
                if (i == active) playbackManager.currentSong?.uid?.toString()
                else s?.let { it.songUid ?: it.heap.getOrNull(it.currentHeapIndex) }
            }
        info.value = Info(slots.size, active, sizes, current)
    }

    // --- ARQUIVO ---

    private fun fromSaved(state: PlaybackStateManager.SavedState): Stored? {
        val heap = state.heap.map { it?.uid?.toString() ?: return null }
        return Stored(
            heap = heap,
            mapping = state.shuffledMapping,
            index = state.index,
            songUid = state.songUid.toString(),
            positionMs = state.positionMs,
            parentUid = state.parent?.uid?.toString(),
        )
    }

    /** Grava já o estado do player, para o Auxio não reabrir com a fila anterior. */
    private fun saveEngineNow() {
        val state = playbackManager.toSavedState()
        scope.launch {
            try {
                persistenceRepository.saveState(state)
            } catch (e: Exception) {
                L.w(e, "Não consegui salvar o estado do player")
            }
        }
    }

    private fun persist() {
        val json =
            JSONObject().apply {
                put("active", active)
                put(
                    "slots",
                    JSONArray().apply {
                        for ((i, s) in slots.withIndex()) {
                            if (s == null || i == active) {
                                put(JSONObject.NULL)
                                continue
                            }
                            put(
                                JSONObject().apply {
                                    put("heap", JSONArray(s.heap))
                                    put("mapping", JSONArray(s.mapping))
                                    put("index", s.index)
                                    put("song", s.songUid ?: JSONObject.NULL)
                                    put("pos", s.positionMs)
                                    put("parent", s.parentUid ?: JSONObject.NULL)
                                }
                            )
                        }
                    },
                )
            }
        val text = json.toString()
        scope.launch {
            try {
                val tmp = File(file.parentFile, file.name + ".tmp")
                tmp.writeText(text)
                tmp.renameTo(file)
            } catch (e: Exception) {
                L.w(e, "Não consegui salvar as filas")
            }
        }
    }

    private fun load() {
        try {
            if (!file.exists()) return
            val json = JSONObject(file.readText())
            val array = json.getJSONArray("slots")
            slots.clear()
            for (i in 0 until array.length()) {
                val o = array.optJSONObject(i)
                if (o == null) {
                    slots.add(null)
                    continue
                }
                val heap = o.getJSONArray("heap").let { a -> List(a.length()) { a.getString(it) } }
                val mapping = o.getJSONArray("mapping").let { a -> List(a.length()) { a.getInt(it) } }
                slots.add(
                    Stored(
                        heap = heap,
                        mapping = mapping,
                        index = o.optInt("index", 0),
                        songUid = if (o.isNull("song")) null else o.getString("song"),
                        positionMs = o.optLong("pos", 0),
                        parentUid = if (o.isNull("parent")) null else o.getString("parent"),
                    )
                )
            }
            if (slots.isEmpty()) slots.add(null)
            active = json.optInt("active", 0).coerceIn(0, slots.size - 1)
            slots[active] = null
        } catch (e: Exception) {
            L.w(e, "Arquivo das filas ilegível; começando com uma fila")
            slots.clear()
            slots.add(null)
            active = 0
        }
    }

    companion object {
        const val MAX = 20
    }
}

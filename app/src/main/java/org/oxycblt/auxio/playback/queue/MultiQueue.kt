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
import org.oxycblt.auxio.R
import org.oxycblt.auxio.music.MusicRepository
import org.oxycblt.auxio.music.resolve
import org.oxycblt.auxio.playback.persist.PersistenceRepository
import org.oxycblt.auxio.playback.state.PlaybackCommand
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
 * com suas músicas, a música em que parou e a posição dentro dela, num arquivo próprio.
 * - Cada fila tem nome. O nome automático vem de onde a fila foi montada (álbum, artista…) e
 *   acompanha o conteúdo até o usuário renomear.
 * - Olhar outra fila ([view]) não toca nada; ela só passa a tocar com [activate].
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
    /** Uma fila guardada (não ativa). [heap] já está na ordem de reprodução quando [mapping] é vazio. */
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

    /** Nome da fila. [auto] = nome automático (acompanha o conteúdo); [sig] = conteúdo que o gerou. */
    private data class Meta(val name: String, val auto: Boolean, val sig: Int, val used: Long = 0L)

    /** Resumo para a tela. */
    data class Info(
        val count: Int,
        val active: Int,
        val viewing: Int,
        val names: List<String>,
        val sizes: List<Int>,
    )

    private val file = File(context.filesDir, "multi_queue.json")
    // Uma gravação por vez, na ordem.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))
    private val slots = mutableListOf<Stored?>(null)
    private val metas = mutableListOf(Meta(defaultName(0), true, 0))
    private var active = 0
    private var viewingSlot = 0
    private var switching = false
    private var pendingLabel: String? = null

    val info: StateFlow<Info>
        field = MutableStateFlow(Info(1, 0, 0, listOf(defaultName(0)), listOf(0)))

    /** Muda quando o conteúdo de uma fila parada muda por fora da tela da fila. */
    val storedVersion: StateFlow<Int>
        field = MutableStateFlow(0)

    /** Músicas esperando a escolha da fila ("Adicionar à fila"). */
    val pendingAdd: StateFlow<List<Song>?>
        field = MutableStateFlow<List<Song>?>(null)

    init {
        load()
        playbackManager.addListener(this)
        playbackManager.beforeNewPlayback = ::beforeNewPlayback
        org.oxycblt.auxio.playback.EndMode.nextQueue = ::playNextQueue
        publish()
    }

    /**
     * Fork pessoal: fim da fila com "pular para a próxima fila". Passa para a fila seguinte da lista
     * (depois da última, volta à primeira), de onde ela parou. [play] = já começa tocando.
     */
    private fun playNextQueue(play: Boolean): Boolean {
        if (slots.size <= 1) return false
        val target = (active + 1) % slots.size
        if (!load(target, keepPlaying = false, index = null)) return false
        metas[active] = metas[active].copy(used = System.currentTimeMillis())
        playbackManager.playing(play)
        viewingSlot = target
        saveEngineNow()
        persist()
        publish()
        return true
    }

    /**
     * Fork pessoal: cada reprodução nova vinda da biblioteca (álbum, artista, playlist, música de
     * uma lista) ganha uma fila nova; a anterior fica guardada na lista. Tocar de novo o mesmo
     * conteúdo da fila atual não cria outra. Passando de [MAX], some a fila usada há mais tempo
     * (as renomeadas por você só saem se não houver outra).
     */
    private fun beforeNewPlayback(command: PlaybackCommand) {
        if (switching) return
        val current = playbackManager.queue
        if (current.isEmpty() || command.queue.isEmpty()) return
        val newSet = command.queue.mapTo(HashSet()) { it.uid }
        if (newSet == current.mapTo(HashSet()) { it.uid }) return
        val now = System.currentTimeMillis()
        slots[active] = playbackManager.toSavedState()?.let(::fromSaved)
        metas[active] = metas[active].copy(used = now)
        while (slots.size >= MAX) {
            if (!evictOldest()) break
        }
        slots.add(null)
        metas.add(Meta(defaultName(slots.size - 1), true, 0, now))
        active = slots.size - 1
        viewingSlot = active
        bumpStored()
        persist()
        publish()
    }

    /** Apaga a fila parada usada há mais tempo (as automáticas primeiro). */
    private fun evictOldest(): Boolean {
        val candidates = slots.indices.filter { it != active }
        if (candidates.isEmpty()) return false
        val victim =
            candidates.filter { metas[it].auto }.minByOrNull { metas[it].used }
                ?: candidates.minByOrNull { metas[it].used }
                ?: return false
        removeStoppedSlot(victim)
        return true
    }

    private fun removeStoppedSlot(slot: Int) {
        if (slot == active || slot !in slots.indices) return
        slots.removeAt(slot)
        metas.removeAt(slot)
        if (active > slot) active--
        if (viewingSlot == slot) viewingSlot = active else if (viewingSlot > slot) viewingSlot--
    }

    /** Fork pessoal: apaga na hora a fila [slot] (a que está tocando passa para outra). */
    fun deleteQuick(slot: Int): Boolean = if (slot == active) delete(slot) else {
        if (slot !in slots.indices) false
        else {
            removeStoppedSlot(slot)
            bumpStored()
            persist()
            publish()
            true
        }
    }

    /** Fork pessoal: apaga todas as filas menos a que está tocando. */
    fun deleteOthers() {
        if (slots.size <= 1) return
        val keepSlot = slots[active]
        val keepMeta = metas[active]
        slots.clear()
        metas.clear()
        slots.add(keepSlot)
        metas.add(keepMeta)
        active = 0
        viewingSlot = 0
        bumpStored()
        persist()
        publish()
    }

    val activeSlot: Int
        get() = active

    val viewing: Int
        get() = viewingSlot

    // --- ADICIONAR À FILA ---

    /**
     * Pede para adicionar [songs] a uma fila (a tela pergunta qual). [source] = de onde vieram (álbum,
     * artista…), para dar nome a uma fila nova.
     */
    fun requestAdd(songs: List<Song>, source: Music? = null) {
        if (songs.isEmpty()) return
        pendingLabel = source?.takeIf { it !is Song }?.name?.resolve(context)
        pendingAdd.value = songs
    }

    fun clearPending() {
        pendingAdd.value = null
        pendingLabel = null
    }

    /** Adiciona as músicas pendentes ao fim da fila [slot]. */
    fun addPendingTo(slot: Int): Boolean {
        val songs = pendingAdd.value ?: return false
        clearPending()
        if (slot !in slots.indices) return false
        if (slot == active) {
            playbackManager.addToQueue(songs)
        } else {
            val stored = normalized(slot)
            val uids = songs.map { it.uid.toString() }
            slots[slot] =
                if (stored == null || stored.heap.isEmpty()) {
                    Stored(uids, emptyList(), 0, uids.first(), 0, null)
                } else {
                    stored.copy(heap = stored.heap + uids)
                }
            bumpStored()
            persist()
        }
        publish()
        return true
    }

    /** Cria uma fila nova com as músicas pendentes. Retorna o número dela (0 = já tem [MAX]). */
    fun addPendingToNew(): Int {
        val songs = pendingAdd.value ?: return 0
        if (slots.size >= MAX) return 0
        val label = pendingLabel
        clearPending()
        val uids = songs.map { it.uid.toString() }
        slots.add(Stored(uids, emptyList(), 0, uids.first(), 0, null))
        metas.add(Meta(label ?: autoName(null, songs, slots.size - 1), true, sigOf(songs)))
        persist()
        publish()
        return slots.size
    }

    /**
     * Fork pessoal: cria uma fila nova chamada [name] com [songs] (embaralhadas se [shuffle]) e
     * passa a tocá-la. A fila que estava tocando fica guardada. Retorna false se já há [MAX] filas
     * ou se a biblioteca ainda não carregou.
     */
    fun playInNewQueue(
        songs: List<Song>,
        name: String,
        shuffle: Boolean,
        startIndex: Int = 0,
    ): Boolean {
        if (songs.isEmpty() || musicRepository.library == null) return false
        while (slots.size >= MAX) {
            if (!evictOldest()) return false
        }
        val order = if (shuffle) songs.shuffled() else songs
        val start = if (shuffle) 0 else startIndex.coerceIn(0, order.lastIndex)
        val uids = order.map { it.uid.toString() }
        slots.add(Stored(uids, emptyList(), start, uids[start], 0, null))
        metas.add(Meta(name, true, sigOf(order), System.currentTimeMillis()))
        val slot = slots.size - 1
        if (!activate(slot, start)) {
            slots.removeAt(slot)
            metas.removeAt(slot)
            publish()
            return false
        }
        return true
    }

    /** Fork pessoal: embaralha a fila parada [slot]; ela recomeça do topo. */
    fun shuffleStored(slot: Int) {
        val stored = normalized(slot) ?: return
        if (stored.heap.size < 2) return
        val heap = stored.heap.shuffled()
        slots[slot] = stored.copy(heap = heap, mapping = emptyList(), index = 0, songUid = heap[0], positionMs = 0)
        bumpStored()
        persist()
        publish()
    }

    // --- VER, TOCAR ---

    /** Mostra a fila [slot] na tela da fila, sem tocar nada. */
    fun view(slot: Int) {
        if (slot !in slots.indices) return
        viewingSlot = slot
        if (slot != active) normalized(slot)
        publish()
    }

    /** Músicas da fila parada [slot], na ordem de reprodução. */
    fun storedSongs(slot: Int): List<Song> {
        val library = musicRepository.library ?: return emptyList()
        val stored = normalized(slot) ?: return emptyList()
        return stored.heap.mapNotNull { uid -> Music.UID.fromString(uid)?.let(library::findSong) }
    }

    /** Posição da música em que a fila parada [slot] parou. */
    fun storedIndex(slot: Int): Int = normalized(slot)?.index ?: -1

    /**
     * Passa a tocar a fila [slot]. [index] = começar nessa música (do início dela); null = de onde a
     * fila parou. Retorna false se a biblioteca ainda não carregou.
     */
    fun activate(slot: Int, index: Int? = null): Boolean {
        if (slot !in slots.indices) return false
        if (slot == active) {
            if (index != null) playbackManager.goto(index)
            playbackManager.playing(true)
            viewingSlot = slot
            publish()
            return true
        }
        if (!load(slot, keepPlaying = false, index = index)) return false
        metas[active] = metas[active].copy(used = System.currentTimeMillis())
        playbackManager.playing(true)
        viewingSlot = slot
        saveEngineNow()
        persist()
        publish()
        return true
    }

    /**
     * Troca a fila do player para [slot], guardando a atual. [keepPlaying] = continua tocando se
     * estava. Retorna false se a biblioteca ainda não carregou.
     */
    private fun load(slot: Int, keepPlaying: Boolean, index: Int?): Boolean {
        val library = musicRepository.library ?: return false
        val wasPlaying = playbackManager.progression.isPlaying
        slots[active] = playbackManager.toSavedState()?.let(::fromSaved)
        val target = normalized(slot)
        slots[slot] = null
        active = slot

        val heap = target?.heap?.map { uid -> Music.UID.fromString(uid)?.let(library::findSong) }
        switching = true
        try {
            if (target != null && heap != null && heap.any { it != null }) {
                val useIndex = index ?: target.index
                val position = if (index == null || index == target.index) target.positionMs else 0L
                val song =
                    heap.getOrNull(
                        if (target.mapping.isNotEmpty()) target.mapping.getOrElse(useIndex) { -1 }
                        else useIndex
                    ) ?: heap.firstNotNullOf { it }
                val parent =
                    target.parentUid?.let(Music.UID::fromString)?.let {
                        musicRepository.find(it) as? MusicParent
                    }
                playbackManager.applySavedState(
                    PlaybackStateManager.SavedState(
                        positionMs = position,
                        repeatMode = playbackManager.repeatMode,
                        parent = parent,
                        heap = heap,
                        shuffledMapping = target.mapping,
                        index = useIndex,
                        songUid = song.uid,
                    ),
                    true,
                )
                if (keepPlaying && wasPlaying) playbackManager.playing(true)
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
        } finally {
            switching = false
        }
        metas[active] = metas[active].copy(sig = sigOf(playbackManager.queue))
        return true
    }

    // --- EDITAR FILA PARADA (a tela da fila atualiza a si mesma) ---

    fun removeFromStored(slot: Int, positions: Collection<Int>) {
        val stored = normalized(slot) ?: return
        val drop = positions.toSet()
        val heap = stored.heap.filterIndexed { i, _ -> i !in drop }
        val removedBefore = drop.count { it < stored.index }
        val currentRemoved = stored.index in drop
        var index = (stored.index - removedBefore).coerceAtMost(heap.lastIndex)
        if (index < 0) index = 0
        slots[slot] =
            stored.copy(
                heap = heap,
                index = index,
                songUid = heap.getOrNull(index),
                positionMs = if (currentRemoved) 0 else stored.positionMs,
            )
        persist()
        publish()
    }

    fun moveInStored(slot: Int, from: Int, to: Int) {
        val stored = normalized(slot) ?: return
        if (from !in stored.heap.indices || to !in stored.heap.indices) return
        val heap = stored.heap.toMutableList()
        heap.add(to, heap.removeAt(from))
        slots[slot] = stored.copy(heap = heap, index = heap.indexOf(stored.songUid).coerceAtLeast(0))
        persist()
    }

    /** Reordena a fila parada para a ordem [order] (lista de posições antigas). */
    fun reorderStored(slot: Int, order: List<Int>) {
        val stored = normalized(slot) ?: return
        if (order.size != stored.heap.size) return
        val heap = order.map { stored.heap[it] }
        slots[slot] =
            stored.copy(heap = heap, index = order.indexOf(stored.index).coerceAtLeast(0))
        persist()
    }

    /** Coloca [songs] logo depois da música atual da fila parada [slot]. */
    fun playNextInStored(slot: Int, songs: List<Song>) {
        val stored = normalized(slot) ?: return
        val heap = stored.heap.toMutableList()
        val at = (stored.index + 1).coerceIn(0, heap.size)
        heap.addAll(at, songs.map { it.uid.toString() })
        slots[slot] = stored.copy(heap = heap)
        persist()
        publish()
    }

    // --- NOMES, ORDEM, APAGAR, SALVAR ---

    fun rename(slot: Int, name: String) {
        if (slot !in metas.indices || name.isBlank()) return
        metas[slot] = metas[slot].copy(name = name.trim(), auto = false)
        persist()
        publish()
    }

    /** Move a fila [slot] uma posição para cima (-1) ou para baixo (+1) na lista de filas. */
    fun moveSlot(slot: Int, delta: Int) {
        val to = slot + delta
        if (slot !in slots.indices || to !in slots.indices) return
        slots.add(to, slots.removeAt(slot))
        metas.add(to, metas.removeAt(slot))
        fun remap(i: Int) =
            when (i) {
                slot -> to
                to -> slot
                else -> i
            }
        active = remap(active)
        viewingSlot = remap(viewingSlot)
        persist()
        publish()
    }

    /** Apaga a fila [slot]. A última fila não pode ser apagada. */
    fun delete(slot: Int): Boolean {
        if (slots.size <= 1 || slot !in slots.indices) return false
        if (slot == active) {
            if (!load(if (slot > 0) slot - 1 else 1, keepPlaying = true, index = null)) return false
            saveEngineNow()
        }
        slots.removeAt(slot)
        metas.removeAt(slot)
        if (active > slot) active--
        viewingSlot = active
        bumpStored()
        persist()
        publish()
        return true
    }

    /** Salva a fila [slot] como uma playlist comum chamada [name]. */
    fun saveAsPlaylist(slot: Int, name: String) {
        val songs = if (slot == active) playbackManager.queue else storedSongs(slot)
        if (songs.isEmpty() || name.isBlank()) return
        scope.launch {
            try {
                musicRepository.createPlaylist(name.trim(), songs)
            } catch (e: Exception) {
                L.w(e, "Não consegui salvar a fila como playlist")
            }
        }
    }

    fun nameOf(slot: Int) = metas.getOrNull(slot)?.name ?: defaultName(slot)

    // --- ACOMPANHA O PLAYER ---

    override fun onQueueChanged(queue: List<Song>, index: Int, change: QueueChange) = publish()

    override fun onNewPlayback(parent: MusicParent?, queue: List<Song>, index: Int, isShuffled: Boolean) {
        // Fila nova vinda da biblioteca: o nome automático acompanha. Restaurações e trocas de
        // fila (mesmo conteúdo) não mudam o nome.
        if (!switching && queue.isNotEmpty()) {
            val sig = sigOf(queue)
            val meta = metas[active]
            if (sig != meta.sig) {
                val now = System.currentTimeMillis()
                metas[active] =
                    if (meta.auto) Meta(autoName(parent, queue, active), true, sig, now)
                    else meta.copy(sig = sig, used = now)
                persist()
            }
        }
        publish()
    }

    override fun onIndexMoved(index: Int) = publish()

    override fun onQueueReordered(queue: List<Song>, index: Int, isShuffled: Boolean) = publish()

    private fun publish() {
        val sizes =
            slots.mapIndexed { i, s -> if (i == active) playbackManager.queue.size else s?.heap?.size ?: 0 }
        if (viewingSlot !in slots.indices) viewingSlot = active
        info.value = Info(slots.size, active, viewingSlot, metas.map { it.name }, sizes)
    }

    private fun bumpStored() {
        storedVersion.value = storedVersion.value + 1
    }

    // --- NOMES AUTOMÁTICOS ---

    private fun defaultName(slot: Int) = context.getString(R.string.lbl_queue_n, slot + 1)

    private fun autoName(parent: MusicParent?, songs: List<Song>, slot: Int): String =
        when {
            parent != null -> parent.name.resolve(context)
            songs.isEmpty() -> defaultName(slot)
            songs.size == 1 -> songs[0].name.resolve(context)
            songs.size == (musicRepository.library?.songs?.size ?: -1) ->
                context.getString(R.string.lbl_all_songs)
            else -> {
                // Todas do mesmo álbum (inteiro) ou do mesmo artista: o nome dele.
                val album = songs[0].album
                if (songs.all { it.album == album } && songs.size == album.songs.size) {
                    album.name.resolve(context)
                } else {
                    context.getString(
                        R.string.fmt_queue_name_more,
                        songs[0].name.resolve(context),
                        songs.size - 1,
                    )
                }
            }
        }

    private fun sigOf(songs: List<Song>) = songs.map { it.uid }.hashCode()

    // --- ARQUIVO ---

    /**
     * Fila parada sem músicas sumidas e na ordem de reprodução (sem a ordem embaralhada guardada à
     * parte), para a tela mostrar e editar posição por posição.
     */
    private fun normalized(slot: Int): Stored? {
        val stored = slots.getOrNull(slot) ?: return null
        val library = musicRepository.library ?: return stored
        val exists = { uid: String -> Music.UID.fromString(uid)?.let(library::findSong) != null }
        if (stored.mapping.isEmpty() && stored.heap.all(exists)) return stored
        val order =
            if (stored.mapping.isNotEmpty()) stored.mapping.mapNotNull { stored.heap.getOrNull(it) }
            else stored.heap
        val currentUid = stored.songUid ?: stored.heap.getOrNull(stored.currentHeapIndex)
        val heap = order.filter(exists)
        val index = heap.indexOf(currentUid).coerceAtLeast(0)
        val fixed = stored.copy(heap = heap, mapping = emptyList(), index = index, songUid = heap.getOrNull(index))
        slots[slot] = fixed
        return fixed
    }

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
                            val meta = metas.getOrNull(i)
                            put(
                                JSONObject().apply {
                                    put("name", meta?.name ?: defaultName(i))
                                    put("auto", meta?.auto ?: true)
                                    put("sig", meta?.sig ?: 0)
                                    put("used", meta?.used ?: 0L)
                                    if (s != null && i != active) {
                                        put("heap", JSONArray(s.heap))
                                        put("mapping", JSONArray(s.mapping))
                                        put("index", s.index)
                                        put("song", s.songUid ?: JSONObject.NULL)
                                        put("pos", s.positionMs)
                                        put("parent", s.parentUid ?: JSONObject.NULL)
                                    }
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
            metas.clear()
            for (i in 0 until array.length()) {
                val o = array.optJSONObject(i)
                if (o == null) {
                    // Formato antigo: fila ativa ou vazia, sem nome.
                    slots.add(null)
                    metas.add(Meta(defaultName(i), true, 0))
                    continue
                }
                metas.add(
                    Meta(
                        o.optString("name").ifBlank { defaultName(i) },
                        o.optBoolean("auto", true),
                        o.optInt("sig", 0),
                        o.optLong("used", 0L),
                    )
                )
                if (!o.has("heap")) {
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
                        songUid = if (o.isNull("song")) null else o.optString("song"),
                        positionMs = o.optLong("pos", 0),
                        parentUid = if (o.isNull("parent")) null else o.optString("parent"),
                    )
                )
            }
            if (slots.isEmpty()) {
                slots.add(null)
                metas.add(Meta(defaultName(0), true, 0))
            }
            active = json.optInt("active", 0).coerceIn(0, slots.size - 1)
            viewingSlot = active
            slots[active] = null
        } catch (e: Exception) {
            L.w(e, "Arquivo das filas ilegível; começando com uma fila")
            slots.clear()
            slots.add(null)
            metas.clear()
            metas.add(Meta(defaultName(0), true, 0))
            active = 0
            viewingSlot = 0
        }
    }

    companion object {
        const val MAX = 20
    }
}

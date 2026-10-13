/*
 * Copyright (c) 2021 Auxio Project
 * QueueViewModel.kt is part of Auxio.
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

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.oxycblt.auxio.list.adapter.UpdateInstructions
import org.oxycblt.auxio.playback.state.PlaybackStateManager
import org.oxycblt.auxio.playback.state.QueueChange
import org.oxycblt.auxio.util.Event
import org.oxycblt.auxio.util.MutableEvent
import org.oxycblt.musikr.MusicParent
import org.oxycblt.musikr.Song
import timber.log.Timber as L

/**
 * A [ViewModel] that manages the current queue state and allows navigation through the queue.
 *
 * Fork pessoal: mostra a fila que está tocando ou uma fila parada (várias filas), e cuida da
 * seleção de várias músicas da fila.
 *
 * @author Alexander Capehart (OxygenCobalt)
 */
@HiltViewModel
class QueueViewModel
@Inject
constructor(
    private val playbackManager: PlaybackStateManager,
    private val multiQueue: MultiQueue,
) : ViewModel(), PlaybackStateManager.Listener {

    private val _queue = MutableStateFlow(listOf<Song>())
    /** The current queue. */
    val queue: StateFlow<List<Song>> = _queue
    private val _queueInstructions = MutableEvent<UpdateInstructions>()
    /** Instructions for how to update [queue] in the UI. */
    val queueInstructions: Event<UpdateInstructions> = _queueInstructions

    /** Controls whether the queue should be force-scrolled to a particular location. */
    val scrollTo: Event<Int>
        field = MutableEvent<Int>()

    /** The index of the currently playing song in the queue. */
    val index: StateFlow<Int>
        field = MutableStateFlow(playbackManager.index)

    /** Fork pessoal: true = a fila mostrada é a que está tocando. */
    val viewingActive: StateFlow<Boolean>
        field = MutableStateFlow(true)

    /** Fork pessoal: posições selecionadas na fila mostrada. */
    val selected: StateFlow<Set<Int>>
        field = MutableStateFlow(emptySet<Int>())

    private var shownSlot = multiQueue.viewing
    private var internalOp = false

    init {
        playbackManager.addListener(this)
        viewModelScope.launch { multiQueue.info.collect { onInfo(it) } }
        viewModelScope.launch {
            multiQueue.storedVersion.collect { if (!isActiveView()) reloadStored() }
        }
    }

    private fun isActiveView() = shownSlot == multiQueue.activeSlot

    private fun onInfo(info: MultiQueue.Info) {
        val nowActive = info.viewing == info.active
        if (info.viewing != shownSlot || nowActive != viewingActive.value) {
            shownSlot = info.viewing
            viewingActive.value = nowActive
            selected.value = emptySet()
            if (nowActive) replace(playbackManager.queue, playbackManager.index) else reloadStored()
        }
    }

    private fun reloadStored() {
        replace(multiQueue.storedSongs(shownSlot), multiQueue.storedIndex(shownSlot))
    }

    private fun replace(queue: List<Song>, index: Int) {
        _queueInstructions.put(UpdateInstructions.Replace(0))
        scrollTo.put(index)
        _queue.value = queue
        this.index.value = index
    }

    override fun onIndexMoved(index: Int) {
        if (!isActiveView()) return
        L.d("Index moved, synchronizing and scrolling to new position")
        scrollTo.put(index)
        this.index.value = index
    }

    override fun onQueueChanged(queue: List<Song>, index: Int, change: QueueChange) {
        if (!isActiveView()) return
        // Queue changed trivially due to item mo -> Diff queue, stay at current index.
        L.d("Updating queue display")
        _queueInstructions.put(change.instructions)
        _queue.value = queue
        if (change.type != QueueChange.Type.MAPPING) {
            // Index changed, make sure it remains updated without actually scrolling to it.
            L.d("Index changed with queue, synchronizing new position")
            this.index.value = index
        }
        // Itens entraram ou saíram por fora da seleção: as posições marcadas não valem mais.
        if (!internalOp && change.instructions !is UpdateInstructions.Move) {
            selected.value = emptySet()
        }
    }

    override fun onQueueReordered(queue: List<Song>, index: Int, isShuffled: Boolean) {
        if (!isActiveView()) return
        // Queue changed completely -> Replace queue, update index
        L.d("Queue changed completely, replacing queue and position")
        selected.value = emptySet()
        replace(queue, index)
    }

    override fun onNewPlayback(
        parent: MusicParent?,
        queue: List<Song>,
        index: Int,
        isShuffled: Boolean,
    ) {
        if (!isActiveView()) return
        // Entirely new queue -> Replace queue, update index
        L.d("New playback, replacing queue and position")
        selected.value = emptySet()
        replace(queue, index)
    }

    override fun onCleared() {
        playbackManager.removeListener(this)
    }

    /**
     * Start playing the the queue item at the given index.
     *
     * Fork pessoal: numa fila parada, ela passa a tocar a partir dessa música.
     */
    fun goto(adapterIndex: Int) {
        if (adapterIndex !in queue.value.indices) {
            return
        }
        L.d("Going to position $adapterIndex in queue")
        if (isActiveView()) playbackManager.goto(adapterIndex)
        else multiQueue.activate(shownSlot, adapterIndex)
    }

    /** Fork pessoal: toca a fila mostrada de onde ela parou (botão ▶). */
    fun playShown() {
        multiQueue.activate(shownSlot, null)
    }

    /**
     * Remove a queue item at the given index.
     *
     * @param adapterIndex The index of the queue item to play. Does nothing if the index is out of
     *   range.
     */
    fun removeQueueDataItem(adapterIndex: Int) {
        if (adapterIndex !in queue.value.indices) {
            return
        }
        L.d("Removing item $adapterIndex in queue")
        if (isActiveView()) {
            playbackManager.removeQueueItem(adapterIndex)
        } else {
            multiQueue.removeFromStored(shownSlot, listOf(adapterIndex))
            val list = _queue.value.toMutableList().also { it.removeAt(adapterIndex) }
            _queueInstructions.put(UpdateInstructions.Remove(adapterIndex, 1))
            _queue.value = list
            index.value = multiQueue.storedIndex(shownSlot)
            selected.value = emptySet()
        }
    }

    /**
     * Move a queue item from one index to another index.
     *
     * @param adapterFrom The index of the queue item to move.
     * @param adapterTo The destination index for the queue item.
     * @return true if the items were moved, false otherwise.
     */
    fun moveQueueDataItems(adapterFrom: Int, adapterTo: Int): Boolean {
        if (adapterFrom !in queue.value.indices || adapterTo !in queue.value.indices) {
            return false
        }
        L.d("Moving $adapterFrom to $adapterFrom in queue")
        if (isActiveView()) {
            internalOp = true
            try {
                playbackManager.moveQueueItem(adapterFrom, adapterTo)
            } finally {
                internalOp = false
            }
        } else {
            multiQueue.moveInStored(shownSlot, adapterFrom, adapterTo)
            val list = _queue.value.toMutableList()
            list.add(adapterTo, list.removeAt(adapterFrom))
            _queueInstructions.put(UpdateInstructions.Move(adapterFrom, adapterTo))
            _queue.value = list
            index.value = multiQueue.storedIndex(shownSlot)
        }
        return true
    }

    // --- SELEÇÃO (fork pessoal) ---

    fun toggleSelected(position: Int) {
        if (position !in queue.value.indices) return
        val set = selected.value.toMutableSet()
        if (!set.remove(position)) set.add(position)
        selected.value = set
    }

    fun clearSelection() {
        selected.value = emptySet()
    }

    fun selectAll() {
        selected.value = queue.value.indices.toSet()
    }

    fun invertSelection() {
        val set = selected.value
        selected.value = queue.value.indices.filter { it !in set }.toSet()
    }

    fun selectRange() {
        val set = selected.value
        if (set.isEmpty()) return
        selected.value = set + (set.min()..set.max()).toSet()
    }

    fun selectedSongs(): List<Song> =
        selected.value.sorted().mapNotNull { queue.value.getOrNull(it) }

    /** Remove da fila as músicas selecionadas. */
    fun removeSelected() {
        val positions = selected.value.sortedDescending()
        if (positions.isEmpty()) return
        selected.value = emptySet()
        if (isActiveView()) {
            internalOp = true
            try {
                for (p in positions) playbackManager.removeQueueItem(p)
            } finally {
                internalOp = false
            }
            // Várias mudanças de uma vez: a lista é redesenhada inteira.
            _queueInstructions.put(UpdateInstructions.Replace(0))
        } else {
            multiQueue.removeFromStored(shownSlot, positions)
            reloadStored()
        }
    }

    /** Leva as músicas selecionadas para logo depois da música atual, na ordem em que estavam. */
    fun playSelectedNext() {
        val chosen = selected.value.sorted()
        if (chosen.isEmpty()) return
        val current = index.value
        val chosenSet = chosen.toSet()
        val rest = queue.value.indices.filter { it !in chosenSet || it == current }
        val insertAt = rest.indexOf(current) + 1
        val moving = chosen.filter { it != current }
        val order = rest.toMutableList().apply { addAll(insertAt.coerceIn(0, size), moving) }
        applyOrder(order)
        selected.value = moving.map { order.indexOf(it) }.toSet()
    }

    /**
     * Fim de um arrasto que levou a música da posição [start] para [end]. Se ela fazia parte da
     * seleção, as outras selecionadas vêm juntas (as de antes ficam antes dela, as de depois, depois).
     */
    fun onDragEnd(start: Int, end: Int) {
        val chosen = selected.value
        if (start == end && chosen.size <= 1) return
        // Posições, depois do arrasto, das selecionadas (o arrasto deslocou as do meio).
        fun shifted(p: Int) =
            when {
                p == start -> end
                start < end && p in (start + 1)..end -> p - 1
                end < start && p in end until start -> p + 1
                else -> p
            }
        if (start !in chosen || chosen.size <= 1) {
            selected.value = chosen.map(::shifted).toSet()
            return
        }
        val others = chosen.filter { it != start }.sorted()
        val before = others.filter { it < start }.map(::shifted)
        val after = others.filter { it > start }.map(::shifted)
        val othersSet = (before + after).toSet()
        val base = queue.value.indices.filter { it !in othersSet }
        val order = base.flatMap { if (it == end) before + end + after else listOf(it) }
        applyOrder(order)
        selected.value = (before + end + after).map { order.indexOf(it) }.toSet()
    }

    /** Põe a fila mostrada na ordem [order] (lista das posições atuais, na nova ordem). */
    private fun applyOrder(order: List<Int>) {
        val n = queue.value.size
        if (order.size != n || order == order.indices.toList()) return
        if (isActiveView()) {
            internalOp = true
            try {
                val cur = (0 until n).toMutableList()
                for (k in 0 until n) {
                    val p = cur.indexOf(order[k])
                    if (p != k) {
                        playbackManager.moveQueueItem(p, k)
                        cur.add(k, cur.removeAt(p))
                    }
                }
            } finally {
                internalOp = false
            }
            _queueInstructions.put(UpdateInstructions.Replace(0))
        } else {
            multiQueue.reorderStored(shownSlot, order)
            val list = order.map { queue.value[it] }
            _queueInstructions.put(UpdateInstructions.Replace(0))
            _queue.value = list
            index.value = multiQueue.storedIndex(shownSlot)
        }
    }
}

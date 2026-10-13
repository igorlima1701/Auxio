/*
 * Copyright (c) 2021 Auxio Project
 * QueueFragment.kt is part of Auxio.
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

import android.os.Bundle
import android.view.LayoutInflater
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.PopupMenu
import androidx.activity.OnBackPressedCallback
import androidx.core.view.isInvisible
import androidx.core.view.isVisible
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlin.math.min
import org.oxycblt.auxio.R
import org.oxycblt.auxio.databinding.FragmentQueueBinding
import org.oxycblt.auxio.list.EditClickListListener
import org.oxycblt.auxio.music.MusicViewModel
import org.oxycblt.auxio.music.resolve
import org.oxycblt.auxio.music.resolveNames
import org.oxycblt.auxio.playback.formatDurationMs
import org.oxycblt.auxio.playback.PlaybackViewModel
import org.oxycblt.auxio.ui.ViewBindingFragment
import org.oxycblt.auxio.util.collectImmediately
import org.oxycblt.auxio.util.showToast
import org.oxycblt.musikr.Song
import timber.log.Timber as L

/**
 * A [ViewBindingFragment] that displays an editable queue.
 *
 * Fork pessoal: no topo, o nome da fila mostrada (tocar abre a lista das filas), ▶ para tocar uma fila
 * parada e ⋮ com as opções da fila. Segurar uma música começa a seleção de várias.
 *
 * @author Alexander Capehart (OxygenCobalt)
 */
@AndroidEntryPoint
class QueueFragment :
    ViewBindingFragment<FragmentQueueBinding>(),
    EditClickListListener<Song>,
    QueueMenuOpener,
    QueueDragCallback.GroupDragHost {
    private val queueModel: QueueViewModel by viewModels()
    private val playbackModel: PlaybackViewModel by activityViewModels()
    private val musicModel: MusicViewModel by activityViewModels()
    @Inject lateinit var multiQueue: MultiQueue
    private val queueAdapter = QueueAdapter(this)
    private var touchHelper: ItemTouchHelper? = null

    // Fork pessoal: arrasto de várias músicas juntas (as outras marcadas saem da lista até soltar).
    private class GroupDrag(val original: List<Song>, val selected: List<Int>, val rep: Int, val ids: MutableList<Int>)

    private var group: GroupDrag? = null

    // Fork pessoal: busca dentro da fila. [filterMap] = posição na lista filtrada -> posição na fila.
    private var filterQuery = ""
    private var filterMap: List<Int> = emptyList()
    private val selectionBack =
        object : OnBackPressedCallback(false) {
            override fun handleOnBackPressed() {
                queueModel.clearSelection()
            }
        }

    override fun onCreateBinding(inflater: LayoutInflater) = FragmentQueueBinding.inflate(inflater)

    override fun onBindingCreated(binding: FragmentQueueBinding, savedInstanceState: Bundle?) {
        super.onBindingCreated(binding, savedInstanceState)

        // --- UI SETUP ---
        binding.queueRecycler.apply {
            adapter = queueAdapter
            touchHelper =
                ItemTouchHelper(QueueDragCallback(queueModel, this@QueueFragment)).also {
                    it.attachToRecyclerView(this)
                    org.oxycblt.auxio.list.recycler.PreviewSwipe.register(this, it)
                }
        }

        // Sometimes the scroll can change without the listener being updated, so we also
        // check for relayout events.
        binding.queueRecycler.apply {
            addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> updateDivider() }
            addOnScrollListener(
                object : RecyclerView.OnScrollListener() {
                    override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                        updateDivider()
                    }
                }
            )
        }

        binding.queueSwitcher.setOnClickListener { showQueueList() }
        binding.queuePlay.setOnClickListener { queueModel.playShown() }
        binding.queueShuffle.setOnClickListener { queueModel.shuffleShown() }
        binding.queueMore.setOnClickListener { showQueueOptions() }
        binding.queueSelClose.setOnClickListener { queueModel.clearSelection() }
        binding.queueSelAll.setOnClickListener { queueModel.selectAll() }
        binding.queueSelInvert.setOnClickListener { queueModel.invertSelection() }
        binding.queueSelRange.setOnClickListener { queueModel.selectRange() }
        binding.queueSelActions.setOnClickListener { showSelectionActions() }
        binding.queueSearch.addTextChangedListener(
            object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}

                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}

                override fun afterTextChanged(s: android.text.Editable?) {
                    setFilter(s?.toString().orEmpty())
                }
            }
        )
        binding.queueSearchClear.setOnClickListener { binding.queueSearch.setText("") }

        // --- VIEWMODEL SETUP ----
        collectImmediately(
            queueModel.queue,
            queueModel.index,
            playbackModel.isPlaying,
            ::updateQueue,
        )
        collectImmediately(multiQueue.info, queueModel.queue, queueModel.index) { info, _, _ ->
            updateHeader(info)
        }
        collectImmediately(queueModel.selected, ::updateSelection)
    }

    override fun onResume() {
        super.onResume()
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, selectionBack)
    }

    override fun onDestroyBinding(binding: FragmentQueueBinding) {
        super.onDestroyBinding(binding)
        touchHelper = null
        binding.queueRecycler.adapter = null
        // Avoid possible race conditions that could cause a bad instruction to be consumed
        // during list initialization and crash the app. Could happen if the user is fast enough.
        queueModel.queueInstructions.consume()
    }

    override fun onClick(item: Song, viewHolder: RecyclerView.ViewHolder) {
        val pos = realPosition(viewHolder.bindingAdapterPosition)
        // Fork pessoal: com seleção aberta, tocar marca/desmarca.
        if (queueModel.selected.value.isNotEmpty()) {
            queueModel.toggleSelected(pos)
        } else {
            queueModel.goto(pos)
        }
    }

    override fun openQueueMenu(song: Song, index: Int) {
        if (index == RecyclerView.NO_POSITION) return
        QueueItemMenuDialog.show(childFragmentManager, song, realPosition(index))
    }

    override fun onItemLongPress(index: Int) {
        if (index == RecyclerView.NO_POSITION || filterQuery.isNotEmpty()) return
        queueModel.toggleSelected(index)
    }

    /** Posição na fila de uma linha da lista mostrada (que pode estar filtrada pela busca). */
    private fun realPosition(shown: Int) =
        if (filterQuery.isEmpty()) shown else filterMap.getOrElse(shown) { RecyclerView.NO_POSITION }

    override fun onPickUp(viewHolder: RecyclerView.ViewHolder) {
        val helper = requireNotNull(touchHelper) { "ItemTouchHelper was not available" }
        val pos = viewHolder.bindingAdapterPosition
        val chosen = queueModel.selected.value
        if (filterQuery.isNotEmpty() || pos == RecyclerView.NO_POSITION || pos !in chosen || chosen.size < 2) {
            helper.startDrag(viewHolder)
            return
        }
        // Fork pessoal: as outras marcadas saem da lista e a linha pega vira "N músicas".
        val original = queueModel.queue.value
        val selected = chosen.sorted()
        val ids = original.indices.toMutableList()
        for (p in selected.sortedDescending()) {
            if (p == pos) continue
            val idx = ids.indexOf(p)
            ids.removeAt(idx)
            queueAdapter.update(
                ids.map { original[it] },
                org.oxycblt.auxio.list.adapter.UpdateInstructions.Remove(idx, 1),
            )
        }
        group = GroupDrag(original, selected, pos, ids)
        queueAdapter.setSelectedPositions(setOf(ids.indexOf(pos)))
        (viewHolder as? QueueSongViewHolder)?.setGroupLabel(selected.size)
        helper.startDrag(viewHolder)
    }

    override fun groupMove(from: Int, to: Int): Boolean? {
        val g = group ?: return null
        if (from !in g.ids.indices || to !in g.ids.indices) return false
        g.ids.add(to, g.ids.removeAt(from))
        queueAdapter.update(
            g.ids.map { g.original[it] },
            org.oxycblt.auxio.list.adapter.UpdateInstructions.Move(from, to),
        )
        return true
    }

    override val groupActive: Boolean
        get() = group != null

    override fun groupDrop(end: Int): Boolean {
        val g = group ?: return false
        group = null
        // A faixa do grupo vira todas as marcadas, na ordem em que estavam.
        val at = g.ids.indexOf(g.rep).takeIf { it >= 0 } ?: end
        val order = g.ids.toMutableList().apply {
            removeAt(at)
            addAll(at, g.selected)
        }
        val newSelection = g.selected.map { order.indexOf(it) }.toSet()
        // Volta a lista da tela para a fila real (ainda na ordem antiga) e aplica a nova ordem.
        queueAdapter.update(g.original, org.oxycblt.auxio.list.adapter.UpdateInstructions.Replace(0))
        queueModel.reorder(order, newSelection)
        if (order == order.indices.toList()) {
            queueAdapter.setSelectedPositions(newSelection)
        }
        return true
    }

    private fun updateDivider() {
        val binding = requireBinding()
        binding.queueDivider.isInvisible =
            (binding.queueRecycler.layoutManager as LinearLayoutManager)
                .findFirstCompletelyVisibleItemPosition() < 1
    }

    // --- TOPO (fork pessoal) ---

    private fun songCount(n: Int) =
        if (n == 1) getString(R.string.fmt_queue_one_song) else getString(R.string.fmt_queue_songs, n)

    private fun updateHeader(info: MultiQueue.Info) {
        val binding = requireBinding()
        val viewing = info.viewing
        binding.queueName.text = info.names.getOrElse(viewing) { getString(R.string.lbl_queue) }
        val queue = queueModel.queue.value
        val position = queueModel.index.value
        val totalMs = queue.sumOf { it.durationMs }
        val state =
            getString(
                if (viewing == info.active) R.string.lng_queue_state_playing
                else R.string.lng_queue_state_stopped
            )
        // Fork pessoal: posição da música atual / total e a duração da fila, como no Musicolet.
        val where = if (position in queue.indices) "${position + 1} / ${queue.size}" else songCount(queue.size)
        binding.queueCount.text = "$where · ⏱ ${totalMs.formatDurationMs(false)} · $state"
        binding.queuePlay.isVisible = viewing != info.active
    }

    /** Lista das filas (menu suspenso); tocar numa só mostra a fila, sem tocá-la. */
    private fun showQueueList() {
        val info = multiQueue.info.value
        PopupMenu(requireContext(), requireBinding().queueSwitcher).apply {
            for (i in 0 until info.count) {
                val marker = if (i == info.active) "▶ " else "    "
                val title = marker + info.names[i] + "  (" + info.sizes.getOrElse(i) { 0 } + ")"
                menu.add(0, i, i, title).apply {
                    isCheckable = true
                    isChecked = i == info.viewing
                }
            }
            menu.setGroupCheckable(0, true, true)
            setOnMenuItemClickListener { item ->
                multiQueue.view(item.itemId)
                true
            }
            show()
        }
    }

    private fun showQueueOptions() {
        val info = multiQueue.info.value
        val slot = info.viewing
        PopupMenu(requireContext(), requireBinding().queueMore).apply {
            menu.add(0, 1, 0, R.string.lbl_queue_rename)
            menu.add(0, 2, 1, R.string.lbl_queue_move_up).isEnabled = slot > 0
            menu.add(0, 3, 2, R.string.lbl_queue_move_down).isEnabled = slot < info.count - 1
            menu.add(0, 4, 3, R.string.lbl_queue_save_playlist)
            menu.add(0, 5, 4, getString(R.string.lbl_queue_delete_named, info.names[slot]))
                .isEnabled = info.count > 1
            setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    1 -> askName(R.string.lbl_queue_rename, info.names[slot]) {
                        multiQueue.rename(slot, it)
                    }
                    2 -> multiQueue.moveSlot(slot, -1)
                    3 -> multiQueue.moveSlot(slot, +1)
                    4 -> askName(R.string.lbl_queue_save_playlist, info.names[slot]) {
                        multiQueue.saveAsPlaylist(slot, it)
                        requireContext().showToast(getString(R.string.lng_queue_saved_playlist, it))
                    }
                    5 ->
                        MaterialAlertDialogBuilder(requireContext())
                            .setMessage(getString(R.string.lng_queue_delete_confirm, info.names[slot]))
                            .setPositiveButton(R.string.lbl_queue_delete_short) { _, _ ->
                                if (!multiQueue.delete(slot)) {
                                    requireContext().showToast(R.string.err_queue_library)
                                }
                            }
                            .setNegativeButton(R.string.lbl_cancel, null)
                            .show()
                            .window
                            ?.setWindowAnimations(0)
                }
                true
            }
            show()
        }
    }

    private fun askName(title: Int, current: String, onOk: (String) -> Unit) {
        val context = requireContext()
        val input =
            EditText(context).apply {
                setText(current)
                setSingleLine()
                selectAll()
            }
        val pad = (20 * resources.displayMetrics.density).toInt()
        val frame =
            FrameLayout(context).apply {
                setPadding(pad, pad / 2, pad, 0)
                addView(input)
            }
        MaterialAlertDialogBuilder(context)
            .setTitle(title)
            .setView(frame)
            .setPositiveButton(R.string.lbl_tag_save) { _, _ ->
                val text = input.text?.toString()?.trim().orEmpty()
                if (text.isNotEmpty()) onOk(text)
            }
            .setNegativeButton(R.string.lbl_cancel, null)
            .show()
            .window
            ?.setWindowAnimations(0)
    }

    // --- SELEÇÃO (fork pessoal) ---

    private fun updateSelection(selected: Set<Int>) {
        val binding = requireBinding()
        val active = selected.isNotEmpty()
        binding.queueSelectionBar.isVisible = active
        binding.queueHeader.isInvisible = active
        binding.queueSelCount.text = getString(R.string.fmt_queue_selected, selected.size)
        queueAdapter.setSelectedPositions(selected)
        selectionBack.isEnabled = active
    }

    private fun showSelectionActions() {
        PopupMenu(requireContext(), requireBinding().queueSelActions).apply {
            menu.add(0, 1, 0, R.string.lbl_queue_remove)
            menu.add(0, 2, 1, R.string.lbl_play_after_current)
            menu.add(0, 3, 2, R.string.lbl_add_to_queue)
            menu.add(0, 4, 3, R.string.lbl_add_to_playlists)
            setOnMenuItemClickListener { item ->
                val songs = queueModel.selectedSongs()
                when (item.itemId) {
                    1 -> queueModel.removeSelected()
                    2 -> queueModel.playSelectedNext()
                    3 -> {
                        playbackModel.addToQueue(songs)
                        queueModel.clearSelection()
                    }
                    4 -> {
                        musicModel.addToPlaylist(songs)
                        queueModel.clearSelection()
                    }
                }
                true
            }
            show()
        }
    }

    /** Fork pessoal: filtra a fila mostrada pelo texto da busca (título, artista ou álbum). */
    private fun setFilter(text: String) {
        val query = normalize(text.trim())
        val binding = binding ?: return
        binding.queueSearchClear.isVisible = text.isNotEmpty()
        if (query == filterQuery) return
        filterQuery = query
        if (query.isNotEmpty()) queueModel.clearSelection()
        queueAdapter.editable = query.isEmpty()
        applyQueue(queueModel.queue.value, queueModel.index.value, playbackModel.isPlaying.value, replace = true)
    }

    private fun normalize(s: String) =
        java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
            .lowercase()

    private fun applyQueue(queue: List<Song>, index: Int, isPlaying: Boolean, replace: Boolean) {
        val playingNow = isPlaying && queueModel.viewingActive.value
        if (filterQuery.isEmpty()) {
            filterMap = emptyList()
            val instructions = queueModel.queueInstructions.consume()
            queueAdapter.update(
                queue,
                if (replace) org.oxycblt.auxio.list.adapter.UpdateInstructions.Replace(0) else instructions,
            )
            queueAdapter.setPosition(index, playingNow)
            return
        }
        queueModel.queueInstructions.consume()
        val context = requireContext()
        val matches =
            queue.withIndex().filter { (_, song) ->
                val text =
                    song.name.resolve(context) + " " +
                        song.artists.resolveNames(context) + " " +
                        song.album.name.resolve(context)
                normalize(text).contains(filterQuery)
            }
        filterMap = matches.map { it.index }
        queueAdapter.update(matches.map { it.value }, org.oxycblt.auxio.list.adapter.UpdateInstructions.Replace(0))
        queueAdapter.setPosition(filterMap.indexOf(index), playingNow)
    }

    private fun updateQueue(queue: List<Song>, index: Int, isPlaying: Boolean) {
        val binding = requireBinding()
        // Durante o arrasto de várias juntas, a lista da tela é cuidada pelo próprio arrasto.
        if (group != null) return

        applyQueue(queue, index, isPlaying, replace = false)
        if (filterQuery.isNotEmpty()) {
            queueModel.scrollTo.consume()
            return
        }

        // If requested, scroll to a new item (occurs when the index moves)
        val scrollTo = queueModel.scrollTo.consume()
        if (scrollTo != null) {
            val lmm = binding.queueRecycler.layoutManager as LinearLayoutManager
            val start = lmm.findFirstCompletelyVisibleItemPosition()
            val end = lmm.findLastCompletelyVisibleItemPosition()
            val notInitialized =
                start == RecyclerView.NO_POSITION || end == RecyclerView.NO_POSITION
            // When we scroll, we want to scroll to the almost-top so the user can see
            // future songs instead of past songs. The way we have to do this however is
            // dependent on where we have to scroll to get to the currently playing song.
            if (notInitialized || scrollTo < start) {
                // We need to scroll upwards, or initialize the scroll, no need to offset
                L.d("Not scrolling downwards, no offset needed")
                binding.queueRecycler.scrollToPosition(scrollTo.coerceAtLeast(0))
            } else if (scrollTo > end) {
                // We need to scroll downwards, we need to offset by a screen of songs.
                // This does have some error due to how many completely visible items on-screen
                // can vary. This is considered okay.
                val offset = scrollTo + (end - start)
                L.d("Scrolling downwards, offsetting by $offset")
                binding.queueRecycler.scrollToPosition(min(queue.lastIndex, offset))
            }
        }
    }
}

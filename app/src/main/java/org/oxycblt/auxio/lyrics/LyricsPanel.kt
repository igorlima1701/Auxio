/*
 * Copyright (c) 2026 Auxio Project
 * LyricsPanel.kt is part of Auxio.
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

import android.os.SystemClock
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.lifecycle.LifecycleCoroutineScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.oxycblt.auxio.R
import org.oxycblt.musikr.Song

/**
 * Fork pessoal: a letra no lugar da capa, no player.
 * - Com tempos marcados, a linha atual fica destacada e a letra acompanha a música; tocar numa
 *   linha pula a música para ela. Se você rolar a letra com o dedo, ela espera alguns segundos antes
 *   de voltar a acompanhar.
 * - Sem tempos, a letra fica inteira para ler com a rolagem; tocar nela volta para a capa.
 * - Os botões de cima voltam para a capa e abrem o editor.
 */
class LyricsPanel(
    private val container: FrameLayout,
    private val scope: LifecycleCoroutineScope,
    private val onSeek: (Long) -> Unit,
    private val onClose: () -> Unit,
    private val onEdit: (Song) -> Unit,
    private val onRequestAccess: (Song) -> Unit,
) {
    private val context = container.context
    private val adapter = LineAdapter()
    private val list: RecyclerView
    private val empty: LinearLayout
    private val emptyText: TextView
    private val emptyDetail: TextView
    private val accessButton: com.google.android.material.button.MaterialButton
    private var song: Song? = null
    private var lyrics = Lyrics(emptyList(), false)
    private var current = -1
    private var loadJob: Job? = null
    private var loadedVersion = -1
    private var lastUserScroll = 0L

    init {
        container.removeAllViews()
        val body = FrameLayout(context)
        list =
            RecyclerView(context).apply {
                layoutManager = LinearLayoutManager(context)
                adapter = this@LyricsPanel.adapter
                itemAnimator = null
                overScrollMode = View.OVER_SCROLL_NEVER
                clipToPadding = false
                isVerticalScrollBarEnabled = true
                // Toque fora das linhas (nos espaços de cima e de baixo) também volta para a capa.
                val detector =
                    android.view.GestureDetector(
                        context,
                        object : android.view.GestureDetector.SimpleOnGestureListener() {
                            override fun onSingleTapUp(e: android.view.MotionEvent) = true
                        },
                    )
                addOnItemTouchListener(
                    object : RecyclerView.SimpleOnItemTouchListener() {
                        override fun onInterceptTouchEvent(rv: RecyclerView, e: android.view.MotionEvent): Boolean {
                            if (detector.onTouchEvent(e) && rv.findChildViewUnder(e.x, e.y) == null) {
                                onClose()
                            }
                            return false
                        }
                    }
                )
                addOnScrollListener(
                    object : RecyclerView.OnScrollListener() {
                        override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                            if (newState == RecyclerView.SCROLL_STATE_DRAGGING) {
                                lastUserScroll = SystemClock.uptimeMillis()
                            }
                        }
                    }
                )
                addOnLayoutChangeListener { v, _, top, _, bottom, _, oldTop, _, oldBottom ->
                    if (bottom - top != oldBottom - oldTop) {
                        // Espaço em cima e embaixo para a primeira e a última linha poderem ficar
                        // perto do meio.
                        val pad = (bottom - top) * 2 / 5
                        v.setPadding(0, pad, 0, pad)
                        v.post { scrollToCurrent(force = true) }
                    }
                }
            }
        body.addView(list, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        empty =
            LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                isVisible = false
                val pad = (20 * resources.displayMetrics.density).toInt()
                setPadding(pad, 0, pad, 0)
                setOnClickListener { onClose() }
            }
        emptyText =
            TextView(context).apply {
                gravity = Gravity.CENTER
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                alpha = 0.8f
            }
        emptyDetail =
            TextView(context).apply {
                gravity = Gravity.CENTER
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                alpha = 0.55f
                setPadding(0, (8 * resources.displayMetrics.density).toInt(), 0, 0)
            }
        accessButton =
            com.google.android.material.button.MaterialButton(context).apply {
                setText(R.string.lbl_lyrics_grant)
                isVisible = false
                setOnClickListener { song?.let(onRequestAccess) }
            }
        empty.addView(emptyText)
        empty.addView(emptyDetail)
        empty.addView(
            accessButton,
            LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                )
                .apply { topMargin = (12 * context.resources.displayMetrics.density).toInt() },
        )
        body.addView(empty, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        container.addView(body, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        // Fork pessoal: só o lápis, pequeno, flutuando no canto (sem a linha de botões).
        val pencil = iconButton(R.drawable.ic_edit_24, R.string.lbl_lyrics_edit) { song?.let(onEdit) }
        pencil.alpha = 0.7f
        val size = (40 * context.resources.displayMetrics.density).toInt()
        container.addView(pencil, FrameLayout.LayoutParams(size, size, Gravity.TOP or Gravity.END))
    }

    private fun iconButton(icon: Int, desc: Int, onClick: () -> Unit) =
        ImageButton(context).apply {
            setImageResource(icon)
            contentDescription = context.getString(desc)
            val ripple = TypedValue()
            context.theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, ripple, true)
            setBackgroundResource(ripple.resourceId)
            setOnClickListener { onClick() }
        }

    /** Mostra a letra de [song] (lê de novo só se a música ou o .lrc mudaram). */
    fun bind(song: Song?) {
        if (song == null) return
        if (song == this.song && loadedVersion == LyricsFiles.version.value) return
        this.song = song
        loadedVersion = LyricsFiles.version.value
        loadJob?.cancel()
        lyrics = Lyrics(emptyList(), false)
        current = -1
        adapter.notifyDataSetChanged()
        empty.isVisible = false
        loadJob =
            scope.launch {
                val loaded = withContext(Dispatchers.IO) { LyricsFiles.load(context, song) }
                if (this@LyricsPanel.song != song) return@launch
                show(loaded)
            }
    }

    /** Recarrega a letra atual (depois de editar). */
    fun reload() {
        val s = song ?: return
        song = null
        bind(s)
    }

    private fun show(loaded: LoadedLyrics) {
        lyrics = Lyrics.parse(loaded.text)
        current = -1
        val none = lyrics.lines.isEmpty()
        empty.isVisible = none
        list.isVisible = !none
        emptyText.text = context.getString(R.string.lng_lyrics_none)
        emptyDetail.text = loaded.detail ?: ""
        emptyDetail.isVisible = !loaded.detail.isNullOrEmpty()
        accessButton.isVisible = loaded.needsAccess
        adapter.notifyDataSetChanged()
        list.scrollToPosition(0)
        lastUserScroll = 0
    }

    /** Posição da música, para destacar e acompanhar a linha atual. */
    fun onPosition(positionMs: Long) {
        if (!lyrics.synced) return
        val idx = lyrics.indexAt(positionMs)
        if (idx == current) return
        val old = current
        current = idx
        if (old >= 0) adapter.notifyItemChanged(old)
        if (idx >= 0) adapter.notifyItemChanged(idx)
        scrollToCurrent(force = false)
    }

    private fun scrollToCurrent(force: Boolean) {
        if (!lyrics.synced) return
        if (!force && SystemClock.uptimeMillis() - lastUserScroll < USER_SCROLL_PAUSE_MS) return
        if (list.scrollState != RecyclerView.SCROLL_STATE_IDLE && !force) return
        val manager = list.layoutManager as LinearLayoutManager
        // Sem animação: a linha atual vai direto para perto do meio.
        manager.scrollToPositionWithOffset(current.coerceAtLeast(0), 0)
    }

    private inner class LineAdapter : RecyclerView.Adapter<LineHolder>() {
        override fun getItemCount() = lyrics.lines.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): LineHolder {
            val padH = (20 * parent.resources.displayMetrics.density).toInt()
            val padV = (7 * parent.resources.displayMetrics.density).toInt()
            val view =
                TextView(parent.context).apply {
                    layoutParams =
                        RecyclerView.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                        )
                    gravity = Gravity.CENTER_HORIZONTAL
                    setPadding(padH, padV, padH, padV)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 19f)
                    val ripple = TypedValue()
                    context.theme.resolveAttribute(android.R.attr.selectableItemBackground, ripple, true)
                    setBackgroundResource(ripple.resourceId)
                }
            return LineHolder(view)
        }

        override fun onBindViewHolder(holder: LineHolder, position: Int) {
            val line = lyrics.lines[position]
            val view = holder.text
            view.text = line.text.ifBlank { "♪" }
            view.alpha =
                when {
                    !lyrics.synced -> 1f
                    position == current -> 1f
                    else -> 0.4f
                }
            // Fork pessoal: um toque volta para a capa; segurar uma linha (letra sincronizada) pula
            // a música para ela.
            view.setOnClickListener { onClose() }
            view.setOnLongClickListener {
                val time = line.timeMs
                if (lyrics.synced && time != null) {
                    onSeek(time)
                    true
                } else {
                    false
                }
            }
        }
    }

    private class LineHolder(val text: TextView) : RecyclerView.ViewHolder(text)

    private companion object {
        const val USER_SCROLL_PAUSE_MS = 3000L
    }
}

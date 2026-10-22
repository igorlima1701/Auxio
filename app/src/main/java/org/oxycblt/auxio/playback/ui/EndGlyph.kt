/*
 * Copyright (c) 2026 Auxio Project
 * EndGlyph.kt is part of Auxio.
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

package org.oxycblt.auxio.playback.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import androidx.core.content.ContextCompat
import org.oxycblt.auxio.R
import org.oxycblt.auxio.playback.EndMode

/**
 * Fork pessoal: desenhos do "o que fazer quando acaba" (1, 1→, 1‖→, repetir 1, Q, Q→, repetir Q),
 * no estilo do Music OLED. Cada desenho ocupa uma caixa de 24×24 unidades.
 */
enum class EndGlyph {
    SONG_STOP,
    SONG_LOAD_NEXT,
    SONG_PLAY_NEXT,
    SONG_REPEAT,
    QUEUE_STOP,
    QUEUE_NEXT,
    QUEUE_REPEAT;

    companion object {
        fun of(song: EndMode.SongEnd) =
            when (song) {
                EndMode.SongEnd.STOP -> SONG_STOP
                EndMode.SongEnd.LOAD_NEXT -> SONG_LOAD_NEXT
                EndMode.SongEnd.PLAY_NEXT -> SONG_PLAY_NEXT
                EndMode.SongEnd.REPEAT -> SONG_REPEAT
            }

        fun of(queue: EndMode.QueueEnd) =
            when (queue) {
                EndMode.QueueEnd.STOP -> QUEUE_STOP
                EndMode.QueueEnd.NEXT_QUEUE -> QUEUE_NEXT
                EndMode.QueueEnd.REPEAT -> QUEUE_REPEAT
            }

        /** O que o botão do player mostra para [state]. */
        fun forState(state: EndMode.State): List<EndGlyph> =
            if (state.advanced) listOf(of(state.songEnd), of(state.queueEnd))
            else
                listOf(
                    when (state.repeatMode) {
                        org.oxycblt.auxio.playback.state.RepeatMode.NONE -> SONG_PLAY_NEXT
                        org.oxycblt.auxio.playback.state.RepeatMode.ALL -> QUEUE_REPEAT
                        org.oxycblt.auxio.playback.state.RepeatMode.TRACK -> SONG_REPEAT
                    }
                )
    }
}

/**
 * Desenha uma ou mais [EndGlyph] lado a lado (com um traço fino entre elas), em [sizePx] de altura.
 */
class EndGlyphDrawable(
    context: Context,
    private val glyphs: List<EndGlyph>,
    private val sizePx: Int,
    private var color: Int,
) : Drawable() {
    private val gap = 4f // unidades entre um desenho e outro (com o traço no meio)
    private val unitsWide = glyphs.size * 24f + (glyphs.size - 1) * gap
    private val text =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
        }
    private val stroke =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val repeatOne = ContextCompat.getDrawable(context, R.drawable.ic_repeat_one_24)?.mutate()
    private val repeatAll = ContextCompat.getDrawable(context, R.drawable.ic_repeat_on_24)?.mutate()

    override fun getIntrinsicHeight() = sizePx

    override fun getIntrinsicWidth() = (sizePx * unitsWide / 24f).toInt()

    fun setColor(newColor: Int) {
        color = newColor
        invalidateSelf()
    }

    override fun draw(canvas: Canvas) {
        val b = bounds
        val unit = b.height() / 24f
        val save = canvas.save()
        canvas.translate(b.left + (b.width() - unitsWide * unit) / 2f, b.top.toFloat())
        canvas.scale(unit, unit)
        text.color = color
        stroke.color = color
        fill.color = color
        stroke.strokeWidth = 1.8f
        glyphs.forEachIndexed { i, glyph ->
            if (i > 0) {
                // Traço fino separando "1" e "Q".
                fill.alpha = 120
                canvas.drawRect(-gap / 2f - 0.4f, 4f, -gap / 2f + 0.4f, 20f, fill)
                fill.alpha = 255
            }
            drawGlyph(canvas, glyph, unit)
            canvas.translate(24f + gap, 0f)
        }
        canvas.restoreToCount(save)
    }

    private fun arrow(canvas: Canvas) {
        canvas.drawLine(3f, 18f, 20.5f, 18f, stroke)
        canvas.drawLine(20.5f, 18f, 17f, 14.8f, stroke)
        canvas.drawLine(20.5f, 18f, 17f, 21.2f, stroke)
    }

    private fun drawGlyph(canvas: Canvas, glyph: EndGlyph, unit: Float) {
        when (glyph) {
            EndGlyph.SONG_STOP -> big(canvas, "1")
            EndGlyph.QUEUE_STOP -> big(canvas, "Q")
            EndGlyph.SONG_PLAY_NEXT -> small(canvas, "1", 9f).also { arrow(canvas) }
            EndGlyph.QUEUE_NEXT -> small(canvas, "Q", 9f).also { arrow(canvas) }
            EndGlyph.SONG_LOAD_NEXT -> {
                small(canvas, "1", 7f)
                // Sinal de pausa ao lado do "1".
                canvas.drawRect(11.5f, 3.5f, 13.3f, 13f, fill)
                canvas.drawRect(15f, 3.5f, 16.8f, 13f, fill)
                arrow(canvas)
            }
            EndGlyph.SONG_REPEAT -> icon(canvas, repeatOne, unit)
            EndGlyph.QUEUE_REPEAT -> {
                icon(canvas, repeatAll, unit)
                text.textSize = 8.5f
                canvas.drawText("Q", 12f, 15.2f, text)
            }
        }
    }

    private fun big(canvas: Canvas, s: String) {
        text.textSize = 19f
        canvas.drawText(s, 12f, 19f, text)
    }

    private fun small(canvas: Canvas, s: String, x: Float) {
        text.textSize = 12.5f
        canvas.drawText(s, x, 12.5f, text)
    }

    private fun icon(canvas: Canvas, drawable: Drawable?, unit: Float) {
        drawable ?: return
        // O ícone é desenhado em pixels; desfaz a escala só para ele.
        val save = canvas.save()
        canvas.scale(1f / unit, 1f / unit)
        val px = (24f * unit).toInt()
        drawable.setBounds(0, 0, px, px)
        drawable.setTint(color)
        drawable.draw(canvas)
        canvas.restoreToCount(save)
    }

    override fun setAlpha(alpha: Int) {
        text.alpha = alpha
        stroke.alpha = alpha
        fill.alpha = alpha
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        text.colorFilter = colorFilter
        stroke.colorFilter = colorFilter
        fill.colorFilter = colorFilter
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}

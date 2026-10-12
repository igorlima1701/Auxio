/*
 * Copyright (c) 2026 Auxio Project
 * SwipeNavFrameLayout.kt is part of Auxio.
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

package org.oxycblt.auxio.ui

import android.content.Context
import android.graphics.Rect
import android.os.SystemClock
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.widget.FrameLayout
import androidx.annotation.AttrRes
import kotlin.math.abs

/**
 * Fork pessoal: página que percebe um deslize horizontal rápido para trocar de página, sem
 * atrapalhar os toques dos filhos (só observa os eventos, nunca os consome).
 *
 * [onSwipe] recebe +1 quando o dedo vai da direita para a esquerda (próxima página) e -1 no sentido
 * contrário (página anterior). Deslizes que começam sobre [excludedIds] (ex.: capa e barra de
 * progresso do player) são ignorados.
 */
class SwipeNavFrameLayout
@JvmOverloads
constructor(context: Context, attrs: AttributeSet? = null, @AttrRes defStyleAttr: Int = 0) :
    FrameLayout(context, attrs, defStyleAttr) {
    var onSwipe: ((Int) -> Unit)? = null
    /** Chamado no início de cada toque; retornar false ignora esse gesto. */
    var shouldTrack: (() -> Boolean)? = null
    var excludedIds: List<Int> = emptyList()

    private val minDistance = 72 * resources.displayMetrics.density
    private var downX = 0f
    private var downY = 0f
    private var downTime = 0L
    private var tracking = false
    private val hitRect = Rect()
    private val location = IntArray(2)

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x
                downY = ev.y
                downTime = SystemClock.uptimeMillis()
                tracking = (shouldTrack?.invoke() ?: true) && !isOverExcluded(ev)
            }
            MotionEvent.ACTION_POINTER_DOWN -> tracking = false
            MotionEvent.ACTION_UP -> {
                if (tracking) {
                    val dx = ev.x - downX
                    val dy = ev.y - downY
                    val elapsed = SystemClock.uptimeMillis() - downTime
                    if (abs(dx) >= minDistance && abs(dx) > abs(dy) * 2 && elapsed < MAX_DURATION_MS) {
                        val direction = if (dx < 0) 1 else -1
                        post { onSwipe?.invoke(direction) }
                    }
                }
                tracking = false
            }
            MotionEvent.ACTION_CANCEL -> tracking = false
        }
        return super.dispatchTouchEvent(ev)
    }

    private fun isOverExcluded(ev: MotionEvent): Boolean {
        getLocationInWindow(location)
        val rawX = (ev.x + location[0]).toInt()
        val rawY = (ev.y + location[1]).toInt()
        for (id in excludedIds) {
            val view = findViewById<View>(id) ?: continue
            if (!view.isShown) continue
            view.getGlobalVisibleRect(hitRect)
            if (hitRect.contains(rawX, rawY)) return true
        }
        return false
    }

    // Repassa as margens da tela aos filhos sem consumi-las.
    override fun dispatchApplyWindowInsets(insets: WindowInsets): WindowInsets {
        super.dispatchApplyWindowInsets(insets)
        return insets
    }

    private companion object {
        const val MAX_DURATION_MS = 600L
    }
}

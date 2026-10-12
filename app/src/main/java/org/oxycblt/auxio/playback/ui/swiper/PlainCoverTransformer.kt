/*
 * Copyright (c) 2026 Auxio Project
 * PlainCoverTransformer.kt is part of Auxio.
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
 
package org.oxycblt.auxio.playback.ui.swiper

import android.graphics.RectF
import android.view.View
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.carousel.MaskableFrameLayout

/**
 * Fork pessoal: as capas do player passam lado a lado, sem o efeito carrossel. A máscara de cada
 * página precisa ser definida (sem ela a capa fica invisível), então ela cobre a página inteira.
 */
class PlainCoverTransformer : ViewPager2.PageTransformer {
    override fun transformPage(page: View, position: Float) {
        val maskable = page as? MaskableFrameLayout ?: return
        val width = page.width.toFloat()
        val height = page.height.toFloat()
        if (width <= 0f || height <= 0f) return
        page.translationX = 0f
        page.alpha = 1f
        maskable.setMaskRectF(RectF(0f, 0f, width, height))
        maskable.getChildAt(0)?.translationX = 0f
    }
}

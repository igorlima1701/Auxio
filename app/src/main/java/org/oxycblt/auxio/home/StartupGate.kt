/*
 * Copyright (c) 2026 Auxio Project
 * StartupGate.kt is part of Auxio.
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
 
package org.oxycblt.auxio.home

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Fork pessoal: tela de carregamento da abertura. Fica na frente até a biblioteca estar montada e
 * as capas das primeiras telas do grid prontas na memória; aí o grid aparece já liso.
 *
 * Progresso: até 85% é a leitura da biblioteca; o resto, as capas.
 */
object StartupGate {
    /** 0..1. */
    val progress: StateFlow<Float>
        field = MutableStateFlow(0f)

    /** A lista de álbuns já chegou à tela inicial. */
    val libraryReady: StateFlow<Boolean>
        field = MutableStateFlow(false)

    /** Pode tirar a tela de carregamento. */
    val ready: StateFlow<Boolean>
        field = MutableStateFlow(false)

    fun libraryProgress(fraction: Float) {
        if (ready.value) return
        val p = (fraction.coerceIn(0f, 1f) * LIBRARY_SHARE)
        if (p > progress.value) progress.value = p
    }

    fun markLibraryReady() {
        if (libraryReady.value) return
        if (progress.value < LIBRARY_SHARE) progress.value = LIBRARY_SHARE
        libraryReady.value = true
    }

    fun coversProgress(fraction: Float) {
        if (ready.value) return
        val p = LIBRARY_SHARE + fraction.coerceIn(0f, 1f) * (1f - LIBRARY_SHARE)
        if (p > progress.value) progress.value = p
    }

    fun markReady() {
        if (ready.value) return
        progress.value = 1f
        libraryReady.value = true
        ready.value = true
    }

    private const val LIBRARY_SHARE = 0.85f
}

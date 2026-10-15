/*
 * Copyright (c) 2026 Auxio Project
 * QuickReadCache.kt is part of Auxio.
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
 
package org.oxycblt.auxio.music.shim

import org.oxycblt.musikr.cache.CacheResult
import org.oxycblt.musikr.cache.CachedFile
import org.oxycblt.musikr.cache.MutableCache
import org.oxycblt.musikr.fs.File

/**
 * Fork pessoal: cache só de leitura para a biblioteca rápida da abertura. Não grava nem limpa nada
 * (a conferência completa, logo depois, cuida disso), para a biblioteca aparecer mais cedo.
 */
class QuickReadCache(private val inner: MutableCache) : MutableCache {
    override suspend fun read(file: File): CacheResult = inner.read(file)

    override suspend fun write(cachedFile: CachedFile) {}

    override suspend fun writeAll(cachedFiles: List<CachedFile>) {}

    override suspend fun cleanup(excluding: List<CachedFile>) {}
}

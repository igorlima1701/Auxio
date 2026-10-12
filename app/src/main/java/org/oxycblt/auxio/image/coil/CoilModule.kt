/*
 * Copyright (c) 2023 Auxio Project
 * CoilModule.kt is part of Auxio.
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
 
package org.oxycblt.auxio.image.coil

import android.content.Context
import coil3.ImageLoader
import coil3.memory.MemoryCache
import coil3.request.CachePolicy
import coil3.request.transitionFactory
import coil3.transition.Transition
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
class CoilModule {
    @Singleton
    @Provides
    fun imageLoader(
        @ApplicationContext context: Context,
        coverKeyer: CoverFetcher.Keyer,
        coverFactory: CoverFetcher.Factory,
        galleryKeyer: GalleryComposeFetcher.Keyer,
        galleryFetcherFactory: GalleryComposeFetcher.Factory,
        smatteringKeyer: SmatteringCompositionFetcher.Keyer,
        smatteringFetcherFactory: SmatteringCompositionFetcher.Factory,
        stackKeyer: StackCompositionFetcher.Keyer,
        stackFetcherFactory: StackCompositionFetcher.Factory,
        gridThumbKeyer: GridThumbFetcher.Keyer,
        gridThumbFactory: GridThumbFetcher.Factory,
    ) =
        ImageLoader.Builder(context)
            .components {
                add(coverKeyer)
                add(coverFactory)
                add(galleryKeyer)
                add(galleryFetcherFactory)
                add(smatteringKeyer)
                add(smatteringFetcherFactory)
                add(stackKeyer)
                add(stackFetcherFactory)
                add(gridThumbKeyer)
                add(gridThumbFactory)
            }
            // Fork pessoal: sem fade; a capa aparece de uma vez assim que fica pronta.
            .transitionFactory(Transition.Factory.NONE)
            // Not downloading anything, so no disk-caching
            .diskCachePolicy(CachePolicy.DISABLED)
            // Fork pessoal: cache de memória maior, para a grade de capas voltar sem recarregar.
            .memoryCache { MemoryCache.Builder().maxSizePercent(context, 0.35).build() }
            .build()
}

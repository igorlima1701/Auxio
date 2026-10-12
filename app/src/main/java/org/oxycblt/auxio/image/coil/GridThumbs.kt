/*
 * Copyright (c) 2026 Auxio Project
 * GridThumbs.kt is part of Auxio.
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
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import coil3.ImageLoader
import coil3.decode.DataSource
import coil3.decode.ImageSource
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.SourceFetchResult
import coil3.key.Keyer as CoilKeyer
import coil3.request.Options
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.min
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okio.FileSystem
import okio.Path.Companion.toOkioPath
import org.oxycblt.musikr.Album
import org.oxycblt.musikr.covers.Cover
import timber.log.Timber as L

/**
 * A capa "principal" de um álbum: a que aparece em mais faixas. Mesmo critério usado pelo
 * [org.oxycblt.auxio.image.CoverView].
 */
fun Album.primaryCover(): Cover? =
    covers.covers.groupBy { it.id }.maxByOrNull { it.value.size }?.value?.firstOrNull()

/**
 * Pedido de miniatura quadrada de [px] x [px] pixels para a grade de álbuns. As miniaturas ficam
 * guardadas em disco já no tamanho exato da célula, então abrir uma é muito mais leve do que
 * decodificar a capa original.
 */
data class GridThumb(val cover: Cover, val px: Int)

/** Guarda as miniaturas da grade em disco e gera as que faltam. */
@Singleton
class GridThumbStore @Inject constructor(@ApplicationContext context: Context) {
    private val dir = File(context.filesDir, "grid_thumbs").apply { mkdirs() }
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)

    fun file(cover: Cover, px: Int): File {
        val safeId = cover.id.replace(UNSAFE_CHARS, "_").takeLast(120)
        return File(dir, "${safeId}_$px.jpg")
    }

    fun has(cover: Cover, px: Int): Boolean = file(cover, px).length() > 0

    /** Devolve o arquivo da miniatura, gerando-o se ainda não existir. Null se a capa falhar. */
    suspend fun ensure(cover: Cover, px: Int): File? {
        val file = file(cover, px)
        if (file.length() > 0) return file
        return withContext(Dispatchers.IO) {
            try {
                generate(cover, px, file)
            } catch (e: Exception) {
                L.w("Falha ao gerar miniatura de ${cover.id}: $e")
                null
            }
        }
    }

    private suspend fun generate(cover: Cover, px: Int, target: File): File? {
        val bytes = cover.open()?.use { it.readBytes() } ?: return null

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val side = min(bounds.outWidth, bounds.outHeight)
        if (side <= 0) return null

        // Decodifica já reduzida (potência de 2) sem ficar menor que a célula.
        var sample = 1
        while (side / (sample * 2) >= px) sample *= 2
        val decoded =
            BitmapFactory.decodeByteArray(
                bytes,
                0,
                bytes.size,
                BitmapFactory.Options().apply { inSampleSize = sample },
            ) ?: return null

        // Recorte central quadrado, redimensionado para o tamanho exato da célula.
        val cropSide = min(decoded.width, decoded.height)
        val left = (decoded.width - cropSide) / 2
        val top = (decoded.height - cropSide) / 2
        val thumb = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
        Canvas(thumb)
            .drawBitmap(
                decoded,
                Rect(left, top, left + cropSide, top + cropSide),
                Rect(0, 0, px, px),
                paint,
            )
        decoded.recycle()

        val tmp = File(target.parentFile, "${target.name}.${System.nanoTime()}.tmp")
        tmp.outputStream().use { thumb.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        thumb.recycle()
        if (!tmp.renameTo(target)) {
            tmp.delete()
        }
        return target.takeIf { it.length() > 0 }
    }

    private companion object {
        val UNSAFE_CHARS = Regex("[^A-Za-z0-9._-]")
    }
}

/**
 * Pré-carregamento: gera em segundo plano, uma de cada vez, as miniaturas que ainda faltam para
 * todos os álbuns. Depois da primeira vez, a grade só lê arquivos pequenos já prontos.
 */
@Singleton
class GridThumbWarmer @Inject constructor(private val store: GridThumbStore) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private var lastAlbums: List<Album>? = null
    private var lastPx = -1

    fun warm(albums: List<Album>, px: Int) {
        if (px <= 0 || (albums === lastAlbums && px == lastPx)) return
        lastAlbums = albums
        lastPx = px
        job?.cancel()
        job =
            scope.launch {
                // Deixa a abertura do app terminar antes de começar.
                delay(1500)
                for (album in albums) {
                    ensureActive()
                    val cover = album.primaryCover() ?: continue
                    if (!store.has(cover, px)) {
                        store.ensure(cover, px)
                    }
                }
            }
    }
}

class GridThumbFetcher private constructor(
    private val thumb: GridThumb,
    private val store: GridThumbStore,
) : Fetcher {
    override suspend fun fetch(): FetchResult? {
        val file = store.ensure(thumb.cover, thumb.px) ?: return null
        return SourceFetchResult(
            source = ImageSource(file.toOkioPath(), FileSystem.SYSTEM),
            mimeType = "image/jpeg",
            dataSource = DataSource.DISK,
        )
    }

    class Factory @Inject constructor(private val store: GridThumbStore) :
        Fetcher.Factory<GridThumb> {
        override fun create(data: GridThumb, options: Options, imageLoader: ImageLoader) =
            GridThumbFetcher(data, store)
    }

    class Keyer @Inject constructor() : CoilKeyer<GridThumb> {
        override fun key(data: GridThumb, options: Options) =
            "grid:${data.cover.id}:${data.px}&${options.size}"
    }
}

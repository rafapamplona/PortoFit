package com.example.portofit.data.photo

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.UUID

/**
 * Implementação Android de [PhotoDrafts].
 *
 * - Aceita os formatos que o aparelho sabe descodificar (JPEG, PNG, WebP, HEIF/AVIF conforme a versão).
 * - Lê as dimensões antes de descodificar e descodifica já reduzida (ImageDecoder.setTargetSize no Android 9+;
 *   BitmapFactory com inSampleSize antes disso); a orientação EXIF é aplicada (o ImageDecoder aplica-a sozinho;
 *   no Android 8 lê-se com ExifInterface).
 * - Grava um JPEG (qualidade 85, lado maior até 1600 px) na pasta privada `cache/photo-drafts/`.
 * - Tudo em [io], fora da thread principal.
 */
class AndroidPhotoDrafts(
    private val context: Context,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : PhotoDrafts {

    private val dir: File get() = File(context.cacheDir, DIR).apply { mkdirs() }
    private val authority = "${context.packageName}.photos"

    override fun newCaptureTarget(): CaptureTarget? = try {
        val name = "capture-${UUID.randomUUID()}.jpg"
        val file = File(dir, name).apply { createNewFile() }
        CaptureTarget(name, FileProvider.getUriForFile(context, authority, file).toString())
    } catch (e: IOException) {
        null
    } catch (e: IllegalArgumentException) {
        null
    }

    override suspend fun prepareFromUri(uri: String): PrepareResult = withContext(io) {
        prepare(Input.FromUri(uri.toUri()))
    }

    override suspend fun prepareFromCapture(captureName: String): PrepareResult = withContext(io) {
        val capture = file(captureName)
        try {
            if (capture == null || capture.length() == 0L) PrepareResult.Failure(PhotoError.CAPTURE_CANCELLED)
            else prepare(Input.FromFile(capture))
        } finally {
            // A captura completa já não é precisa: fica só a versão preparada.
            capture?.delete()
        }
    }

    override fun exists(name: String): Boolean = file(name)?.isFile == true

    override fun file(name: String): File? = if (PhotoSizing.isDraftName(name)) File(dir, name) else null

    override fun delete(name: String) {
        file(name)?.delete()
    }

    override fun deleteStale(olderThanMillis: Long) {
        val limit = System.currentTimeMillis() - olderThanMillis
        dir.listFiles()?.filter { it.isFile && PhotoSizing.isDraftName(it.name) && it.lastModified() < limit }?.forEach { it.delete() }
    }

    /** Origem da imagem: URI do Photo Picker ou ficheiro da câmara. */
    private sealed interface Input {
        class FromUri(val uri: Uri) : Input
        class FromFile(val file: File) : Input
    }

    private fun open(input: Input): InputStream = when (input) {
        is Input.FromUri -> context.contentResolver.openInputStream(input.uri) ?: throw IOException("sem conteúdo")
        is Input.FromFile -> input.file.inputStream()
    }

    private fun prepare(input: Input): PrepareResult = try {
        writePrepared(decode(input))
    } catch (e: CancellationException) {
        throw e
    } catch (e: TooSmallException) {
        PrepareResult.Failure(PhotoError.TOO_SMALL)
    } catch (e: Exception) {
        // IOException, formato não suportado, dados inválidos, etc.
        PrepareResult.Failure(PhotoError.UNREADABLE)
    } catch (e: OutOfMemoryError) {
        PrepareResult.Failure(PhotoError.UNREADABLE)
    }

    /** Grava o JPEG preparado na pasta da app. */
    private fun writePrepared(decoded: Bitmap?): PrepareResult {
        val bitmap = decoded ?: return PrepareResult.Failure(PhotoError.UNREADABLE)
        try {
            val name = "photo-${UUID.randomUUID()}.jpg"
            val file = File(dir, name)
            try {
                FileOutputStream(file).use { out ->
                    if (!bitmap.compress(Bitmap.CompressFormat.JPEG, PhotoSizing.JPEG_QUALITY, out)) throw IOException("compressão falhou")
                    out.fd.sync()
                }
            } catch (e: IOException) {
                file.delete()
                return PrepareResult.Failure(PhotoError.STORAGE)
            }
            return PrepareResult.Success(PreparedPhoto(name, bitmap.width, bitmap.height))
        } finally {
            bitmap.recycle()
        }
    }

    private class TooSmallException : Exception()

    /** Bitmap já orientado e com o tamanho final, ou null se não for uma imagem. */
    private fun decode(input: Input): Bitmap? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            // A fonte é lida pelo próprio descodificador: o ficheiro inteiro nunca fica em memória.
            val source = when (input) {
                is Input.FromUri -> ImageDecoder.createSource(context.contentResolver, input.uri)
                is Input.FromFile -> ImageDecoder.createSource(input.file)
            }
            return ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                // Dimensões já orientadas (o ImageDecoder aplica a orientação EXIF).
                val (w, h) = PhotoSizing.target(info.size.width, info.size.height) ?: throw TooSmallException()
                decoder.setTargetSize(w, h)
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
        }
        // Android 8: dimensões primeiro, descodificação reduzida, depois orientação e tamanho exato.
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        open(input).use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val orientation = open(input).use { ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) }
        val swap = orientation in setOf(ExifInterface.ORIENTATION_ROTATE_90, ExifInterface.ORIENTATION_ROTATE_270, ExifInterface.ORIENTATION_TRANSPOSE, ExifInterface.ORIENTATION_TRANSVERSE)
        val orientedW = if (swap) bounds.outHeight else bounds.outWidth
        val orientedH = if (swap) bounds.outWidth else bounds.outHeight
        val (targetW, targetH) = PhotoSizing.target(orientedW, orientedH) ?: throw TooSmallException()
        val options = BitmapFactory.Options().apply {
            inSampleSize = PhotoSizing.sampleSize(orientedW, orientedH, targetW, targetH)
        }
        val decoded = open(input).use { BitmapFactory.decodeStream(it, null, options) } ?: return null
        val matrix = Matrix().apply {
            when (orientation) {
                ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> postScale(-1f, 1f)
                ExifInterface.ORIENTATION_ROTATE_180 -> postRotate(180f)
                ExifInterface.ORIENTATION_FLIP_VERTICAL -> postScale(1f, -1f)
                ExifInterface.ORIENTATION_TRANSPOSE -> { postRotate(90f); postScale(-1f, 1f) }
                ExifInterface.ORIENTATION_ROTATE_90 -> postRotate(90f)
                ExifInterface.ORIENTATION_TRANSVERSE -> { postRotate(270f); postScale(-1f, 1f) }
                ExifInterface.ORIENTATION_ROTATE_270 -> postRotate(270f)
            }
            val oriented = if (swap) decoded.height to decoded.width else decoded.width to decoded.height
            postScale(targetW.toFloat() / oriented.first, targetH.toFloat() / oriented.second)
        }
        return Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true).also { if (it !== decoded) decoded.recycle() }
    }

    private companion object {
        const val DIR = "photo-drafts"
    }
}

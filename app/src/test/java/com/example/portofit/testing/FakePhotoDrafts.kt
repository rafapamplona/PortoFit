package com.example.portofit.testing

import com.example.portofit.data.photo.CaptureTarget
import com.example.portofit.data.photo.PhotoDrafts
import com.example.portofit.data.photo.PhotoSizing
import com.example.portofit.data.photo.PrepareResult
import com.example.portofit.data.photo.PreparedPhoto
import java.io.File
import java.nio.file.Files
import java.util.UUID

/**
 * Rascunhos de fotografias para testes: ficheiros reais numa pasta temporária (para o envio poder ler o
 * ficheiro), com a preparação decidida por [onPrepare] (que pode suspender).
 */
class FakePhotoDrafts : PhotoDrafts {
    val dir: File = Files.createTempDirectory("photo-drafts-test").toFile()
    val deleted = mutableListOf<String>()
    val prepared = mutableListOf<String>()
    var captureAvailable = true

    var onPrepare: suspend (String) -> PrepareResult = { PrepareResult.Success(newPhoto()) }

    fun newPhoto(): PreparedPhoto {
        val name = "photo-${UUID.randomUUID()}.jpg"
        File(dir, name).writeBytes(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte()))
        return PreparedPhoto(name, 1600, 1200)
    }

    override fun newCaptureTarget(): CaptureTarget? {
        if (!captureAvailable) return null
        val name = "capture-${UUID.randomUUID()}.jpg"
        File(dir, name).createNewFile()
        return CaptureTarget(name, "content://com.example.portofit.photos/photo_drafts/$name")
    }

    override suspend fun prepareFromUri(uri: String): PrepareResult {
        prepared += uri
        return onPrepare(uri)
    }

    override suspend fun prepareFromCapture(captureName: String): PrepareResult {
        prepared += captureName
        File(dir, captureName).delete()
        return onPrepare(captureName)
    }

    override fun exists(name: String): Boolean = file(name)?.isFile == true

    override fun file(name: String): File? = if (PhotoSizing.isDraftName(name)) File(dir, name) else null

    override fun delete(name: String) {
        deleted += name
        file(name)?.delete()
    }

    override fun deleteStale(olderThanMillis: Long) = Unit

    fun files(): List<String> = dir.list()?.toList().orEmpty()
}

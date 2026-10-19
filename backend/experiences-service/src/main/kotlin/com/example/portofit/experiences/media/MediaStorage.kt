package com.example.portofit.experiences.media

import com.example.portofit.experiences.api.ApiErrors
import com.example.portofit.experiences.config.MediaProperties
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.time.Instant
import java.util.UUID

/** Ficheiro de fotografia encontrado na pasta do volume (para a limpeza). */
data class StoredFile(val mediaId: UUID, val modifiedAt: Instant)

/**
 * Ficheiros das fotografias no volume (docs/experiences-api.md §12.3). Os caminhos são construídos só a partir
 * do `mediaId` (UUID) gerado pelo serviço: `<2 primeiros caracteres>/<mediaId>.jpg`; temporários em `tmp/`.
 * Nenhum nome ou caminho vindo do pedido chega aqui, e os erros nunca expõem caminhos.
 */
@Component
class MediaStorage(properties: MediaProperties) {

	private val log = LoggerFactory.getLogger(javaClass)
	private val root: Path = Path.of(properties.storageDir).toAbsolutePath().normalize()
	private val tmp: Path = root.resolve("tmp")

	/** Escrita segura: temporário → escrita forçada para o disco → mudança de nome atómica. */
	fun write(mediaId: UUID, bytes: ByteArray) {
		val target = pathOf(mediaId)
		val part = tmp.resolve("${UUID.randomUUID()}.part")
		try {
			Files.createDirectories(tmp)
			Files.createDirectories(target.parent)
			FileChannel.open(part, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE).use { channel ->
				val buffer = ByteBuffer.wrap(bytes)
				while (buffer.hasRemaining()) channel.write(buffer)
				channel.force(true)
			}
			Files.move(part, target, StandardCopyOption.ATOMIC_MOVE)
		} catch (e: IOException) {
			runCatching { Files.deleteIfExists(part) }
			log.warn("Não foi possível gravar uma fotografia: {}", e.javaClass.simpleName)
			throw ApiErrors.storageUnavailable()
		}
	}

	/** Conteúdo, ou null se o ficheiro não existir. Outras falhas de leitura → 503. */
	fun read(mediaId: UUID): ByteArray? = try {
		Files.readAllBytes(pathOf(mediaId))
	} catch (e: NoSuchFileException) {
		null
	} catch (e: IOException) {
		log.warn("Não foi possível ler uma fotografia: {}", e.javaClass.simpleName)
		throw ApiErrors.photoUnavailable()
	}

	/** true se o ficheiro já não existe (apagado agora ou antes). */
	fun delete(mediaId: UUID): Boolean = try {
		Files.deleteIfExists(pathOf(mediaId))
		true
	} catch (e: IOException) {
		log.warn("Não foi possível apagar uma fotografia (fica para a limpeza): {}", e.javaClass.simpleName)
		false
	}

	fun exists(mediaId: UUID): Boolean = Files.isRegularFile(pathOf(mediaId), LinkOption.NOFOLLOW_LINKS)

	/** Fotografias guardadas: só ficheiros regulares `<uuid>.jpg` nas subpastas do serviço (sem seguir ligações). */
	fun list(): List<StoredFile> {
		if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) return emptyList()
		val result = mutableListOf<StoredFile>()
		Files.newDirectoryStream(root).use { dirs ->
			for (dir in dirs) {
				val name = dir.fileName.toString()
				if (!SUBDIR.matches(name) || !Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) continue
				Files.newDirectoryStream(dir).use { files ->
					for (file in files) {
						val id = FILE.matchEntire(file.fileName.toString())?.groupValues?.get(1)?.let(::parseCanonical) ?: continue
						if (!id.toString().startsWith(name) || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) continue
						result += StoredFile(id, Files.getLastModifiedTime(file, LinkOption.NOFOLLOW_LINKS).toInstant())
					}
				}
			}
		}
		return result
	}

	/** Apaga temporários `.part` mais antigos do que [olderThan]; devolve quantos. */
	fun deleteTemporariesOlderThan(olderThan: Instant): Int {
		if (!Files.isDirectory(tmp, LinkOption.NOFOLLOW_LINKS)) return 0
		var removed = 0
		Files.newDirectoryStream(tmp, "*.part").use { files ->
			for (file in files) {
				if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) continue
				if (Files.getLastModifiedTime(file, LinkOption.NOFOLLOW_LINKS).toInstant().isBefore(olderThan) &&
					runCatching { Files.deleteIfExists(file) }.getOrDefault(false)
				) {
					removed++
				}
			}
		}
		return removed
	}

	private fun pathOf(mediaId: UUID): Path {
		val id = mediaId.toString()
		val path = root.resolve(id.substring(0, 2)).resolve("$id.jpg").normalize()
		check(path.startsWith(root)) { "caminho fora do volume" }
		return path
	}

	private companion object {
		val SUBDIR = Regex("^[0-9a-f]{2}$")
		val FILE = Regex("^([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})\\.jpg$")

		fun parseCanonical(value: String): UUID? = runCatching { UUID.fromString(value) }.getOrNull()?.takeIf { it.toString() == value }
	}
}

package com.example.portofit.experiences

import com.drew.imaging.ImageMetadataReader
import com.drew.metadata.exif.ExifIFD0Directory
import com.drew.metadata.exif.GpsDirectory
import com.example.portofit.experiences.media.MediaService
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.not
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.Mockito
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.MediaType
import org.springframework.mock.web.MockMultipartFile
import org.springframework.test.web.servlet.MockHttpServletRequestDsl
import org.springframework.test.web.servlet.ResultActionsDsl
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.multipart
import org.springframework.test.web.servlet.patch
import org.springframework.test.web.servlet.post
import tools.jackson.databind.JsonNode
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.time.Instant
import java.util.UUID
import java.util.zip.CRC32
import javax.imageio.ImageIO
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Fotografias (docs/experiences-api.md §12): upload, consulta, substituição, remoção, autorização,
 * validação pelo conteúdo, orientação, remoção de metadados, falhas parciais e limpeza.
 */
class MediaApiIntegrationTest : ApiIntegrationTest() {

	@Autowired lateinit var mediaService: MediaService

	@AfterEach
	fun restore() {
		Mockito.reset(mediaRepository)
		catalog.mode = CatalogStub.Mode.UP
	}

	// --- Upload, publicação e consulta ---

	@Test
	fun `fotografia com EXIF - orientacao aplicada, metadados e GPS removidos, servida so depois de publicada`() {
		val ana = newUser("ana")
		// 400×200 guardada de lado (esquerda vermelha, direita azul) com orientação 6 e GPS.
		val original = withExif(jpeg(halves(400, 200)), orientation = 6)
		val metadata = ImageMetadataReader.readMetadata(ByteArrayInputStream(original))
		assertNotNull(metadata.getFirstDirectoryOfType(GpsDirectory::class.java), "o original tem GPS")

		val upload = upload(ana, original).andExpect {
			status { isCreated() }
			jsonPath("$.width") { value(200) }
			jsonPath("$.height") { value(400) }
		}.andReturn().json()
		val mediaId = upload["mediaId"].asString()
		assertEquals(4, UUID.fromString(mediaId).version(), "id aleatório e opaco")

		// Pendente: invisível para todos, incluindo quem a enviou.
		mvc.get("/api/v1/media/$mediaId").andExpect { status { isNotFound() } }

		val boxe = catalog.add()
		val experience = publish(ana, boxe, mediaId).andExpect {
			status { isCreated() }
			jsonPath("$.photo.mediaId") { value(mediaId) }
			jsonPath("$.photo.url") { value("/api/v1/media/$mediaId") }
			jsonPath("$.photo.width") { value(200) }
			jsonPath("$.photo.height") { value(400) }
		}.andReturn().json()

		val response = mvc.get("/api/v1/media/$mediaId").andExpect {
			status { isOk() }
			content { contentType(MediaType.IMAGE_JPEG) }
			header { string("X-Content-Type-Options", "nosniff") }
			header { string("Cache-Control", containsString("private")) }
			header { string("Cache-Control", containsString("max-age=86400")) }
			header { string("ETag", "\"$mediaId\"") }
		}.andReturn().response
		val stored = response.contentAsByteArray

		// Sem EXIF nem GPS; orientação aplicada (vermelho em cima, azul em baixo).
		assertFalse(String(stored, Charsets.ISO_8859_1).contains("Exif"))
		val cleaned = ImageMetadataReader.readMetadata(ByteArrayInputStream(stored))
		assertNull(cleaned.getFirstDirectoryOfType(GpsDirectory::class.java))
		assertNull(cleaned.getFirstDirectoryOfType(ExifIFD0Directory::class.java))
		val decoded = ImageIO.read(ByteArrayInputStream(stored))
		assertEquals(200, decoded.width)
		assertEquals(400, decoded.height)
		assertTrue(Color(decoded.getRGB(100, 30)).let { it.red > 200 && it.blue < 80 }, "topo vermelho")
		assertTrue(Color(decoded.getRGB(100, 370)).let { it.blue > 200 && it.red < 80 }, "fundo azul")

		// Revalidação: 304 sem corpo.
		mvc.get("/api/v1/media/$mediaId") { header("If-None-Match", "\"$mediaId\"") }.andExpect { status { isNotModified() } }
		// A fotografia aparece no detalhe e na lista.
		mvc.get("/api/v1/experiences/${experience["id"].asString()}").andExpect { jsonPath("$.photo.mediaId") { value(mediaId) } }
		mvc.get("/api/v1/experiences?placeId=${boxe.placeId}").andExpect { jsonPath("$.items[0].photo.mediaId") { value(mediaId) } }
		// Experiência sem fotografia: photo null.
		publish(ana, catalog.add(), null).andExpect { jsonPath("$.photo") { value(null as Any?) } }
	}

	@Test
	fun `PNG aceite e recodificado em JPEG, sem texto embutido e com transparencia sobre branco`() {
		val ana = newUser("ana")
		val transparent = BufferedImage(300, 300, BufferedImage.TYPE_INT_ARGB)
		val png = withPngText(png(transparent), "Comment", "segredo GPS 41.1,-8.6")
		val mediaId = upload(ana, png, filename = "captura.png", contentType = "image/png").andExpect { status { isCreated() } }
			.andReturn().json()["mediaId"].asString()
		publish(ana, catalog.add(), mediaId).andExpect { status { isCreated() } }

		val stored = mvc.get("/api/v1/media/$mediaId").andReturn().response.contentAsByteArray
		assertEquals(0xFF.toByte(), stored[0])
		assertEquals(0xD8.toByte(), stored[1])
		assertFalse(String(stored, Charsets.ISO_8859_1).contains("segredo"))
		assertTrue(Color(ImageIO.read(ByteArrayInputStream(stored)).getRGB(150, 150)).let { it.red > 240 && it.green > 240 && it.blue > 240 })
	}

	@Test
	fun `formato decidido pelo conteudo real e dimensoes recusadas antes de descodificar`() {
		val ana = newUser("ana")
		// Texto com nome e Content-Type de JPEG.
		expectInvalidImage(upload(ana, "não sou uma imagem".toByteArray(), filename = "foto.jpg", contentType = "image/jpeg"))
		// GIF verdadeiro com nome de JPEG.
		expectInvalidImage(upload(ana, encode(halves(300, 300), "gif"), filename = "foto.jpg", contentType = "image/jpeg"))
		// JPEG truncado e ficheiro vazio.
		val noisy = jpeg(noise(600, 600))
		expectInvalidImage(upload(ana, noisy.copyOfRange(0, noisy.size / 2)))
		expectInvalidImage(upload(ana, ByteArray(0)))
		// Demasiado pequena; demasiado grande (só o cabeçalho: nunca é descodificada).
		expectInvalidImage(upload(ana, jpeg(halves(100, 100))))
		val started = System.nanoTime()
		expectInvalidImage(upload(ana, pngHeaderOnly(9000, 9000), contentType = "image/png"))
		expectInvalidImage(upload(ana, pngHeaderOnly(8000, 6000), contentType = "image/png")) // 48 MP > 40 MP
		assertTrue((System.nanoTime() - started) / 1_000_000 < 3000, "recusado pelo cabeçalho, sem descodificar")
		// Nada ficou pendente.
		assertEquals(0, pendingOf(ana.id))

		mvc.multipart("/api/v1/media") { bearer(ana.token) }.andExpect {
			status { isBadRequest() }
			jsonPath("$.errors[0].field") { value("file") }
		}
		mvc.post("/api/v1/media") { bearer(ana.token); contentType = MediaType.APPLICATION_JSON; content = "{}" }.andExpect {
			status { isUnsupportedMediaType() }
		}
		mvc.multipart("/api/v1/media") { file(MockMultipartFile("file", "f.jpg", "image/jpeg", jpeg(halves(300, 300)))) }.andExpect {
			status { isUnauthorized() }
		}
	}

	@Test
	fun `ficheiro acima de 10 MiB recusado com 413 pelo servidor`() {
		val ana = newUser("ana")
		val big = ByteArray(10 * 1024 * 1024 + 1).also { it[0] = 0xFF.toByte(); it[1] = 0xD8.toByte(); it[2] = 0xFF.toByte() }
		val boundary = "limite" + UUID.randomUUID()
		val body = ByteArrayOutputStream().apply {
			write("--$boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"grande.jpg\"\r\nContent-Type: image/jpeg\r\n\r\n".toByteArray())
			write(big)
			write("\r\n--$boundary--\r\n".toByteArray())
		}.toByteArray()
		val response = HttpClient.newHttpClient().send(
			HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port/api/v1/media"))
				.header("Authorization", "Bearer ${ana.token}")
				.header("Content-Type", "multipart/form-data; boundary=$boundary")
				.POST(HttpRequest.BodyPublishers.ofByteArray(body)).build(),
			HttpResponse.BodyHandlers.ofString(),
		)
		assertEquals(413, response.statusCode())
		assertTrue(response.body().contains("PAYLOAD_TOO_LARGE"))
		assertEquals(0, pendingOf(ana.id))
	}

	// --- Autorização ---

	@Test
	fun `so o autor associa, substitui ou remove, e so com fotografias proprias`() {
		val ana = newUser("ana")
		val rui = newUser("rui")
		val anaMedia = uploadId(ana)
		val ruiMedia = uploadId(rui)

		// Rui não pode publicar com a fotografia da Ana.
		publish(rui, catalog.add(), anaMedia).andExpect {
			status { isBadRequest() }
			jsonPath("$.code") { value("MEDIA_NOT_USABLE") }
		}
		assertEquals(0, jdbc.sql("SELECT count(*) FROM experience WHERE author_id = :a").param("a", rui.id).query(Long::class.javaObjectType).single())

		val id = publish(ana, catalog.add(), anaMedia).andReturn().json()["id"].asString()
		// Rui não altera a experiência da Ana; a Ana não usa a fotografia do Rui.
		mvc.patch("/api/v1/experiences/$id") { json("""{"mediaId":"$ruiMedia"}"""); bearer(rui.token) }.andExpect { status { isForbidden() } }
		mvc.patch("/api/v1/experiences/$id") { json("""{"mediaId":null}"""); bearer(rui.token) }.andExpect { status { isForbidden() } }
		mvc.patch("/api/v1/experiences/$id") { json("""{"mediaId":"$ruiMedia"}"""); bearer(ana.token) }.andExpect {
			status { isBadRequest() }
			jsonPath("$.code") { value("MEDIA_NOT_USABLE") }
		}
		// Uma fotografia já associada não serve para outra experiência.
		publish(ana, catalog.add(), anaMedia).andExpect { jsonPath("$.code") { value("MEDIA_NOT_USABLE") } }
		// A mesma fotografia de novo: sem alterações.
		mvc.patch("/api/v1/experiences/$id") { json("""{"mediaId":"$anaMedia"}"""); bearer(ana.token) }.andExpect {
			status { isOk() }
			jsonPath("$.editedAt") { value(null as Any?) }
		}
		mvc.get("/api/v1/media/$anaMedia").andExpect { status { isOk() } }
		expectFieldError(mvc.patch("/api/v1/experiences/$id") { json("""{"mediaId":"../../etc/passwd"}"""); bearer(ana.token) }, "mediaId")
		expectFieldError(publishRaw(ana, catalog.add(), "123"), "mediaId")
	}

	// --- Substituição, remoção e eliminação ---

	@Test
	fun `substituir muda o URL, deixa logo de servir a antiga e so a apaga depois de confirmada`() {
		val ana = newUser("ana")
		val first = uploadId(ana)
		val id = publish(ana, catalog.add(), first).andReturn().json()["id"].asString()

		// Substituição falhada (fotografia inexistente): nada muda, a antiga continua.
		mvc.patch("/api/v1/experiences/$id") { json("""{"mediaId":"${UUID.randomUUID()}","stars":1}"""); bearer(ana.token) }.andExpect {
			status { isBadRequest() }
		}
		mvc.get("/api/v1/media/$first").andExpect { status { isOk() } }
		mvc.get("/api/v1/experiences/$id").andExpect {
			jsonPath("$.photo.mediaId") { value(first) }
			jsonPath("$.stars") { value(4) }
		}

		val second = uploadId(ana, jpeg(halves(500, 300)))
		mvc.patch("/api/v1/experiences/$id") { json("""{"mediaId":"$second"}"""); bearer(ana.token) }.andExpect {
			status { isOk() }
			jsonPath("$.photo.mediaId") { value(second) }
			jsonPath("$.photo.url") { value("/api/v1/media/$second") }
			jsonPath("$.editedAt") { exists() }
		}
		mvc.get("/api/v1/media/$first").andExpect { status { isNotFound() } }
		assertFalse(Files.exists(fileOf(first)), "ficheiro antigo apagado depois de confirmar")
		assertEquals(0, rows(first))
		mvc.get("/api/v1/media/$second").andExpect { status { isOk() } }
	}

	@Test
	fun `remover a fotografia mantem a experiencia de texto`() {
		val ana = newUser("ana")
		val media = uploadId(ana)
		val id = publish(ana, catalog.add(), media).andReturn().json()["id"].asString()
		mvc.patch("/api/v1/experiences/$id") { json("""{"mediaId":null}"""); bearer(ana.token) }.andExpect {
			status { isOk() }
			jsonPath("$.photo") { value(null as Any?) }
			jsonPath("$.text") { value("Com fotografia.") }
		}
		mvc.get("/api/v1/media/$media").andExpect { status { isNotFound() } }
		assertFalse(Files.exists(fileOf(media)))
	}

	@Test
	fun `eliminar a experiencia impede logo o acesso, mesmo com a limpeza fisica pendente`() {
		val ana = newUser("ana")
		val media = uploadId(ana)
		val id = publish(ana, catalog.add(), media).andReturn().json()["id"].asString()
		mvc.delete("/api/v1/experiences/$id") { bearer(ana.token) }.andExpect { status { isNoContent() } }
		mvc.get("/api/v1/media/$media").andExpect { status { isNotFound() } }
		assertFalse(Files.exists(fileOf(media)))

		// Limpeza física pendente (ex.: o ficheiro não pôde ser apagado): já não é servida, a limpeza trata dela.
		val other = uploadId(ana)
		val otherId = publish(ana, catalog.add(), other).andReturn().json()["id"].asString()
		jdbc.sql("UPDATE media SET status = 'DELETED', deleted_at = now() WHERE id = :id").param("id", UUID.fromString(other)).update()
		jdbc.sql("UPDATE experience SET deleted_at = now(), text = NULL, stars = NULL, photo_media_id = NULL WHERE id = :id")
			.param("id", UUID.fromString(otherId)).update()
		mvc.get("/api/v1/media/$other").andExpect { status { isNotFound() } }
		assertTrue(Files.exists(fileOf(other)))
		mediaService.cleanup()
		assertFalse(Files.exists(fileOf(other)))
		assertEquals(0, rows(other))
	}

	// --- Idempotência e limites ---

	@Test
	fun `publicacao com fotografia continua idempotente`() {
		val ana = newUser("ana")
		val media = uploadId(ana)
		val boxe = catalog.add()
		val key = UUID.randomUUID().toString()

		// Fotografia inutilizável: nada criado, a chave não fica gasta.
		publish(ana, boxe, UUID.randomUUID().toString(), key).andExpect { jsonPath("$.code") { value("MEDIA_NOT_USABLE") } }
		val id = publish(ana, boxe, media, key).andExpect { status { isCreated() } }.andReturn().json()["id"].asString()
		// Repetição: 200 com a mesma experiência (a fotografia já está associada e não é validada de novo).
		publish(ana, boxe, media, key).andExpect {
			status { isOk() }
			header { string("Idempotent-Replayed", "true") }
			jsonPath("$.id") { value(id) }
			jsonPath("$.photo.mediaId") { value(media) }
		}
		publish(ana, boxe, null, key).andExpect { jsonPath("$.reason") { value("DIFFERENT_CONTENT") } }
	}

	@Test
	fun `limite de fotografias pendentes com Retry-After`() {
		val ana = newUser("ana")
		repeat(PENDING_LIMIT) { uploadId(ana) }
		val retryAfter = upload(ana, jpeg(halves(300, 300))).andExpect {
			status { isTooManyRequests() }
			jsonPath("$.code") { value("TOO_MANY_REQUESTS") }
		}.andReturn().response.getHeader("Retry-After")!!.toLong()
		assertTrue(retryAfter in 1..86_400)
	}

	// --- Falhas parciais e limpeza ---

	@Test
	fun `falha da base de dados depois de gravar o ficheiro apaga o ficheiro`() {
		val ana = newUser("ana")
		val before = storedFiles()
		Mockito.doThrow(IllegalStateException("base de dados em baixo")).`when`(mediaRepository)
			.insertPending(anyUuid(), anyUuid(), anyInt(), anyInt(), anyInt())
		upload(ana, jpeg(halves(300, 300))).andExpect {
			status { isInternalServerError() }
			content { string(not(containsString(mediaDir.toString()))) }
		}
		assertEquals(before, storedFiles())
	}

	@Test
	fun `ficheiro em falta - a fotografia fica indisponivel mas a experiencia nao`() {
		val ana = newUser("ana")
		val media = uploadId(ana)
		val id = publish(ana, catalog.add(), media).andReturn().json()["id"].asString()
		Files.delete(fileOf(media))
		mvc.get("/api/v1/media/$media").andExpect {
			status { isServiceUnavailable() }
			jsonPath("$.code") { value("SERVICE_UNAVAILABLE") }
			content { string(not(containsString(mediaDir.toString()))) }
			content { string(not(containsString(".jpg"))) }
		}
		mvc.get("/api/v1/experiences/$id").andExpect {
			status { isOk() }
			jsonPath("$.text") { value("Com fotografia.") }
		}
		mvc.get("/api/v1/media/nao-e-um-id").andExpect { status { isNotFound() } }
	}

	@Test
	fun `limpeza de pendentes expiradas, ficheiros orfaos e temporarios, sem tocar no resto`() {
		val ana = newUser("ana")
		val expired = uploadId(ana)
		val recent = uploadId(ana)
		val attached = uploadId(ana)
		publish(ana, catalog.add(), attached).andExpect { status { isCreated() } }
		jdbc.sql("UPDATE media SET created_at = now() - interval '25 hours' WHERE id = :id").param("id", UUID.fromString(expired)).update()

		val twoHoursAgo = FileTime.from(Instant.now().minusSeconds(7200))
		val oldOrphan = UUID.randomUUID().toString().also { orphan(it, twoHoursAgo) }
		val newOrphan = UUID.randomUUID().toString().also { orphan(it, null) }
		val tmp = Files.createDirectories(mediaDir.resolve("tmp"))
		val oldPart = Files.write(tmp.resolve("${UUID.randomUUID()}.part"), byteArrayOf(1)).also { Files.setLastModifiedTime(it, twoHoursAgo) }
		val newPart = Files.write(tmp.resolve("${UUID.randomUUID()}.part"), byteArrayOf(1))
		val foreign = Files.write(mediaDir.resolve("leia-me.txt"), byteArrayOf(1)).also { Files.setLastModifiedTime(it, twoHoursAgo) }

		mediaService.cleanup()

		assertFalse(Files.exists(fileOf(expired)))
		assertEquals(0, rows(expired))
		assertTrue(Files.exists(fileOf(recent)))
		assertTrue(Files.exists(fileOf(attached)))
		mvc.get("/api/v1/media/$attached").andExpect { status { isOk() } }
		assertFalse(Files.exists(fileOf(oldOrphan)))
		assertTrue(Files.exists(fileOf(newOrphan)))
		assertFalse(Files.exists(oldPart))
		assertTrue(Files.exists(newPart))
		assertTrue(Files.exists(foreign), "ficheiros que não são do serviço não são tocados")
		Files.delete(foreign)
	}

	// --- Auxiliares ---

	private fun upload(user: TestUser, bytes: ByteArray, filename: String = "foto.jpg", contentType: String = "image/jpeg"): ResultActionsDsl =
		mvc.multipart("/api/v1/media") {
			file(MockMultipartFile("file", filename, contentType, bytes))
			bearer(user.token)
		}

	private fun uploadId(user: TestUser, bytes: ByteArray = jpeg(halves(400, 300))): String =
		upload(user, bytes).andExpect { status { isCreated() } }.andReturn().json()["mediaId"].asString()

	private fun publish(user: TestUser, offering: CatalogStub.TestOffering, mediaId: String?, key: String = UUID.randomUUID().toString()) =
		mvc.post("/api/v1/experiences") {
			json(mapper.writeValueAsString(mapOf("placeId" to offering.placeId, "offeringId" to offering.id, "text" to "Com fotografia.", "stars" to 4, "mediaId" to mediaId)))
			bearer(user.token)
			header("Idempotency-Key", key)
		}

	private fun publishRaw(user: TestUser, offering: CatalogStub.TestOffering, rawMediaId: String) =
		mvc.post("/api/v1/experiences") {
			json("""{"placeId":"${offering.placeId}","offeringId":"${offering.id}","text":"x","stars":4,"mediaId":$rawMediaId}""")
			bearer(user.token)
			header("Idempotency-Key", UUID.randomUUID().toString())
		}

	private fun expectInvalidImage(result: ResultActionsDsl) {
		result.andExpect {
			status { isBadRequest() }
			jsonPath("$.code") { value("INVALID_IMAGE") }
			jsonPath("$.errors[0].field") { value("file") }
			content { string(not(containsString(mediaDir.toString()))) }
		}
	}

	private fun expectFieldError(result: ResultActionsDsl, field: String) {
		result.andExpect {
			status { isBadRequest() }
			jsonPath("$.code") { value("VALIDATION_FAILED") }
			jsonPath("$.errors[0].field") { value(field) }
		}
	}

	private fun fileOf(mediaId: String): Path = mediaDir.resolve(mediaId.take(2)).resolve("$mediaId.jpg")

	private fun orphan(mediaId: String, modified: FileTime?) {
		val file = Files.createDirectories(fileOf(mediaId).parent).resolve("$mediaId.jpg")
		Files.write(file, jpeg(halves(300, 300)))
		if (modified != null) Files.setLastModifiedTime(file, modified)
	}

	private fun storedFiles(): Set<Path> = Files.walk(mediaDir).use { s -> s.filter { it.toString().endsWith(".jpg") }.toList().toSet() }

	private fun rows(mediaId: String): Long =
		jdbc.sql("SELECT count(*) FROM media WHERE id = :id").param("id", UUID.fromString(mediaId)).query(Long::class.javaObjectType).single()

	private fun pendingOf(owner: UUID): Long =
		jdbc.sql("SELECT count(*) FROM media WHERE owner_id = :o").param("o", owner).query(Long::class.javaObjectType).single()

	/** Mockito com Kotlin: o valor devolvido nunca é usado (a chamada é intercetada). */
	private fun anyUuid(): UUID {
		Mockito.any(UUID::class.java)
		return uninitialized()
	}

	/** Cast não verificado para um parâmetro de tipo: o Kotlin não insere verificação de nulo aqui. */
	@Suppress("UNCHECKED_CAST")
	private fun <T> uninitialized(): T = null as T

	private fun MockHttpServletRequestDsl.json(body: String) {
		contentType = MediaType.APPLICATION_JSON
		content = body
	}

	private fun MockHttpServletRequestDsl.bearer(token: String) {
		header("Authorization", "Bearer $token")
	}

	private fun org.springframework.test.web.servlet.MvcResult.json(): JsonNode = mapper.readTree(response.contentAsString)

	// --- Imagens de teste ---

	/** Metade esquerda vermelha, metade direita azul. */
	private fun halves(width: Int, height: Int) = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB).apply {
		val g = createGraphics()
		g.color = Color.RED
		g.fillRect(0, 0, width / 2, height)
		g.color = Color.BLUE
		g.fillRect(width / 2, 0, width - width / 2, height)
		g.dispose()
	}

	private fun noise(width: Int, height: Int) = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB).apply {
		val random = java.util.Random(7)
		for (x in 0 until width) for (y in 0 until height) setRGB(x, y, random.nextInt())
	}

	private fun encode(image: BufferedImage, format: String): ByteArray =
		ByteArrayOutputStream().also { check(ImageIO.write(image, format, it)) }.toByteArray()

	private fun jpeg(image: BufferedImage) = encode(image, "jpeg")

	private fun png(image: BufferedImage) = encode(image, "png")

	/** Segmento APP1 EXIF (TIFF big-endian) com orientação e GPS (latitude norte), logo a seguir ao SOI. */
	private fun withExif(jpeg: ByteArray, orientation: Int): ByteArray {
		val tiff = ByteArrayOutputStream()
		DataOutputStream(tiff).apply {
			writeBytes("MM"); writeShort(42); writeInt(8)
			writeShort(2) // IFD0: 2 entradas
			writeShort(0x0112); writeShort(3); writeInt(1); writeShort(orientation); writeShort(0)
			writeShort(0x8825); writeShort(4); writeInt(1); writeInt(38) // ponteiro para o IFD de GPS
			writeInt(0)
			writeShort(1) // IFD de GPS: GPSLatitudeRef = "N"
			writeShort(0x0001); writeShort(2); writeInt(2); writeBytes("N"); writeByte(0); writeShort(0)
			writeInt(0)
		}
		val payload = "Exif\u0000\u0000".toByteArray(Charsets.ISO_8859_1) + tiff.toByteArray()
		val length = payload.size + 2
		val app1 = byteArrayOf(0xFF.toByte(), 0xE1.toByte(), (length shr 8).toByte(), length.toByte()) + payload
		return jpeg.copyOfRange(0, 2) + app1 + jpeg.copyOfRange(2, jpeg.size)
	}

	/** Acrescenta um bloco tEXt depois do IHDR. */
	private fun withPngText(png: ByteArray, keyword: String, text: String): ByteArray =
		png.copyOfRange(0, 33) + chunk("tEXt", keyword.toByteArray(Charsets.ISO_8859_1) + 0 + text.toByteArray(Charsets.ISO_8859_1)) + png.copyOfRange(33, png.size)

	/** Só assinatura, IHDR e IEND: dimensões declaradas sem píxeis (uma "bomba" de descompressão). */
	private fun pngHeaderOnly(width: Int, height: Int): ByteArray {
		val ihdr = ByteArrayOutputStream().also { DataOutputStream(it).apply { writeInt(width); writeInt(height); writeByte(8); writeByte(2); writeByte(0); writeByte(0); writeByte(0) } }
		val signature = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
		return signature + chunk("IHDR", ihdr.toByteArray()) + chunk("IEND", ByteArray(0))
	}

	private fun chunk(type: String, data: ByteArray): ByteArray {
		val out = ByteArrayOutputStream()
		val typeBytes = type.toByteArray(Charsets.ISO_8859_1)
		DataOutputStream(out).apply {
			writeInt(data.size)
			write(typeBytes)
			write(data)
			writeInt(CRC32().apply { update(typeBytes); update(data) }.value.toInt())
		}
		return out.toByteArray()
	}
}

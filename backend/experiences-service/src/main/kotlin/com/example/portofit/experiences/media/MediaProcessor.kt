package com.example.portofit.experiences.media

import com.drew.imaging.ImageMetadataReader
import com.drew.metadata.exif.ExifIFD0Directory
import com.example.portofit.experiences.api.ApiErrors
import com.example.portofit.experiences.config.MediaProperties
import org.springframework.stereotype.Component
import java.awt.Color
import java.awt.RenderingHints
import java.awt.geom.AffineTransform
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageReader
import javax.imageio.ImageWriteParam
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Fotografia recodificada, pronta a guardar. */
class ProcessedImage(val jpeg: ByteArray, val width: Int, val height: Int)

/** Formato identificado pela assinatura do conteúdo (nunca pela extensão nem pelo Content-Type). */
enum class ImageFormat(val readerName: String) { JPEG("jpeg"), PNG("png") }

/**
 * Validação e recodificação das fotografias (docs/experiences-api.md §12.2), só com o `javax.imageio` do JDK
 * e a `metadata-extractor` para a orientação EXIF.
 *
 * Memória limitada: as dimensões são lidas do cabeçalho antes de descodificar; a descodificação usa
 * subamostragem até [MediaProperties.decodeMaxSide]; e no máximo [MediaProperties.maxConcurrentProcessing]
 * imagens são processadas ao mesmo tempo.
 */
@Component
class MediaProcessor(private val properties: MediaProperties) {

	private val permits = Semaphore(properties.maxConcurrentProcessing)

	init {
		// Sem ficheiros temporários do ImageIO: tudo em memória, já limitada acima.
		ImageIO.setUseCache(false)
	}

	fun process(bytes: ByteArray): ProcessedImage {
		if (bytes.isEmpty()) throw ApiErrors.invalidImage("O ficheiro está vazio.")
		val format = detect(bytes) ?: throw ApiErrors.invalidImage("Só são aceites fotografias JPEG ou PNG.")
		if (!permits.tryAcquire(properties.processingWait.toMillis(), TimeUnit.MILLISECONDS)) throw ApiErrors.mediaBusy()
		try {
			val decoded = decode(bytes, format)
			val oriented = orientAndScale(decoded, orientation(bytes))
			return ProcessedImage(encodeJpeg(oriented), oriented.width, oriented.height)
		} finally {
			permits.release()
		}
	}

	private fun decode(bytes: ByteArray, format: ImageFormat): BufferedImage {
		val reader: ImageReader = ImageIO.getImageReadersByFormatName(format.readerName).next()
		try {
			ImageIO.createImageInputStream(ByteArrayInputStream(bytes)).use { input ->
				// ignoreMetadata = true: os metadados nunca são lidos pelo descodificador (a orientação é lida à parte).
				reader.setInput(input, true, true)
				val width = reader.getWidth(0)
				val height = reader.getHeight(0)
				if (width < properties.minSide || height < properties.minSide || width > properties.maxSide || height > properties.maxSide ||
					width.toLong() * height > properties.maxPixels
				) {
					throw ApiErrors.invalidImage(
						"A fotografia deve ter entre ${properties.minSide} e ${properties.maxSide} píxeis de lado e no máximo " +
							"${properties.maxPixels / 1_000_000} megapíxeis.",
					)
				}
				var damaged = false
				reader.addIIOReadWarningListener { _, _ -> damaged = true }
				val param = reader.defaultReadParam
				val step = max(1, max(width, height) / properties.decodeMaxSide)
				if (step > 1) param.setSourceSubsampling(step, step, 0, 0)
				val image = reader.read(0, param)
				// Um JPEG truncado é descodificado com avisos (cinzento no fim): é recusado.
				if (damaged || image == null) throw ApiErrors.invalidImage(DAMAGED)
				return image
			}
		} catch (e: com.example.portofit.experiences.api.ApiException) {
			throw e
		} catch (e: Exception) {
			// IIOException, dados inválidos, etc.: nunca se devolve a mensagem interna.
			throw ApiErrors.invalidImage(DAMAGED)
		} finally {
			reader.dispose()
		}
	}

	/** Orientação EXIF (1–8); metadados ilegíveis ou ausentes → 1 (são descartados de qualquer forma). */
	private fun orientation(bytes: ByteArray): Int = try {
		ImageMetadataReader.readMetadata(ByteArrayInputStream(bytes), bytes.size.toLong())
			.getFirstDirectoryOfType(ExifIFD0Directory::class.java)
			?.takeIf { it.containsTag(ExifIFD0Directory.TAG_ORIENTATION) }
			?.getInt(ExifIFD0Directory.TAG_ORIENTATION)
			?.takeIf { it in 1..8 } ?: 1
	} catch (e: Exception) {
		1
	}

	/**
	 * Aplica a orientação e reduz o lado maior a [MediaProperties.outputMaxSide], numa única passagem para
	 * uma imagem RGB nova (transparência sobre branco). Nada dos metadados passa para a nova imagem.
	 */
	private fun orientAndScale(source: BufferedImage, orientation: Int): BufferedImage {
		val w = source.width.toDouble()
		val h = source.height.toDouble()
		val swap = orientation >= 5
		val orientedW = if (swap) h else w
		val orientedH = if (swap) w else h
		val scale = min(1.0, properties.outputMaxSide / max(orientedW, orientedH))
		val targetW = max(1, (orientedW * scale).roundToInt())
		val targetH = max(1, (orientedH * scale).roundToInt())

		// x' = m00·x + m01·y + m02; y' = m10·x + m11·y + m12 (orientações EXIF 1–8).
		val orient = when (orientation) {
			2 -> AffineTransform(-1.0, 0.0, 0.0, 1.0, w, 0.0)
			3 -> AffineTransform(-1.0, 0.0, 0.0, -1.0, w, h)
			4 -> AffineTransform(1.0, 0.0, 0.0, -1.0, 0.0, h)
			5 -> AffineTransform(0.0, 1.0, 1.0, 0.0, 0.0, 0.0)
			6 -> AffineTransform(0.0, 1.0, -1.0, 0.0, h, 0.0)
			7 -> AffineTransform(0.0, -1.0, -1.0, 0.0, h, w)
			8 -> AffineTransform(0.0, -1.0, 1.0, 0.0, 0.0, w)
			else -> AffineTransform()
		}
		val transform = AffineTransform.getScaleInstance(targetW / orientedW, targetH / orientedH).apply { concatenate(orient) }

		val target = BufferedImage(targetW, targetH, BufferedImage.TYPE_INT_RGB)
		val g = target.createGraphics()
		try {
			g.color = Color.WHITE
			g.fillRect(0, 0, targetW, targetH)
			g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
			g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
			g.drawImage(source, transform, null)
		} finally {
			g.dispose()
		}
		return target
	}

	/** JPEG só com a imagem (sem EXIF, XMP, IPTC, comentários nem miniaturas). */
	private fun encodeJpeg(image: BufferedImage): ByteArray {
		val writer = ImageIO.getImageWritersByFormatName("jpeg").next()
		try {
			val out = ByteArrayOutputStream()
			ImageIO.createImageOutputStream(out).use { output ->
				writer.output = output
				val param = writer.defaultWriteParam.apply {
					compressionMode = ImageWriteParam.MODE_EXPLICIT
					compressionQuality = properties.jpegQuality
				}
				writer.write(null, IIOImage(image, null, null), param)
			}
			return out.toByteArray()
		} finally {
			writer.dispose()
		}
	}

	companion object {
		private const val DAMAGED = "O ficheiro está danificado ou não é uma imagem válida."

		private val PNG_SIGNATURE = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)

		fun detect(bytes: ByteArray): ImageFormat? = when {
			bytes.size >= 3 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() && bytes[2] == 0xFF.toByte() -> ImageFormat.JPEG
			bytes.size >= 8 && bytes.copyOfRange(0, 8).contentEquals(PNG_SIGNATURE) -> ImageFormat.PNG
			else -> null
		}
	}
}

package com.example.portofit.experiences.experience

import com.example.portofit.experiences.api.ApiErrors
import com.example.portofit.experiences.api.FieldError
import tools.jackson.core.JacksonException
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.security.MessageDigest
import java.util.HexFormat
import java.util.UUID

/** Conteúdo de uma publicação, já validado e normalizado. */
data class PublishInput(
	val placeId: UUID,
	val offeringId: UUID,
	val text: String,
	val stars: Int,
	/** Fotografia pendente a associar (E2), ou null. */
	val mediaId: UUID? = null,
) {
	/**
	 * SHA-256 do conteúdo normalizado, para reconhecer a repetição da mesma tentativa (docs/experiences-api.md §5).
	 * O separador NUL não pode aparecer no texto (caracteres de controlo são recusados).
	 */
	fun requestHash(): String {
		// O mediaId só entra quando existe: as chaves de publicações sem fotografia continuam válidas (§12.1).
		val parts = listOf(placeId.toString(), offeringId.toString(), stars.toString(), text) + listOfNotNull(mediaId?.toString())
		val canonical = parts.joinToString("\u0000")
		return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(Charsets.UTF_8)))
	}
}

/**
 * Alterações pedidas num PATCH; null = não alterar. [mediaSpecified] distingue `"mediaId"` ausente (não muda)
 * de `"mediaId": null` (remove a fotografia).
 */
data class EditInput(val text: String?, val stars: Int?, val mediaSpecified: Boolean = false, val mediaId: UUID? = null)

/** Regras de validação de docs/experiences-api.md §4.1 e §4.4. */
object ExperienceRules {
	const val MAX_TEXT_LENGTH = 2000

	private const val TEXT_MESSAGE = "O texto deve ter entre 1 e 2000 caracteres."
	private const val STARS_MESSAGE = "As estrelas devem ser um número inteiro de 1 a 5."
	private val PUBLISH_FIELDS = setOf("placeId", "offeringId", "text", "stars", "mediaId")
	private val SLUG = Regex("^[a-z0-9]+(-[a-z0-9]+)*$")

	fun parsePublish(body: String?, mapper: JsonMapper): PublishInput {
		val node = parseObject(body, mapper)
		val errors = mutableListOf<FieldError>()
		node.propertyNames().filterNot { it in PUBLISH_FIELDS }.forEach { errors += FieldError(it, "Este campo não pode ser enviado.") }
		val placeId = uuidField(node, "placeId", "Indica o local.", errors)
		val offeringId = uuidField(node, "offeringId", "Indica a atividade.", errors)
		val text = textField(node.get("text"), errors)
		val stars = starsField(node.get("stars"), errors)
		val mediaId = mediaField(node, errors)
		if (errors.isNotEmpty()) throw ApiErrors.validation(errors)
		return PublishInput(placeId!!, offeringId!!, text!!, stars!!, mediaId)
	}

	fun parseEdit(body: String?, mapper: JsonMapper): EditInput {
		val node = parseObject(body, mapper)
		val errors = mutableListOf<FieldError>()
		for (name in node.propertyNames()) {
			when (name) {
				"text", "stars", "mediaId" -> Unit
				"placeId", "offeringId" -> errors += FieldError(name, "A atividade não pode ser alterada. Apaga a experiência e publica uma nova.")
				else -> errors += FieldError(name, "Este campo não pode ser alterado.")
			}
		}
		val text = if (node.has("text")) textField(node.get("text"), errors) else null
		val stars = if (node.has("stars")) starsField(node.get("stars"), errors) else null
		val mediaId = mediaField(node, errors)
		if (!node.has("text") && !node.has("stars") && !node.has("mediaId") && errors.isEmpty()) {
			errors += FieldError("text", "Indica o texto, as estrelas ou a fotografia a alterar.")
		}
		if (errors.isNotEmpty()) throw ApiErrors.validation(errors)
		return EditInput(text, stars, mediaSpecified = node.has("mediaId"), mediaId = mediaId)
	}

	/** `\r\n` e `\r` passam a `\n`; espaços e mudanças de linha nas pontas são removidos. */
	fun normalizeText(raw: String): String = raw.replace("\r\n", "\n").replace('\r', '\n').trim()

	/**
	 * Texto simples normalizado com 1 a [maxLength] caracteres (pontos de código), sem caracteres de controlo além
	 * de `\n` e `\t`; vazio, só espaços ou inválido → null. Também para os comentários (docs/experiences-api.md §13.1).
	 */
	fun cleanText(raw: String, maxLength: Int): String? {
		val text = normalizeText(raw)
		val length = text.codePointCount(0, text.length)
		val invalidChar = text.codePoints().anyMatch { c ->
			val type = Character.getType(c)
			(type == Character.CONTROL.toInt() && c != '\n'.code && c != '\t'.code) || type == Character.SURROGATE.toInt()
		}
		return text.takeUnless { it.isEmpty() || length > maxLength || invalidChar }
	}

	/** UUID na forma canónica (36 caracteres), sem distinguir maiúsculas; outra forma → null. */
	fun parseUuid(value: String?): UUID? {
		val text = value?.trim() ?: return null
		return runCatching { UUID.fromString(text) }.getOrNull()?.takeIf { it.toString().equals(text, ignoreCase = true) }
	}

	/** Id de experiência no caminho; outra forma → 400. */
	fun experienceId(value: String): UUID =
		parseUuid(value) ?: throw ApiErrors.validation("experienceId", "Identificador de experiência inválido.")

	fun isSportId(value: String) = value.length in 2..40 && SLUG.matches(value)

	fun parseObject(body: String?, mapper: JsonMapper): JsonNode {
		val node = try {
			body?.takeIf { it.isNotBlank() }?.let { mapper.readTree(it) }
		} catch (_: JacksonException) {
			null
		}
		if (node == null || !node.isObject) throw ApiErrors.validation("body", "O corpo do pedido tem de ser um objeto JSON.")
		return node
	}

	/** `"mediaId"`: ausente ou null → null; texto → UUID canónico; outro valor → erro. */
	private fun mediaField(node: JsonNode, errors: MutableList<FieldError>): UUID? {
		val value = node.get("mediaId") ?: return null
		if (value.isNull) return null
		val id = if (value.isString) parseUuid(value.stringValue()) else null
		if (id == null) errors += FieldError("mediaId", "Identificador de fotografia inválido.")
		return id
	}

	private fun uuidField(node: JsonNode, field: String, missing: String, errors: MutableList<FieldError>): UUID? {
		val value = node.get(field)
		val id = if (value != null && value.isString) parseUuid(value.stringValue()) else null
		if (id == null) errors += FieldError(field, missing)
		return id
	}

	private fun textField(value: JsonNode?, errors: MutableList<FieldError>): String? {
		if (value == null || !value.isString) {
			errors += FieldError("text", TEXT_MESSAGE)
			return null
		}
		return cleanText(value.stringValue(), MAX_TEXT_LENGTH) ?: null.also { errors += FieldError("text", TEXT_MESSAGE) }
	}

	/** Só números inteiros JSON de 1 a 5: `4.5`, `4.0`, `"4"` e `null` são recusados. */
	private fun starsField(value: JsonNode?, errors: MutableList<FieldError>): Int? {
		val stars = if (value != null && value.isIntegralNumber && value.canConvertToInt()) value.intValue() else null
		if (stars == null || stars !in 1..5) {
			errors += FieldError("stars", STARS_MESSAGE)
			return null
		}
		return stars
	}
}

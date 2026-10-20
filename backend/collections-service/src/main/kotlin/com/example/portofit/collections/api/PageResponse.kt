package com.example.portofit.collections.api

/** Envelope comum das listas paginadas (igual ao do catalog-service e do users-service). */
data class PageResponse<T>(
	val items: List<T>,
	val page: Int,
	val size: Int,
	val totalItems: Long,
	val totalPages: Int,
) {
	companion object {
		const val DEFAULT_SIZE = 20
		const val MAX_SIZE = 50

		/** Valida `page` (≥ 0, por omissão 0) e `size` (1–50, por omissão 20) recebidos como texto. */
		fun parse(page: String?, size: String?, errors: MutableList<FieldError>): Pair<Int, Int> {
			val pageValue = page?.trim()?.takeIf { it.isNotEmpty() }?.let { it.toIntOrNull() ?: -1 } ?: 0
			if (pageValue < 0) errors += FieldError("page", "A página deve ser um número inteiro maior ou igual a 0.")
			val sizeValue = size?.trim()?.takeIf { it.isNotEmpty() }?.let { it.toIntOrNull() ?: -1 } ?: DEFAULT_SIZE
			if (sizeValue !in 1..MAX_SIZE) errors += FieldError("size", "O tamanho da página deve estar entre 1 e 50.")
			return pageValue to sizeValue
		}

		fun <T> of(items: List<T>, page: Int, size: Int, totalItems: Long): PageResponse<T> {
			val totalPages = if (totalItems == 0L) 0 else ((totalItems + size - 1) / size).toInt()
			return PageResponse(items, page, size, totalItems, totalPages)
		}
	}
}

package com.example.portofit.users.api

/** Envelope comum das listas paginadas (igual ao do catalog-service). */
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

		fun <T> of(items: List<T>, page: Int, size: Int, totalItems: Long): PageResponse<T> {
			val totalPages = if (totalItems == 0L) 0 else ((totalItems + size - 1) / size).toInt()
			return PageResponse(items, page, size, totalItems, totalPages)
		}
	}
}

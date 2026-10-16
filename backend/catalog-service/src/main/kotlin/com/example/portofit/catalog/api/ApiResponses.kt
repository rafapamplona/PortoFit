package com.example.portofit.catalog.api

/** Envelope de listas não paginadas (ex.: modalidades). */
data class ItemsResponse<T>(
	val items: List<T>,
)

/** Envelope comum a todas as listas paginadas. */
data class PageResponse<T>(
	val items: List<T>,
	val page: Int,
	val size: Int,
	val totalItems: Long,
	val totalPages: Int,
) {
	companion object {
		fun <T> of(items: List<T>, page: Int, size: Int, totalItems: Long): PageResponse<T> {
			val totalPages = if (totalItems == 0L) 0 else ((totalItems + size - 1) / size).toInt()
			return PageResponse(items, page, size, totalItems, totalPages)
		}
	}
}

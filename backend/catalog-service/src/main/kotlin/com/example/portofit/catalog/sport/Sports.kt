package com.example.portofit.catalog.sport

import com.example.portofit.catalog.api.ItemsResponse
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

data class Sport(
	val id: String,
	val name: String,
	val description: String?,
)

@Repository
class SportRepository(private val jdbc: JdbcClient) {

	/** Todas as modalidades por nome (sem distinguir maiúsculas/acentos), com desempate pelo id. */
	fun findAll(): List<Sport> =
		jdbc.sql("SELECT id, name, description FROM sport ORDER BY portofit_normalize(name), id")
			.query { rs, _ -> Sport(rs.getString("id"), rs.getString("name"), rs.getString("description")) }
			.list()

	fun exists(id: String): Boolean =
		jdbc.sql("SELECT EXISTS (SELECT 1 FROM sport WHERE id = :id)")
			.param("id", id)
			.query(Boolean::class.javaObjectType)
			.single()
}

@RestController
@RequestMapping("/api/v1/sports")
class SportController(private val sports: SportRepository) {

	@GetMapping
	fun list(): ItemsResponse<Sport> = ItemsResponse(sports.findAll())
}

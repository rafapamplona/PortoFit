package com.example.portofit.experiences.experience

import java.time.Instant
import java.util.UUID

data class AuthorRef(val userId: UUID, val username: String)

data class PlaceRef(val id: UUID, val name: String, val demo: Boolean)

data class OfferingRef(val id: UUID, val name: String)

data class SportRef(val id: String, val name: String)

/** Fotografia atual (E2). O URL muda em cada substituição (novo `mediaId`). */
data class PhotoView(val mediaId: UUID, val url: String, val width: Int, val height: Int)

/**
 * Formato `Experience` das respostas (docs/experiences-api.md §4.1). Os nomes são cópias de apresentação do
 * momento da publicação; os ids são as referências. Nunca inclui email nem outros dados privados.
 */
data class ExperienceView(
	val id: UUID,
	val author: AuthorRef,
	val place: PlaceRef,
	val offering: OfferingRef,
	val sport: SportRef,
	val text: String,
	val stars: Int,
	val createdAt: Instant,
	val editedAt: Instant?,
	val photo: PhotoView?,
)

/** Só para experiências não eliminadas (as eliminadas não têm conteúdo). */
fun ExperienceRow.toView() = ExperienceView(
	id = id,
	author = AuthorRef(authorId, authorUsername),
	place = PlaceRef(placeId, placeName, placeDemo),
	offering = OfferingRef(offeringId, offeringName),
	sport = SportRef(sportId, sportName),
	text = checkNotNull(text) { "experiência eliminada" },
	stars = checkNotNull(stars) { "experiência eliminada" },
	createdAt = createdAt,
	editedAt = editedAt,
	photo = photoMediaId?.let { PhotoView(it, "/api/v1/media/$it", photoWidth ?: 0, photoHeight ?: 0) },
)

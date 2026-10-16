package com.example.portofit.data.remote

import com.example.portofit.data.model.ActivityLevel
import com.example.portofit.data.model.Rating
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Conversão de respostas JSON reais do catalog-service (docs/catalog-api.md) para os modelos da interface. */
class CatalogMappersTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `pagina de pesquisa com avaliacao indisponivel`() {
        val body = """
            {"items":[{"id":"3f1c9a2e-6b0d-4c55-9a7e-1d2b3c4d5e01","name":"Clube de Combate Ribeira (fictício)",
            "address":{"street":"Rua Exemplo, 10","postalCode":"4050-001","parish":"Centro Histórico","city":"Porto"},
            "location":{"latitude":41.1406,"longitude":-8.6131},
            "sports":[{"id":"boxe","name":"Boxe"},{"id":"kickboxing","name":"Kickboxing"}],
            "rating":{"status":"UNAVAILABLE","average":null,"ratingsCount":null},"demo":true}],
            "page":0,"size":2,"totalItems":4,"totalPages":2}
        """
        val page = json.decodeFromString<PageDto<PlaceSummaryDto>>(body).toModel { it.toModel() }

        assertEquals(4L, page.totalItems)
        assertTrue(page.hasMore)
        val place = page.items.single()
        assertEquals("Clube de Combate Ribeira (fictício)", place.name)
        assertEquals("Centro Histórico", place.parish)
        assertEquals(listOf("Boxe", "Kickboxing"), place.sports.map { it.name })
        assertEquals(Rating.Unavailable, place.rating)
        assertTrue(place.isDemo)
    }

    @Test
    fun `distanceKm e campos desconhecidos sao aceites`() {
        val body = """
            {"items":[{"id":"x","name":"Local","address":{"street":"Rua","postalCode":"4000-001","city":"Porto"},
            "location":{"latitude":41.1,"longitude":-8.6},"sports":[],"distanceKm":1.02,
            "rating":{"status":"NO_RATINGS","average":null,"ratingsCount":0},"demo":false,"novoCampo":"ignorado"}],
            "page":1,"size":20,"totalItems":21,"totalPages":2}
        """
        val page = json.decodeFromString<PageDto<PlaceSummaryDto>>(body).toModel { it.toModel() }

        assertFalse(page.hasMore) // página 1 de 2 (começa em 0) é a última
        assertNull(page.items.single().parish)
        assertEquals(Rating.NoRatings, page.items.single().rating)
    }

    @Test
    fun `detalhe com atividades, niveis e modalidades sem repeticoes`() {
        val body = """
            {"id":"p1","name":"Local","description":null,
            "address":{"street":"Rua Exemplo, 10","postalCode":"4050-001","parish":null,"city":"Porto"},
            "location":{"latitude":41.1,"longitude":-8.6},"contacts":{"phone":null,"website":"https://example.org"},
            "rating":{"status":"AVAILABLE","average":4.3,"ratingsCount":12},
            "offerings":[
              {"id":"o1","sport":{"id":"boxe","name":"Boxe"},"name":"Boxe iniciação","description":null,"level":"BEGINNER","scheduleNotes":"Seg., 19h","rating":{"status":"UNAVAILABLE"}},
              {"id":"o2","sport":{"id":"boxe","name":"Boxe"},"name":"Boxe competição","level":"ADVANCED","rating":{"status":"UNAVAILABLE"}},
              {"id":"o3","sport":{"id":"ioga","name":"Ioga"},"name":"Ioga","level":"NOVO_NIVEL","rating":{"status":"UNAVAILABLE"}}
            ],"demo":true}
        """
        val place = json.decodeFromString<PlaceDetailDto>(body).toModel()

        assertEquals(Rating.Available(4.3, 12), place.rating)
        assertEquals("https://example.org", place.website)
        assertEquals(listOf("boxe", "ioga"), place.sports.map { it.id })
        assertEquals(
            listOf(ActivityLevel.BEGINNER, ActivityLevel.ADVANCED, ActivityLevel.UNKNOWN),
            place.offerings.map { it.level },
        )
        assertEquals("Seg., 19h", place.offerings.first().scheduleNotes)
    }

    @Test
    fun `estados de avaliacao`() {
        assertEquals(Rating.Available(3.5, 2), RatingDto("AVAILABLE", 3.5, 2).toModel())
        assertEquals(Rating.NoRatings, RatingDto("NO_RATINGS", null, 0).toModel())
        assertEquals(Rating.Unavailable, RatingDto("UNAVAILABLE").toModel())
        // Dados incoerentes ou estados novos nunca viram uma avaliação inventada.
        assertEquals(Rating.Unavailable, RatingDto("AVAILABLE", null, null).toModel())
        assertEquals(Rating.Unavailable, RatingDto("AVAILABLE", 4.0, 0).toModel())
        assertEquals(Rating.Unavailable, RatingDto("OUTRO").toModel())
    }
}

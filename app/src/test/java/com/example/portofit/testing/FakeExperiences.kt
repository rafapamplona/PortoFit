package com.example.portofit.testing

import com.example.portofit.data.model.Experience
import com.example.portofit.data.model.ExperienceSort
import com.example.portofit.data.model.Page
import com.example.portofit.data.repository.DataResult
import com.example.portofit.data.repository.ExperienceDraft
import com.example.portofit.data.repository.ExperiencesRepository
import com.example.portofit.data.repository.MutationResult
import com.example.portofit.data.repository.PublishOutcome
import java.time.Instant

data class ListCall(val placeId: String?, val authorId: String?, val sort: ExperienceSort, val page: Int, val pageSize: Int)

/** Repositório de experiências controlado pelos testes (cada resposta é uma função, que pode suspender). */
class FakeExperiencesRepository : ExperiencesRepository {
    val listCalls = mutableListOf<ListCall>()
    val publishCalls = mutableListOf<Pair<String, ExperienceDraft>>()
    val editCalls = mutableListOf<Triple<String, String?, Int?>>()
    val deleteCalls = mutableListOf<String>()

    var onList: suspend (ListCall) -> DataResult<Page<Experience>> = { call -> DataResult.Success(pageOf(call.page, 1, call.placeId ?: "p1")) }
    var onGet: suspend (String) -> DataResult<Experience> = { id -> DataResult.Success(experience(id)) }
    var onPublish: suspend (String, ExperienceDraft) -> PublishOutcome = { _, draft ->
        PublishOutcome.Published(experience("e-new", placeId = draft.placeId, text = draft.text, stars = draft.stars), replayed = false)
    }
    var onEdit: suspend (String, String?, Int?) -> MutationResult<Experience> = { id, text, stars ->
        val base = experience(id)
        MutationResult.Success(base.copy(text = text ?: base.text, stars = stars ?: base.stars, editedAt = Instant.parse("2026-10-20T10:00:00Z")))
    }
    var onDelete: suspend (String) -> MutationResult<Unit> = { MutationResult.Success(Unit) }

    override suspend fun list(placeId: String?, authorId: String?, sort: ExperienceSort, page: Int, pageSize: Int): DataResult<Page<Experience>> {
        val call = ListCall(placeId, authorId, sort, page, pageSize)
        listCalls += call
        return onList(call)
    }

    override suspend fun get(experienceId: String) = onGet(experienceId)

    override suspend fun publish(idempotencyKey: String, draft: ExperienceDraft): PublishOutcome {
        publishCalls += idempotencyKey to draft
        return onPublish(idempotencyKey, draft)
    }

    override suspend fun edit(experienceId: String, text: String?, stars: Int?): MutationResult<Experience> {
        editCalls += Triple(experienceId, text, stars)
        return onEdit(experienceId, text, stars)
    }

    override suspend fun delete(experienceId: String): MutationResult<Unit> {
        deleteCalls += experienceId
        return onDelete(experienceId)
    }

    companion object {
        fun experience(
            id: String,
            authorId: String = "author-1",
            placeId: String = "p1",
            text: String = "Boa aula.",
            stars: Int = 4,
        ) = Experience(
            id = id,
            authorId = authorId,
            authorUsername = "ana",
            placeId = placeId,
            placeName = "Clube de Combate Ribeira (fictício)",
            placeDemo = true,
            offeringId = "o1",
            offeringName = "Boxe iniciação",
            sportName = "Boxe",
            text = text,
            stars = stars,
            createdAt = Instant.parse("2026-10-19T18:30:00Z"),
            editedAt = null,
        )

        /** Página [page] de [totalPages], com experiências "e<página>-<n>". */
        fun pageOf(page: Int, totalPages: Int, placeId: String = "p1", perPage: Int = 2) = Page(
            items = (1..perPage).map { experience("e$page-$it", placeId = placeId) },
            page = page,
            totalItems = (totalPages * perPage).toLong(),
            totalPages = totalPages,
        )
    }
}

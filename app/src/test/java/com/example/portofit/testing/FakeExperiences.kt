package com.example.portofit.testing

import com.example.portofit.data.model.Comment
import com.example.portofit.data.model.Experience
import com.example.portofit.data.model.ExperienceSort
import com.example.portofit.data.model.Page
import com.example.portofit.data.repository.CommentOutcome
import com.example.portofit.data.repository.DataResult
import com.example.portofit.data.repository.LikeResult
import com.example.portofit.data.repository.ExperienceDraft
import com.example.portofit.data.repository.ExperiencesRepository
import com.example.portofit.data.repository.MutationResult
import com.example.portofit.data.repository.PhotoChange
import com.example.portofit.data.repository.UploadOutcome
import java.io.File
import com.example.portofit.data.repository.PublishOutcome
import java.time.Instant

data class ListCall(val placeId: String?, val authorId: String?, val sort: ExperienceSort, val page: Int, val pageSize: Int)
data class ExperienceSearchCall(val query: String, val sort: ExperienceSort, val page: Int, val pageSize: Int)

/** Repositório de experiências controlado pelos testes (cada resposta é uma função, que pode suspender). */
class FakeExperiencesRepository : ExperiencesRepository {
    val listCalls = mutableListOf<ListCall>()
    val searchCalls = mutableListOf<ExperienceSearchCall>()
    var onSearch: suspend (ExperienceSearchCall) -> DataResult<Page<Experience>> = { call -> DataResult.Success(pageOf(call.page, 1)) }
    val publishCalls = mutableListOf<Pair<String, ExperienceDraft>>()
    val editCalls = mutableListOf<Triple<String, String?, Int?>>()
    val editPhotoChanges = mutableListOf<PhotoChange>()
    val uploadCalls = mutableListOf<File>()
    var onUpload: suspend (File) -> UploadOutcome = { UploadOutcome.Uploaded("media-${uploadCalls.size}", 1600, 1200) }
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

    // --- E3 ---
    val getCalls = mutableListOf<String>()
    val commentPageCalls = mutableListOf<Int>()
    var onComments: suspend (Int) -> DataResult<Page<Comment>> = { page -> DataResult.Success(Page(emptyList(), page, 0, 0)) }
    /** (chave, experiência, texto) por ordem. */
    val postCommentCalls = mutableListOf<Triple<String, String, String>>()
    var onPostComment: suspend (String, String, String) -> CommentOutcome = { _, experienceId, text ->
        CommentOutcome.Posted(comment("c-new", experienceId = experienceId, text = text), replayed = false)
    }
    val editCommentCalls = mutableListOf<Pair<String, String>>()
    var onEditComment: suspend (String, String) -> MutationResult<Comment> = { id, text ->
        MutationResult.Success(comment(id, text = text).copy(editedAt = Instant.parse("2026-10-20T10:00:00Z")))
    }
    val deleteCommentCalls = mutableListOf<String>()
    var onDeleteComment: suspend (String) -> MutationResult<Unit> = { MutationResult.Success(Unit) }
    val likeCalls = mutableListOf<Pair<String, Boolean>>()
    var onSetLiked: suspend (String, Boolean) -> LikeResult = { _, _ -> LikeResult.Confirmed }
    val viewCalls = mutableListOf<String>()

    override suspend fun list(placeId: String?, authorId: String?, sort: ExperienceSort, page: Int, pageSize: Int): DataResult<Page<Experience>> {
        val call = ListCall(placeId, authorId, sort, page, pageSize)
        listCalls += call
        return onList(call)
    }

    override suspend fun search(query: String, sort: ExperienceSort, page: Int, pageSize: Int): DataResult<Page<Experience>> {
        val call = ExperienceSearchCall(query, sort, page, pageSize)
        searchCalls += call
        return onSearch(call)
    }

    override suspend fun get(experienceId: String): DataResult<Experience> {
        getCalls += experienceId
        return onGet(experienceId)
    }

    override suspend fun publish(idempotencyKey: String, draft: ExperienceDraft): PublishOutcome {
        publishCalls += idempotencyKey to draft
        return onPublish(idempotencyKey, draft)
    }

    override suspend fun edit(experienceId: String, text: String?, stars: Int?, photo: PhotoChange): MutationResult<Experience> {
        editCalls += Triple(experienceId, text, stars)
        editPhotoChanges += photo
        return onEdit(experienceId, text, stars)
    }

    override suspend fun uploadPhoto(file: File): UploadOutcome {
        uploadCalls += file
        return onUpload(file)
    }

    override suspend fun delete(experienceId: String): MutationResult<Unit> {
        deleteCalls += experienceId
        return onDelete(experienceId)
    }

    override suspend fun comments(experienceId: String, page: Int, pageSize: Int): DataResult<Page<Comment>> {
        commentPageCalls += page
        return onComments(page)
    }

    override suspend fun postComment(idempotencyKey: String, experienceId: String, text: String): CommentOutcome {
        postCommentCalls += Triple(idempotencyKey, experienceId, text)
        return onPostComment(idempotencyKey, experienceId, text)
    }

    override suspend fun editComment(commentId: String, text: String): MutationResult<Comment> {
        editCommentCalls += commentId to text
        return onEditComment(commentId, text)
    }

    override suspend fun deleteComment(commentId: String): MutationResult<Unit> {
        deleteCommentCalls += commentId
        return onDeleteComment(commentId)
    }

    override suspend fun setLiked(experienceId: String, liked: Boolean): LikeResult {
        likeCalls += experienceId to liked
        return onSetLiked(experienceId, liked)
    }

    override suspend fun registerView(experienceId: String): Boolean {
        viewCalls += experienceId
        return true
    }

    companion object {
        fun comment(id: String, authorId: String = "user-2", experienceId: String = "e1", text: String = "Comentário") =
            Comment(id, experienceId, authorId, "rui", text, Instant.parse("2026-10-19T19:00:00Z"), null)

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

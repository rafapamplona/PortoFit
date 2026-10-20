package com.example.portofit.testing

import com.example.portofit.data.model.Page
import com.example.portofit.data.model.SavedAvailability
import com.example.portofit.data.model.SavedItem
import com.example.portofit.data.model.SavedItemsPage
import com.example.portofit.data.model.UserCollection
import com.example.portofit.data.repository.CollectionResult
import com.example.portofit.data.repository.CollectionsRepository
import com.example.portofit.data.repository.CreateCollectionOutcome
import com.example.portofit.data.repository.SessionResult
import java.time.Instant

/** Listas controladas pelos testes (cada resposta é uma função, que pode suspender). */
class FakeCollectionsRepository : CollectionsRepository {
    val listCalls = mutableListOf<Int>()
    val getCalls = mutableListOf<String>()
    val createCalls = mutableListOf<Pair<String, String>>()
    val renameCalls = mutableListOf<Pair<String, String>>()
    val deleteCalls = mutableListOf<String>()
    val itemsCalls = mutableListOf<Pair<String, Int>>()
    val addCalls = mutableListOf<Pair<String, String>>()
    val removeCalls = mutableListOf<Pair<String, String>>()

    var onList: suspend (Int) -> SessionResult<Page<UserCollection>> = { page -> SessionResult.Success(listPage(page, 1)) }
    var onGet: suspend (String) -> SessionResult<UserCollection> = { id -> SessionResult.Success(collection(id)) }
    var onCreate: suspend (String, String) -> CreateCollectionOutcome = { _, name -> CreateCollectionOutcome.Created(collection("c-new", name), replayed = false) }
    var onRename: suspend (String, String) -> CollectionResult<UserCollection> = { id, name -> CollectionResult.Success(collection(id, name)) }
    var onDelete: suspend (String) -> CollectionResult<Unit> = { CollectionResult.Success(Unit) }
    var onItems: suspend (String, Int) -> SessionResult<SavedItemsPage> = { _, page -> SessionResult.Success(itemsPage(page, 1)) }
    var onAdd: suspend (String, String) -> CollectionResult<Unit> = { _, _ -> CollectionResult.Success(Unit) }
    var onRemove: suspend (String, String) -> CollectionResult<Unit> = { _, _ -> CollectionResult.Success(Unit) }

    override suspend fun list(page: Int, pageSize: Int): SessionResult<Page<UserCollection>> {
        listCalls += page
        return onList(page)
    }

    override suspend fun get(collectionId: String): SessionResult<UserCollection> {
        getCalls += collectionId
        return onGet(collectionId)
    }

    override suspend fun create(idempotencyKey: String, name: String): CreateCollectionOutcome {
        createCalls += idempotencyKey to name
        return onCreate(idempotencyKey, name)
    }

    override suspend fun rename(collectionId: String, name: String): CollectionResult<UserCollection> {
        renameCalls += collectionId to name
        return onRename(collectionId, name)
    }

    override suspend fun delete(collectionId: String): CollectionResult<Unit> {
        deleteCalls += collectionId
        return onDelete(collectionId)
    }

    override suspend fun items(collectionId: String, page: Int, pageSize: Int): SessionResult<SavedItemsPage> {
        itemsCalls += collectionId to page
        return onItems(collectionId, page)
    }

    override suspend fun add(collectionId: String, experienceId: String): CollectionResult<Unit> {
        addCalls += collectionId to experienceId
        return onAdd(collectionId, experienceId)
    }

    override suspend fun remove(collectionId: String, experienceId: String): CollectionResult<Unit> {
        removeCalls += collectionId to experienceId
        return onRemove(collectionId, experienceId)
    }

    companion object {
        private val AT = Instant.parse("2026-10-20T10:00:00Z")

        fun collection(id: String, name: String = "Lista $id", itemCount: Long = 0) = UserCollection(id, name, itemCount, AT, AT)

        /** Página [page] de [totalPages], com listas "<prefixo><página>-<n>". */
        fun listPage(page: Int, totalPages: Int, prefix: String = "c", perPage: Int = 2) = Page(
            (1..perPage).map { collection("$prefix$page-$it") }, page, (totalPages * perPage).toLong(), totalPages,
        )

        fun item(id: String, availability: SavedAvailability = SavedAvailability.AVAILABLE) = SavedItem(
            id, AT, availability, if (availability == SavedAvailability.AVAILABLE) FakeExperiencesRepository.experience(id) else null,
        )

        fun itemsPage(page: Int, totalPages: Int, items: List<SavedItem>? = null, available: Boolean = true) = SavedItemsPage(
            Page(items ?: listOf(item("x$page-1"), item("x$page-2")), page, (totalPages * 2).toLong(), totalPages),
            available,
        )
    }
}

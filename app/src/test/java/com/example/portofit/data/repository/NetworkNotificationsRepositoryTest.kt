package com.example.portofit.data.repository

import com.example.portofit.data.auth.FailureKind
import com.example.portofit.data.auth.GatewayResult
import com.example.portofit.data.auth.SessionManager
import com.example.portofit.data.auth.SessionState
import com.example.portofit.data.auth.SessionStatus
import com.example.portofit.data.auth.StoredSession
import com.example.portofit.data.model.NotificationTarget
import com.example.portofit.data.model.NotificationType
import com.example.portofit.data.remote.MarkAllReadDto
import com.example.portofit.data.remote.NotificationActorDto
import com.example.portofit.data.remote.NotificationDto
import com.example.portofit.data.remote.NotificationsApi
import com.example.portofit.data.remote.NotificationsPageDto
import com.example.portofit.data.remote.SessionDto
import com.example.portofit.data.remote.UnreadCountDto
import com.example.portofit.notifications.NotificationPoller
import com.example.portofit.notifications.NotificationsCoordinator
import com.example.portofit.notifications.PollOutcome
import com.example.portofit.testing.FakeAuthGateway
import com.example.portofit.testing.FakeAuthGateway.Companion.session
import com.example.portofit.testing.FakeSystemNotifier
import com.example.portofit.testing.FakeWorkScheduler
import com.example.portofit.testing.InMemoryCursorStore
import com.example.portofit.testing.InMemorySessionStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Response

@OptIn(ExperimentalCoroutinesApi::class)
class NetworkNotificationsRepositoryTest {

    private val gateway = FakeAuthGateway()

    private class FakeApi : NotificationsApi {
        val authorizations = mutableListOf<String>()
        var pageResponse: () -> Response<NotificationsPageDto> = { Response.success(NotificationsPageDto(emptyList(), 0, false, false)) }
        var unauthorizedOnce = false

        private fun <T> answer(authorization: String, ok: () -> Response<T>): Response<T> {
            authorizations += authorization
            if (unauthorizedOnce) {
                unauthorizedOnce = false
                return Response.error(401, "{}".toResponseBody("application/json".toMediaType()))
            }
            return ok()
        }

        override suspend fun notifications(authorization: String, before: Long?, after: Long?, limit: Int) = answer(authorization, pageResponse)
        override suspend fun unreadCount(authorization: String) = answer(authorization) { Response.success(UnreadCountDto(3)) }
        override suspend fun markRead(authorization: String, notificationId: String) = answer(authorization) { Response.success(UnreadCountDto(2)) }
        override suspend fun markAllRead(authorization: String, body: MarkAllReadDto) = answer(authorization) { Response.success(UnreadCountDto(0)) }
    }

    private fun dto(seq: Long, type: String, kind: String? = null, experienceId: String? = "e$seq", conversationId: String? = "c$seq") = NotificationDto(
        id = "n$seq", seq = seq, type = type, actor = NotificationActorDto("rui-id", "rui"), conversationId = conversationId, messageSeq = 4,
        messageKind = kind, experienceId = experienceId, commentId = "k$seq", occurredAt = "2026-10-22T10:00:00Z", createdAt = "2026-10-22T10:00:01Z", read = false,
    )

    @Test
    fun `converte os quatro tipos, ignora os desconhecidos e mantem os limites do seq`() = runTest {
        val api = FakeApi()
        api.pageResponse = {
            Response.success(
                NotificationsPageDto(
                    listOf(
                        dto(9, "LIKE_CREATED"), // tipo de uma versão futura: ignorado
                        dto(8, "MESSAGE_RECEIVED", kind = "SHARE"),
                        dto(7, "COMMENT_CREATED"),
                        dto(6, "USER_FOLLOWED", experienceId = null),
                        dto(5, "EXPERIENCE_PUBLISHED"),
                        dto(4, "COMMENT_CREATED", experienceId = null), // sem a referência do destino: ignorado
                    ),
                    lastSeq = 9, hasMoreBefore = true, hasMoreAfter = false,
                ),
            )
        }
        val session = SessionManager(gateway, InMemorySessionStore(), CoroutineScope(Dispatchers.Unconfined), io = UnconfinedTestDispatcher())
        session.login("ana@example.org", "uma frase longa")
        val page = (NetworkNotificationsRepository(api, session).page(null, null, 20) as SessionResult.Success).value
        assertEquals(listOf(8L, 7L, 6L, 5L), page.items.map { it.seq })
        assertEquals(4L to 9L, page.lowestSeq to page.highestSeq)
        assertEquals(NotificationTarget.Conversation("c8", "rui"), page.items[0].target)
        assertTrue(page.items[0].shared)
        assertEquals(NotificationTarget.Experience("e7"), page.items[1].target)
        assertEquals(NotificationTarget.Person("rui-id"), page.items[2].target)
        assertEquals(NotificationType.EXPERIENCE_PUBLISHED, page.items[3].type)
    }

    @Test
    fun `um 401 renova uma vez e repete com o token novo`() = runTest {
        val api = FakeApi()
        val session = SessionManager(gateway, InMemorySessionStore(), CoroutineScope(Dispatchers.Unconfined), io = UnconfinedTestDispatcher())
        session.login("ana@example.org", "uma frase longa")
        api.unauthorizedOnce = true
        assertEquals(SessionResult.Success(3), NetworkNotificationsRepository(api, session).unreadCount())
        assertEquals(listOf("Bearer a-login", "Bearer a-0"), api.authorizations)
        assertEquals(1, gateway.refreshCalls.size)
    }

    // --- Worker e app partilham a mesma sessão (docs/notifications-api.md §15.3) ---

    private val stored = StoredSession("user-1", "ana", "r-guardado")

    @Test
    fun `worker e ecras ao mesmo tempo fazem uma unica renovacao`() = runTest {
        val gate = CompletableDeferred<GatewayResult<SessionDto>>()
        gateway.onRefresh = { gate.await() }
        val session = SessionManager(gateway, InMemorySessionStore(stored), this, io = StandardTestDispatcher(testScheduler))
        val api = FakeApi()
        val repository = NetworkNotificationsRepository(api, session)
        val cursors = InMemoryCursorStore()
        val notifier = FakeSystemNotifier()
        val coordinator = NotificationsCoordinator(session.state, FakeWorkScheduler(), notifier, cursors, backgroundScope, clock = { 0 })
        val poller = NotificationPoller(repository, session.state, coordinator, cursors, notifier, clock = { 0 })

        session.start() // a app arranca (ex.: pelo WorkManager, com a atividade fechada)
        val worker = async { poller.poll("user-1") }
        val screen = async { repository.unreadCount() }
        advanceUntilIdle()
        gate.complete(GatewayResult.Success(session("r-1", "a-1", withUser = false)))
        advanceUntilIdle()

        assertEquals(PollOutcome.DONE, worker.await())
        assertEquals(SessionResult.Success(3), screen.await())
        assertEquals(listOf("r-guardado"), gateway.refreshCalls)
        assertTrue(api.authorizations.all { it == "Bearer a-1" })
        assertEquals(SessionStatus.ACTIVE, (session.state.value as SessionState.SignedIn).status)
    }

    @Test
    fun `depois de uma renovacao ambigua o worker nao volta a tentar`() = runTest {
        gateway.onRefresh = { GatewayResult.Failure(FailureKind.AMBIGUOUS) }
        val session = SessionManager(gateway, InMemorySessionStore(stored), this, io = StandardTestDispatcher(testScheduler))
        val api = FakeApi()
        val repository = NetworkNotificationsRepository(api, session)
        val cursors = InMemoryCursorStore()
        val notifier = FakeSystemNotifier()
        val coordinator = NotificationsCoordinator(session.state, FakeWorkScheduler(), notifier, cursors, backgroundScope, clock = { 0 })
        val poller = NotificationPoller(repository, session.state, coordinator, cursors, notifier, clock = { 0 })

        session.start()
        advanceUntilIdle()
        assertEquals(SessionStatus.UNCONFIRMED, (session.state.value as SessionState.SignedIn).status)

        assertEquals(PollOutcome.SKIPPED, poller.poll("user-1"))
        assertEquals(PollOutcome.SKIPPED, poller.poll("user-1"))
        assertEquals(1, gateway.refreshCalls.size) // só a do arranque
        assertTrue(api.authorizations.isEmpty())
        assertTrue(notifier.shown.isEmpty())
    }
}

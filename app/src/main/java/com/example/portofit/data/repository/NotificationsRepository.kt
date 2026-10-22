package com.example.portofit.data.repository

import com.example.portofit.data.auth.SessionManager
import com.example.portofit.data.auth.SessionState
import com.example.portofit.data.model.AppNotification
import com.example.portofit.data.model.NotificationTarget
import com.example.portofit.data.model.NotificationType
import com.example.portofit.data.model.NotificationsPage
import com.example.portofit.data.remote.MarkAllReadDto
import com.example.portofit.data.remote.NotificationDto
import com.example.portofit.data.remote.NotificationsApi
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerializationException
import retrofit2.Response
import java.io.IOException
import java.time.Instant
import java.time.format.DateTimeParseException

/** Caixa de notificações (docs/notifications-api.md §5). Sempre com sessão. */
interface NotificationsRepository {
    /** [before] e [after] nunca juntos; sem nenhum, as mais recentes. */
    suspend fun page(before: Long?, after: Long?, limit: Int): SessionResult<NotificationsPage>
    suspend fun unreadCount(): SessionResult<Int>

    /** Devolve a contagem por ler depois de marcar. */
    suspend fun markRead(notificationId: String): SessionResult<Int>

    /** Todas até [upToSeq] (o servidor nunca passa do seu `lastSeq`). */
    suspend fun markAllRead(upToSeq: Long): SessionResult<Int>
}

/**
 * Todas as chamadas com o access token do [SessionManager] (o mesmo da app e do worker): um 401 renova uma vez e repete.
 * Sem token (sem sessão, ou sessão por confirmar depois de uma renovação ambígua) o pedido nem é enviado.
 */
class NetworkNotificationsRepository(
    private val api: NotificationsApi,
    private val session: SessionManager,
) : NotificationsRepository {

    override suspend fun page(before: Long?, after: Long?, limit: Int) =
        read({ api.notifications(it, before, after, limit) }) { dto ->
            // Um tipo desconhecido (versão futura do servidor) é ignorado, nunca parte a caixa.
            NotificationsPage(
                dto.items.mapNotNull { it.toModel() }, dto.lastSeq, dto.hasMoreBefore, dto.hasMoreAfter,
                lowestSeq = dto.items.minOfOrNull { it.seq }, highestSeq = dto.items.maxOfOrNull { it.seq },
            )
        }

    override suspend fun unreadCount() = read({ api.unreadCount(it) }) { it.unreadCount }

    override suspend fun markRead(notificationId: String) = read({ api.markRead(it, notificationId) }) { it.unreadCount }

    override suspend fun markAllRead(upToSeq: Long) = read({ api.markAllRead(it, MarkAllReadDto(upToSeq)) }) { it.unreadCount }

    private suspend fun <D, T> read(call: suspend (String) -> Response<D>, convert: (D) -> T): SessionResult<T> =
        try {
            val response = authorized(call) ?: return if (session.state.value is SessionState.SignedIn) SessionResult.SessionUnverified else SessionResult.SessionEnded
            when {
                response.isSuccessful -> SessionResult.Success(convert(response.body()!!))
                response.code() == 401 -> SessionResult.SessionEnded
                else -> SessionResult.Failure(
                    when (response.code()) {
                        404 -> DataError.NOT_FOUND
                        400 -> DataError.INVALID_REQUEST
                        in 500..599 -> DataError.SERVER
                        else -> DataError.UNEXPECTED
                    },
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            SessionResult.Failure(DataError.NETWORK)
        } catch (e: SerializationException) {
            SessionResult.Failure(DataError.UNEXPECTED)
        } catch (e: IllegalArgumentException) {
            SessionResult.Failure(DataError.UNEXPECTED)
        }

    /** null quando não há token: o pedido não chega a ser enviado. */
    private suspend fun <R> authorized(call: suspend (String) -> Response<R>): Response<R>? {
        val token = session.accessToken() ?: return null
        val response = call("Bearer $token")
        if (response.code() != 401) return response
        val fresh = session.accessTokenAfterUnauthorized(token) ?: return null
        return call("Bearer $fresh")
    }
}

/** null para um tipo desconhecido ou sem as referências do seu destino. */
fun NotificationDto.toModel(): AppNotification? {
    val kind = NotificationType.entries.firstOrNull { it.name == type } ?: return null
    val target = when (kind) {
        NotificationType.MESSAGE_RECEIVED -> NotificationTarget.Conversation(conversationId ?: return null, actor.username)
        NotificationType.COMMENT_CREATED, NotificationType.EXPERIENCE_PUBLISHED -> NotificationTarget.Experience(experienceId ?: return null)
        NotificationType.USER_FOLLOWED -> NotificationTarget.Person(actor.userId)
    }
    return AppNotification(
        id = id,
        seq = seq,
        type = kind,
        actorId = actor.userId,
        actorUsername = actor.username,
        shared = messageKind == "SHARE",
        target = target,
        createdAt = try {
            Instant.parse(createdAt)
        } catch (e: DateTimeParseException) {
            throw SerializationException("Data inválida")
        },
        read = read,
    )
}

class UnconfiguredNotificationsRepository : NotificationsRepository {
    override suspend fun page(before: Long?, after: Long?, limit: Int) = SessionResult.Failure(DataError.NOT_CONFIGURED)
    override suspend fun unreadCount() = SessionResult.Failure(DataError.NOT_CONFIGURED)
    override suspend fun markRead(notificationId: String) = SessionResult.Failure(DataError.NOT_CONFIGURED)
    override suspend fun markAllRead(upToSeq: Long) = SessionResult.Failure(DataError.NOT_CONFIGURED)
}

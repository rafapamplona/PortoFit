package com.example.portofit.data.remote

import kotlinx.serialization.Serializable
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.PUT
import retrofit2.http.Path
import retrofit2.http.Query

/** notifications-service (docs/notifications-api.md §5). Tudo com o access token: a caixa é privada. */
interface NotificationsApi {

    /** Sem cursor: as mais recentes (seq decrescente); `before`: histórico; `after`: as novas (seq crescente). */
    @GET("api/v1/notifications")
    suspend fun notifications(
        @Header("Authorization") authorization: String,
        @Query("before") before: Long?,
        @Query("after") after: Long?,
        @Query("limit") limit: Int,
    ): Response<NotificationsPageDto>

    @GET("api/v1/notifications/unread-count")
    suspend fun unreadCount(@Header("Authorization") authorization: String): Response<UnreadCountDto>

    @PUT("api/v1/notifications/{notificationId}/read")
    suspend fun markRead(@Header("Authorization") authorization: String, @Path("notificationId") notificationId: String): Response<UnreadCountDto>

    @PUT("api/v1/notifications/read")
    suspend fun markAllRead(@Header("Authorization") authorization: String, @Body body: MarkAllReadDto): Response<UnreadCountDto>
}

@Serializable
data class NotificationActorDto(val userId: String, val username: String)

/** Só referências: o servidor nunca tem o texto das mensagens, dos comentários nem das experiências (§9). */
@Serializable
data class NotificationDto(
    val id: String,
    val seq: Long,
    val type: String,
    val actor: NotificationActorDto,
    val conversationId: String? = null,
    val messageSeq: Long? = null,
    val messageKind: String? = null,
    val experienceId: String? = null,
    val commentId: String? = null,
    val occurredAt: String,
    val createdAt: String,
    val read: Boolean,
)

@Serializable
data class NotificationsPageDto(
    val items: List<NotificationDto>,
    val lastSeq: Long,
    val hasMoreBefore: Boolean,
    val hasMoreAfter: Boolean,
)

@Serializable
data class UnreadCountDto(val unreadCount: Int)

@Serializable
data class MarkAllReadDto(val upToSeq: Long)

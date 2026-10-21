package com.example.portofit.data.remote

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path
import retrofit2.http.Query

/** messaging-service (docs/messaging-api.md §4). Tudo com o access token: as conversas são privadas. */
interface MessagingApi {

    @GET("api/v1/conversations")
    suspend fun inbox(
        @Header("Authorization") authorization: String,
        @Query("cursor") cursor: String?,
        @Query("limit") limit: Int,
    ): Response<InboxDto>

    @POST("api/v1/conversations")
    suspend fun open(@Header("Authorization") authorization: String, @Body body: OpenConversationDto): Response<ConversationDto>

    @GET("api/v1/conversations/{conversationId}")
    suspend fun conversation(@Header("Authorization") authorization: String, @Path("conversationId") conversationId: String): Response<ConversationDto>

    @GET("api/v1/conversations/{conversationId}/messages")
    suspend fun messages(
        @Header("Authorization") authorization: String,
        @Path("conversationId") conversationId: String,
        @Query("before") before: Long?,
        @Query("after") after: Long?,
        @Query("limit") limit: Int,
    ): Response<MessagesDto>

    @POST("api/v1/conversations/{conversationId}/messages")
    suspend fun send(
        @Header("Authorization") authorization: String,
        @Header("Idempotency-Key") idempotencyKey: String,
        @Path("conversationId") conversationId: String,
        @Body body: SendMessageDto,
    ): Response<MessageDto>

    @GET("api/v1/conversations/{conversationId}/shares")
    suspend fun shares(
        @Header("Authorization") authorization: String,
        @Path("conversationId") conversationId: String,
        @Query("seqs") seqs: String,
    ): Response<SharesDto>

    @PUT("api/v1/conversations/{conversationId}/read")
    suspend fun markRead(
        @Header("Authorization") authorization: String,
        @Path("conversationId") conversationId: String,
        @Body body: ReadDto,
    ): Response<ReadDto>
}

@Serializable
data class ParticipantDto(val userId: String, val username: String)

@Serializable
data class LastMessageDto(val seq: Long, val senderId: String, val kind: String, val textPreview: String? = null, val createdAt: String)

@Serializable
data class ConversationDto(
    val id: String,
    val otherParticipant: ParticipantDto,
    val createdAt: String,
    val lastSeq: Long,
    val lastReadSeq: Long,
    val unreadCount: Int,
    val lastMessage: LastMessageDto? = null,
)

@Serializable
data class InboxDto(val items: List<ConversationDto>, val nextCursor: String? = null)

@Serializable
data class OpenConversationDto(val participantId: String)

/** `experience` fica como JSON: só é convertido quando a partilha está AVAILABLE (§7.2). */
@Serializable
data class SharedExperienceDto(val experienceId: String, val availability: String, val experience: JsonObject? = null)

@Serializable
data class MessageDto(
    val id: String,
    val seq: Long,
    val senderId: String,
    val text: String? = null,
    val sharedExperience: SharedExperienceDto? = null,
    val createdAt: String,
    val clientKey: String? = null,
)

@Serializable
data class MessagesDto(
    val items: List<MessageDto>,
    val lastSeq: Long,
    val hasMoreBefore: Boolean,
    val hasMoreAfter: Boolean,
    val experiencesStatus: String,
)

/** Sem `text` nem `experienceId` nulos no corpo: só se envia o que existe. */
@Serializable
data class SendMessageDto(val text: String? = null, val experienceId: String? = null)

@Serializable
data class ShareItemDto(val seq: Long, val sharedExperience: SharedExperienceDto)

@Serializable
data class SharesDto(val items: List<ShareItemDto>, val experiencesStatus: String)

@Serializable
data class ReadDto(val lastReadSeq: Long, val unreadCount: Int? = null)

/** Problem Details do messaging-service: 409 IDEMPOTENCY_CONFLICT traz os ids da mensagem já enviada. */
@Serializable
data class MessagingProblemDto(
    val code: String? = null,
    val reason: String? = null,
    val conversationId: String? = null,
    val messageId: String? = null,
)

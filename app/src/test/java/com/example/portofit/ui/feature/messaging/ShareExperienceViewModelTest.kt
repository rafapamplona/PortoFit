package com.example.portofit.ui.feature.messaging

import androidx.lifecycle.SavedStateHandle
import com.example.portofit.data.auth.SessionState
import com.example.portofit.data.model.InboxPage
import com.example.portofit.data.model.Page
import com.example.portofit.data.repository.DataError
import com.example.portofit.data.repository.DataResult
import com.example.portofit.data.repository.FollowResult
import com.example.portofit.data.repository.MessagingError
import com.example.portofit.data.repository.OpenConversationResult
import com.example.portofit.data.repository.PeopleRepository
import com.example.portofit.data.repository.PublicProfile
import com.example.portofit.data.repository.SendOutcome
import com.example.portofit.data.repository.SessionResult
import com.example.portofit.data.repository.UserSummary
import com.example.portofit.testing.FakeExperiencesRepository
import com.example.portofit.testing.FakeMessagingRepository
import com.example.portofit.testing.FakeMessagingRepository.Companion.ME
import com.example.portofit.testing.FakeMessagingRepository.Companion.conversationOf
import com.example.portofit.testing.FakeMessagingRepository.Companion.message
import com.example.portofit.testing.MainDispatcherRule
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class ShareExperienceViewModelTest {

    @get:Rule
    val mainDispatcher = MainDispatcherRule()

    private val messaging = FakeMessagingRepository()
    private val experiences = FakeExperiencesRepository()
    private val session = MutableStateFlow<SessionState>(SessionState.SignedIn(ME, "ana"))
    private var keys = 0

    private class FakePeople : PeopleRepository {
        val searches = mutableListOf<String>()
        override suspend fun search(query: String, page: Int, pageSize: Int): DataResult<Page<UserSummary>> {
            searches += query
            return DataResult.Success(Page(listOf(UserSummary(ME, "ana", null), UserSummary("bea-id", "bea", null)), 0, 2, 1))
        }
        override suspend fun profile(userId: String): DataResult<PublicProfile> = error("não usado")
        override suspend fun followers(userId: String, page: Int, pageSize: Int): DataResult<Page<UserSummary>> = error("não usado")
        override suspend fun following(userId: String, page: Int, pageSize: Int): DataResult<Page<UserSummary>> = error("não usado")
        override suspend fun setFollowing(userId: String, follow: Boolean): FollowResult = error("não usado")
    }

    private val people = FakePeople()

    private fun share(state: SavedStateHandle = SavedStateHandle(mapOf(ShareExperienceViewModel.EXPERIENCE_ID_ARG to "e1"))) =
        ShareExperienceViewModel(messaging, people, experiences, session, state, newKey = { "key-${++keys}" })

    @Test
    fun `visitante ve o convite e nada e pedido nem enviado`() {
        session.value = SessionState.SignedOut()
        val vm = share()
        vm.send()
        assertEquals(false, vm.uiState.value.signedIn)
        assertTrue(messaging.inboxCalls.isEmpty() && messaging.sendCalls.isEmpty() && messaging.openCalls.isEmpty())
    }

    @Test
    fun `conversa recente com texto opcional e so com confirmacao explicita`() {
        messaging.onInbox = { SessionResult.Success(InboxPage(listOf(conversationOf("c1")), null)) }
        val vm = share()
        assertTrue(vm.uiState.value.preview is PreviewState.Loaded)
        vm.chooseConversation((vm.uiState.value.recent as RecentState.Loaded).items.single())
        assertTrue("escolher não envia", messaging.sendCalls.isEmpty())
        vm.send()
        assertEquals(FakeMessagingRepository.SendCall("c1", "key-1", null, "e1"), messaging.sendCalls.single())
        assertEquals(ShareSendState.Sent("c1"), vm.uiState.value.send)
        vm.send()
        assertEquals("depois de enviada, não repete", 1, messaging.sendCalls.size)
    }

    @Test
    fun `pessoa sem conversa - users-service em falha mantem destinatario e texto`() {
        val vm = share()
        vm.onQueryChange("b")
        vm.search()
        assertTrue("menos de 2 caracteres", people.searches.isEmpty())
        vm.onQueryChange("be")
        vm.search()
        val results = (vm.uiState.value.people as PeopleSearchState.Results).items
        assertEquals("o próprio não aparece", listOf("bea-id"), results.map { it.userId })
        vm.choosePerson(results.single())
        vm.onTextChange("Olha esta")
        messaging.onOpen = { OpenConversationResult.Failed(MessagingError.USERS_UNAVAILABLE) }
        vm.send()
        assertEquals(ShareSendState.Failed(MessagingError.USERS_UNAVAILABLE), vm.uiState.value.send)
        assertEquals("Olha esta", vm.uiState.value.text)
        assertEquals(ShareTarget.ToPerson("bea-id", "bea"), vm.uiState.value.target)
        assertTrue(messaging.sendCalls.isEmpty())

        messaging.onOpen = { OpenConversationResult.Opened(conversationOf("c-bea", other = "bea-id")) }
        vm.send()
        assertEquals(FakeMessagingRepository.SendCall("c-bea", "key-2", "Olha esta", "e1"), messaging.sendCalls.single())
        assertEquals(ShareSendState.Sent("c-bea"), vm.uiState.value.send)
    }

    @Test
    fun `resultado desconhecido repete com a mesma chave e uma recusa temporaria mantem o texto`() {
        val state = SavedStateHandle(mapOf(ShareExperienceViewModel.EXPERIENCE_ID_ARG to "e1"))
        val vm = share(state)
        vm.chooseConversation(conversationOf("c1"))
        vm.onTextChange("Para ti")
        messaging.onSend = { SendOutcome.Unconfirmed }
        vm.send()
        assertEquals(ShareSendState.Unconfirmed, vm.uiState.value.send)
        vm.onTextChange("outro")
        assertEquals("bloqueado enquanto por confirmar", "Para ti", vm.uiState.value.text)
        // Recriação: destinatário, texto e tentativa mantêm-se.
        val recreated = share(state)
        assertEquals(ShareSendState.Unconfirmed, recreated.uiState.value.send)
        assertEquals(ShareTarget.ToConversation("c1", "rui"), recreated.uiState.value.target)
        messaging.onSend = { call -> SendOutcome.Sent(message(5, ME, call.text, clientKey = call.key), replayed = true) }
        vm.send()
        assertEquals(listOf("key-1", "key-1"), messaging.sendCalls.map { it.key })

        val other = share()
        other.chooseConversation(conversationOf("c1"))
        other.onTextChange("Texto")
        messaging.onSend = { SendOutcome.Rejected(MessagingError.EXPERIENCES_UNAVAILABLE) }
        other.send()
        assertEquals(ShareSendState.Failed(MessagingError.EXPERIENCES_UNAVAILABLE), other.uiState.value.send)
        assertEquals("Texto", other.uiState.value.text)
        messaging.onSend = { call -> SendOutcome.Sent(message(6, ME, call.text, clientKey = call.key), replayed = false) }
        other.send()
        assertEquals("a recusa descartou a chave", "key-3", messaging.sendCalls.last().key)
    }

    @Test
    fun `experiencia eliminada nao pode ser partilhada`() {
        experiences.onGet = { DataResult.Failure(DataError.NOT_FOUND) }
        val vm = share()
        vm.chooseConversation(conversationOf("c1"))
        assertTrue(vm.uiState.value.experienceGone)
        vm.send()
        assertTrue(messaging.sendCalls.isEmpty())
    }

    @Test
    fun `trocar de conta apaga destinatario, texto e tentativa`() {
        val state = SavedStateHandle(mapOf(ShareExperienceViewModel.EXPERIENCE_ID_ARG to "e1"))
        val vm = share(state)
        vm.chooseConversation(conversationOf("c1"))
        vm.onTextChange("Privado")
        messaging.onSend = { SendOutcome.Unconfirmed }
        vm.send()
        session.value = SessionState.SignedIn("bea-id", "bea")
        assertNull(vm.uiState.value.target)
        assertEquals("", vm.uiState.value.text)
        assertEquals(ShareSendState.Idle, vm.uiState.value.send)
        assertTrue(state.keys().none { it.startsWith("share.$ME.") })
    }
}

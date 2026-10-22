package com.example.portofit.ui.feature.messaging

import com.example.portofit.R
import com.example.portofit.data.model.MessageRules
import com.example.portofit.data.repository.MessagingError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class MessagingFormatTest {

    private val lisbon = ZoneId.of("Europe/Lisbon")

    @Test
    fun `hora so para hoje, data e hora para outros dias, no fuso do aparelho`() {
        val now = Instant.parse("2026-10-21T15:00:00Z")
        assertEquals("09:05", formatMessageTime(Instant.parse("2026-10-21T08:05:00Z"), now, lisbon))
        assertEquals("20/10/2026 23:30", formatMessageTime(Instant.parse("2026-10-20T22:30:00Z"), now, lisbon))
    }

    @Test
    fun `por ler ate 99, depois 99+`() {
        assertEquals("1", unreadLabel(1, "99+"))
        assertEquals("99", unreadLabel(99, "99+"))
        assertEquals("99+", unreadLabel(100, "99+"))
    }

    @Test
    fun `texto com as regras do servidor`() {
        assertEquals("Linha 1\nLinha 2\tfim", MessageRules.cleanText("  Linha 1\r\nLinha 2\tfim \n"))
        assertNull(MessageRules.cleanText("   "))
        assertNull(MessageRules.cleanText("a".repeat(1001)))
        assertNull(MessageRules.cleanText("com\u0007controlo"))
        assertEquals(1000, MessageRules.cleanText("😀".repeat(1000))?.codePointCount(0, 2000))
        assertTrue(MessageRules.isValidOptional(""))
        assertFalse(MessageRules.isValidOptional("a".repeat(1001)))
    }

    @Test
    fun `o teu texto mantem-se so quando ha rascunho`() {
        // Abrir a partir do perfil (sem texto) e partilha sem texto: a mensagem não promete um texto que não existe.
        assertEquals(R.string.messaging_error_users_unavailable, messagingErrorMessage(MessagingError.USERS_UNAVAILABLE))
        assertEquals(R.string.messaging_error_users_unavailable_draft, messagingErrorMessage(MessagingError.USERS_UNAVAILABLE, hasDraft = true))
        assertEquals(R.string.messaging_error_experiences_unavailable, messagingErrorMessage(MessagingError.EXPERIENCES_UNAVAILABLE, hasDraft = false))
        assertEquals(R.string.messaging_error_experiences_unavailable_draft, messagingErrorMessage(MessagingError.EXPERIENCES_UNAVAILABLE, hasDraft = true))
        // Abrir sem resposta: resultado desconhecido (nunca "nada foi criado").
        assertEquals(R.string.messaging_error_unavailable, messagingErrorMessage(MessagingError.UNAVAILABLE, hasDraft = true))
    }
}

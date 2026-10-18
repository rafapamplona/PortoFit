package com.example.portofit.di

import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Regras de novas tentativas automáticas e de ligações dos clientes HTTP. */
class HttpClientsTest {

    private val catalog = HttpClients.catalog()
    private val users = HttpClients.users(catalog)
    private val session = HttpClients.session(users)

    @Test
    fun `chamadas de sessao nunca sao repetidas automaticamente`() {
        assertFalse(session.retryOnConnectionFailure)
    }

    @Test
    fun `leituras e escritas idempotentes podem ser repetidas`() {
        assertTrue(catalog.retryOnConnectionFailure)
        assertTrue(users.retryOnConnectionFailure)
    }

    @Test
    fun `sessao e perfil partilham a pool de ligacoes curtas, separada da do catalogo`() {
        assertSame(users.connectionPool, session.connectionPool)
        assertFalse(users.connectionPool === catalog.connectionPool)
    }

    @Test
    fun `tempos de espera do users-service cobrem o BCrypt do login`() {
        assertTrue(users.readTimeoutMillis >= 20_000)
        assertTrue(session.callTimeoutMillis >= 25_000)
    }
}

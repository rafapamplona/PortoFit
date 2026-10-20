package com.example.portofit.di

import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * Clientes HTTP da app. Nenhum regista pedidos (palavras-passe e tokens nunca vão para logs).
 *
 * Regra de novas tentativas automáticas do OkHttp (`retryOnConnectionFailure`):
 * - catálogo e users-service (leituras e escritas idempotentes: GET, PATCH da descrição, PUT dos
 *   interesses) → ligadas;
 * - chamadas de sessão (registo, login, refresh, logout) → desligadas: uma renovação repetida às cegas
 *   pode reenviar um refresh token já consumido (docs/users-api.md §3.3).
 */
object HttpClients {

    /** O servidor (Tomcat) fecha ligações paradas ao fim de 10 s; a app descarta-as antes, aos 5 s. */
    const val USERS_IDLE_SECONDS = 5L

    fun catalog(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .callTimeout(15, TimeUnit.SECONDS)
        .build()

    /** users-service: mais tempo de leitura (o BCrypt demora no login) e pool própria de ligações curtas. */
    fun users(base: OkHttpClient): OkHttpClient = base.newBuilder()
        .connectionPool(ConnectionPool(2, USERS_IDLE_SECONDS, TimeUnit.SECONDS))
        .readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(25, TimeUnit.SECONDS)
        .build()

    /**
     * experiences-service: pool própria de ligações curtas (o servidor fecha-as aos 10 s, como o users-service).
     * As novas tentativas automáticas (só de ligação, antes de haver resposta) ficam ligadas: as leituras, o PATCH,
     * o DELETE e os gostos (PUT/DELETE) são idempotentes; a publicação e os comentários levam sempre uma
     * Idempotency-Key, pelo que uma repetição nunca cria um duplicado; e uma visualização repetida no mesmo período
     * não volta a contar no servidor. A app nunca repete estes POST por iniciativa própria.
     */
    fun experiences(base: OkHttpClient): OkHttpClient = base.newBuilder()
        .connectionPool(ConnectionPool(2, USERS_IDLE_SECONDS, TimeUnit.SECONDS))
        .build()

    /**
     * collections-service: pool própria de ligações curtas. As novas tentativas de ligação do OkHttp são seguras: as
     * leituras, o PATCH, o DELETE e o PUT dos itens são idempotentes, e a criação de listas leva sempre uma
     * Idempotency-Key (docs/collections-api.md §5.2). A app nunca repete pedidos por iniciativa própria.
     */
    fun collections(base: OkHttpClient): OkHttpClient = base.newBuilder()
        .connectionPool(ConnectionPool(2, USERS_IDLE_SECONDS, TimeUnit.SECONDS))
        .build()

    /** Chamadas de sessão: o cliente do users-service (mesma pool), sem novas tentativas automáticas. */
    fun session(users: OkHttpClient): OkHttpClient = users.newBuilder()
        .retryOnConnectionFailure(false)
        .build()
}

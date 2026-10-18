package com.example.portofit.data.auth

import com.example.portofit.testing.FakeCipher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

class SessionStorageAndGatewayTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val session = StoredSession("user-1", "ana", "refresh-token-secreto-123")

    private fun store(file: File = File(folder.root, "session/session.bin")) = FileSessionStore(file, FakeCipher())

    @Test
    fun `guardar e ler a sessao`() {
        val store = store()
        assertEquals(StoredSessionResult.None, store.load())
        store.save(session)
        assertEquals(StoredSessionResult.Found(session), store.load())
        store.save(session.copy(refreshToken = "outro-token"))
        assertEquals("outro-token", (store.load() as StoredSessionResult.Found).session.refreshToken)
    }

    @Test
    fun `o ficheiro nao contem o token em claro`() {
        val file = File(folder.root, "session/session.bin")
        store(file).save(session)
        val bytes = file.readBytes().decodeToString()
        assertFalse(bytes.contains("refresh-token-secreto-123"))
        assertFalse(File(file.parentFile, "session.bin.tmp").exists()) // escrita atómica concluída
    }

    @Test
    fun `ficheiro ilegivel e apagado e reportado`() {
        val file = File(folder.root, "session/session.bin").apply { parentFile!!.mkdirs(); writeBytes(byteArrayOf(1, 2, 3)) }
        val store = store(file)
        assertEquals(StoredSessionResult.Unreadable, store.load())
        assertFalse(file.exists())
        assertEquals(StoredSessionResult.None, store.load())
    }

    @Test
    fun `limpar apaga a sessao`() {
        val store = store()
        store.save(session)
        store.clear()
        assertEquals(StoredSessionResult.None, store.load())
    }

    @Test
    fun `toString nunca mostra o token`() {
        assertFalse(session.toString().contains("refresh-token"))
        assertTrue(session.toString().contains("ana"))
    }

    @Test
    fun `classificacao das falhas de rede`() {
        assertEquals(FailureKind.NOT_SENT, RetrofitAuthGateway.classify(UnknownHostException("x")))
        assertEquals(FailureKind.NOT_SENT, RetrofitAuthGateway.classify(ConnectException("recusada")))
        // Depois de o pedido poder ter chegado ao servidor: ambíguo.
        assertEquals(FailureKind.AMBIGUOUS, RetrofitAuthGateway.classify(SocketTimeoutException("timeout")))
        assertEquals(FailureKind.AMBIGUOUS, RetrofitAuthGateway.classify(IOException("unexpected end of stream")))
    }
}

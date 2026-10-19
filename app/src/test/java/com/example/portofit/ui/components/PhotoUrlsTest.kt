package com.example.portofit.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** O endereço das fotografias vem da configuração; os modelos só têm o caminho relativo. */
class PhotoUrlsTest {

    private val id = "25162773-eec4-426d-83ea-93b00d56ef16"

    @Test
    fun `resolve o caminho com o URL configurado`() {
        assertEquals("http://10.0.2.2:8083/api/v1/media/$id", PhotoUrls("http://10.0.2.2:8083/").resolve("/api/v1/media/$id"))
        assertEquals("https://api.exemplo.pt/api/v1/media/$id", PhotoUrls("https://api.exemplo.pt/").resolve("/api/v1/media/$id"))
    }

    @Test
    fun `recusa enderecos absolutos, outros caminhos e falta de configuracao`() {
        val urls = PhotoUrls("http://10.0.2.2:8083/")
        assertNull(urls.resolve("http://outro.exemplo/api/v1/media/$id"))
        assertNull(urls.resolve("//outro.exemplo/api/v1/media/$id"))
        assertNull(urls.resolve("/api/v1/experiences"))
        assertNull(urls.resolve("/api/v1/media/../../segredo"))
        assertNull(PhotoUrls("").resolve("/api/v1/media/$id"))
    }
}

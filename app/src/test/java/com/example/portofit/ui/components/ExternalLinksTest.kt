package com.example.portofit.ui.components

import com.example.portofit.data.model.GeoPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Ações externas do detalhe do local: só com dados válidos (docs/app-android.md §23). */
class ExternalLinksTest {

    @Test
    fun `site so com http ou https e dominio`() {
        assertEquals("https://foxgym.pt/", ExternalLinks.website(" https://foxgym.pt/ "))
        assertEquals("http://www.crossfitfoz.com/", ExternalLinks.website("http://www.crossfitfoz.com/"))
        assertEquals(
            "https://www.agoraporto.pt/desporto/programas-de-atividade-fisica",
            ExternalLinks.website("https://www.agoraporto.pt/desporto/programas-de-atividade-fisica"),
        )
        assertNull(ExternalLinks.website(null))
        assertNull(ExternalLinks.website(""))
        assertNull(ExternalLinks.website("foxgym.pt"))
        assertNull(ExternalLinks.website("javascript:alert(1)"))
        assertNull(ExternalLinks.website("https://localhost"))
        assertNull(ExternalLinks.website("https://exemplo .pt"))
    }

    @Test
    fun `telefone para o marcador sem separadores e com indicativo`() {
        assertEquals("225390111", ExternalLinks.dialNumber("225390111"))
        assertEquals("+351916435866", ExternalLinks.dialNumber("+351916435866"))
        assertEquals("+351916435866", ExternalLinks.dialNumber("00351916435866"))
        assertEquals("+351932214271", ExternalLinks.dialNumber("+351 932 214 271"))
        assertEquals("252023493", ExternalLinks.dialNumber("252 023 493"))
        assertEquals("tel:+351932214271", ExternalLinks.telUri("+351932214271"))
        assertNull(ExternalLinks.dialNumber(null))
        assertNull(ExternalLinks.dialNumber("12345"))
        assertNull(ExternalLinks.dialNumber("ligar 225390111"))
        assertNull(ExternalLinks.dialNumber("+"))
    }

    @Test
    fun `mapa com coordenadas validas e o nome codificado`() {
        assertEquals(
            "geo:41.171440,-8.687816?q=41.171440,-8.687816(Centro%20Municipal%20%E2%80%94%20Parque%20%28ponto%29)",
            ExternalLinks.geoUri(GeoPoint(41.17144, -8.687816), "Centro Municipal — Parque (ponto)"),
        )
        assertNull(ExternalLinks.geoUri(null, "Local"))
        assertNull(ExternalLinks.geoUri(GeoPoint(91.0, 0.0), "Local"))
        assertNull(ExternalLinks.geoUri(GeoPoint(0.0, -181.0), "Local"))
    }
}

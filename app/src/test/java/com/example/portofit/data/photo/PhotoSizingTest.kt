package com.example.portofit.data.photo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Tamanho da fotografia preparada segundo os limites do servidor (docs/experiences-api.md §12.2). */
class PhotoSizingTest {

    @Test
    fun `reduz o lado maior a 1600 e mantem a proporcao`() {
        assertEquals(1600 to 1200, PhotoSizing.target(4000, 3000))
        assertEquals(1200 to 1600, PhotoSizing.target(3000, 4000))
        assertEquals(800 to 600, PhotoSizing.target(800, 600))
    }

    @Test
    fun `respeita o minimo de 200 por lado`() {
        assertNull(PhotoSizing.target(150, 900))
        assertNull(PhotoSizing.target(0, 0))
        // Panorâmica: o lado menor não desce abaixo de 200 (o maior fica acima de 1600, dentro do limite do servidor).
        assertEquals(4000 to 200, PhotoSizing.target(8000, 400))
        // Proporção impossível de cumprir (lado maior acima de 8000 com o menor a 200).
        assertNull(PhotoSizing.target(20000, 400))
    }

    @Test
    fun `subamostragem por potencias de 2 sem ficar abaixo do tamanho final`() {
        assertEquals(2, PhotoSizing.sampleSize(4000, 3000, 1600, 1200))
        assertEquals(4, PhotoSizing.sampleSize(8000, 6000, 1600, 1200))
        assertEquals(1, PhotoSizing.sampleSize(1700, 1200, 1600, 1129))
    }

    @Test
    fun `so nomes gerados pela app sao aceites`() {
        assertTrue(PhotoSizing.isDraftName("photo-123e4567-e89b-12d3-a456-426614174000.jpg"))
        assertTrue(PhotoSizing.isDraftName("capture-123e4567-e89b-12d3-a456-426614174000.jpg"))
        assertFalse(PhotoSizing.isDraftName("../photo-123e4567-e89b-12d3-a456-426614174000.jpg"))
        assertFalse(PhotoSizing.isDraftName("IMG_0001.jpg"))
        assertFalse(PhotoSizing.isDraftName("photo-123e4567-e89b-12d3-a456-426614174000.jpg/../../x"))
    }
}

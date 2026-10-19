package com.aipaca.android.engine.profiles

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class QuantExtractTest {

    private val matcher = ProfileMatcher(ProfileCatalog(ProfileIndexDoc(), emptyMap(), emptyList()))

    @Test
    fun extractsLongestQuantToken() {
        assertEquals("Q4_0_8_8", matcher.extractQuant("model-q4_0_8_8"))
        assertEquals("BF16", matcher.extractQuant("x-bf16"))
        assertEquals("F16", matcher.extractQuant("x-f16"))
        assertEquals("Q4_K_M", matcher.extractQuant("Qwen3-4B-Q4_K_M.gguf"))
        assertEquals("Q2_K_XL", matcher.extractQuant("x-q2_k_xl.gguf"))
        assertEquals("IQ4_NL", matcher.extractQuant("x-iq4_nl.gguf"))
        assertEquals("Q4_0_4_4", matcher.extractQuant("x-q4_0_4_4.gguf"))
        assertEquals("IQ4_XS", matcher.extractQuant("x-iq4_xs.gguf"))
        assertEquals("Q8_0", matcher.extractQuant("Llama-Q8_0.gguf"))
        assertEquals("F32", matcher.extractQuant("x-f32.gguf"))
    }

    @Test
    fun returnsNullWhenNoQuantTokenPresent() {
        assertNull(matcher.extractQuant("plain.gguf"))
    }
}

package com.example.couponvault.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CodeExtractorTest {

    @Test
    fun testStandardHavellsUrl() {
        val input = "https://pta.havells.com/A51PEWKCWB81MXN"
        val expected = "A51PEWKCWB81MXN"
        assertEquals(expected, CodeExtractor.extractCode(input))
    }

    @Test
    fun testTrailingSlash() {
        val input = "https://pta.havells.com/A51PEWKCWB81MXN/"
        val expected = "A51PEWKCWB81MXN"
        assertEquals(expected, CodeExtractor.extractCode(input))
    }

    @Test
    fun testQueryString() {
        val input = "https://pta.havells.com/A51PEWKCWB81MXN?foo=bar"
        val expected = "A51PEWKCWB81MXN"
        assertEquals(expected, CodeExtractor.extractCode(input))
    }

    @Test
    fun testQueryStringAndTrailingSlash() {
        val input = "https://pta.havells.com/A51PEWKCWB81MXN/?source=qr&campaign=festive"
        val expected = "A51PEWKCWB81MXN"
        assertEquals(expected, CodeExtractor.extractCode(input))
    }

    @Test
    fun testLowercaseInput() {
        val input = "https://pta.havells.com/a51pewkcwb81mxn"
        val expected = "A51PEWKCWB81MXN"
        assertEquals(expected, CodeExtractor.extractCode(input))
    }

    @Test
    fun testLeadingAndTrailingWhitespace() {
        val input = "   https://pta.havells.com/A51PEWKCWB81MXN \n"
        val expected = "A51PEWKCWB81MXN"
        assertEquals(expected, CodeExtractor.extractCode(input))
    }

    @Test
    fun testStandaloneAlphanumericCode() {
        val input = "A51PEWKCWB81MXN"
        val expected = "A51PEWKCWB81MXN"
        assertEquals(expected, CodeExtractor.extractCode(input))
    }

    @Test
    fun testStandaloneAlphanumericLowercase() {
        val input = "a51pewkcwb81mxn"
        val expected = "A51PEWKCWB81MXN"
        assertEquals(expected, CodeExtractor.extractCode(input))
    }

    @Test
    fun testGarbageInputsReturnNull() {
        // Non-havells URL
        assertNull(CodeExtractor.extractCode("https://example.com/A51PEWKCWB81MXN"))
        assertNull(CodeExtractor.extractCode("https://google.com/test"))

        // Havells host but no path segment
        assertNull(CodeExtractor.extractCode("https://havells.com"))
        assertNull(CodeExtractor.extractCode("https://havells.com/"))

        // Too short (< 8 chars)
        assertNull(CodeExtractor.extractCode("ABC123"))

        // Too long (> 24 chars)
        assertNull(CodeExtractor.extractCode("ABCDEFGHIJKLMNOPQRSTUVWXYZ12345"))

        // Special characters in non-URL
        assertNull(CodeExtractor.extractCode("A51PEW-CWB81MXN"))
        assertNull(CodeExtractor.extractCode("A51PEW_CWB81MXN"))
        assertNull(CodeExtractor.extractCode("not a coupon!@#$"))

        // Empty and blank
        assertNull(CodeExtractor.extractCode(""))
        assertNull(CodeExtractor.extractCode("   "))
    }
}

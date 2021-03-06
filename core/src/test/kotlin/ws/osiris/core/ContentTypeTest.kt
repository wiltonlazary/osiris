package ws.osiris.core

import org.testng.annotations.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

@Test
class ContentTypeTest {

    fun parseSimple() {
        val (mimeType, charset) = ContentType.parse("text/plain; charset=UTF-8")
        assertEquals("text/plain", mimeType)
        assertEquals(Charsets.UTF_8, charset)
    }

    fun parseWithWhitespace() {
        val (mimeType, charset) = ContentType.parse("    text/plain    ; charset=UTF-8  ")
        assertEquals("text/plain", mimeType)
        assertEquals(Charsets.UTF_8, charset)
    }

    fun parseNoWhitespace() {
        val (mimeType, charset) = ContentType.parse("text/plain;charset=UTF-8")
        assertEquals("text/plain", mimeType)
        assertEquals(Charsets.UTF_8, charset)
    }

    fun parseUppercaseCharset() {
        val (mimeType, charset) = ContentType.parse("text/plain; CHARSET=UTF-8")
        assertEquals("text/plain", mimeType)
        assertEquals(Charsets.UTF_8, charset)
    }

    fun parseNoCharset() {
        val (mimeType, charset) = ContentType.parse("text/plain")
        assertEquals("text/plain", mimeType)
        assertNull(charset)
    }

    fun header() {
        assertEquals("text/plain; charset=UTF-8", ContentType("text/plain", Charsets.UTF_8).header)
    }

    fun headerNoCharset() {
        assertEquals("text/plain", ContentType("text/plain").header)
        assertEquals("text/plain", ContentType(" text/plain ").header)
    }

    fun multipartForm() {
        val contentType = ContentType.parse("multipart/form-data;boundary=abc123")
        assertEquals(contentType.mimeType, "multipart/form-data")
        assertEquals(contentType.header, "multipart/form-data; boundary=abc123")
        assertEquals(contentType.boundary, "abc123")
        assertNull(contentType.charset)
    }

    fun multipartFormWithWhitespace() {
        val contentType = ContentType.parse("  multipart/form-data ;  boundary=abc123   ")
        assertEquals(contentType.mimeType, "multipart/form-data")
        assertEquals(contentType.header, "multipart/form-data; boundary=abc123")
        assertEquals(contentType.boundary, "abc123")
        assertNull(contentType.charset)
    }
}

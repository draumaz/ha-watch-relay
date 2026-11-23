package xyz.draumaz.wristbridge.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import xyz.draumaz.wristbridge.mobile.ha.TokenQrParser

class TokenQrParserTest {

    @Test
    fun parsesRawHomeAssistantJwtToken() {
        val jwt = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiIxMjM0NTYifQ.signature123"
        val parsed = TokenQrParser.parse("  $jwt \n")
        assertEquals(jwt, parsed?.token)
        assertNull(parsed?.baseUrl)
    }

    @Test
    fun parsesJsonPayloadWithTokenAndUrl() {
        val raw = """{"url":"http://ha.tailnet.ts.net:8123","access_token":"abc.def.ghi"}"""
        val parsed = TokenQrParser.parse(raw)
        assertEquals("abc.def.ghi", parsed?.token)
        assertEquals("http://ha.tailnet.ts.net:8123", parsed?.baseUrl)
    }

    @Test
    fun parsesUriWithTokenQueryParam() {
        val raw = "https://home.example.com:8123/auth?token=my_secret_token"
        val parsed = TokenQrParser.parse(raw)
        assertEquals("my_secret_token", parsed?.token)
        assertEquals("https://home.example.com:8123", parsed?.baseUrl)
    }

    @Test
    fun rejectsBlankInput() {
        assertNull(TokenQrParser.parse("   "))
    }
}

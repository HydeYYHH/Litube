package com.hhst.youtubelite.core

import org.junit.Assert.assertEquals
import org.junit.Test

class MarkupTest {

    @Test
    fun xml_escapesMarkupSensitiveCharacters() {
        assertEquals(
            "&amp;&lt;&gt;&quot;",
            Markup.xml("&<>\""),
        )
    }

    @Test
    fun html_alsoEscapesApostrophe() {
        assertEquals("&#39;", Markup.html("'"))
    }

    @Test
    fun jsString_quotesAndEscapes() {
        assertEquals("\"http://192.168.1.2/x\"", Markup.jsString("http://192.168.1.2/x"))
        assertEquals("\"a\\\"b\"", Markup.jsString("a\"b"))
        // </script> inside a <script> block would terminate the element;
        // the escape must keep the literal intact.
        assertEquals("\"\\u003C/script>\"", Markup.jsString("</script>"))
    }
}

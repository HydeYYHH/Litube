package com.hhst.youtubelite.extractor

import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class PoIntegrityTest {
    @Test fun twoFieldsAreSufficient() {
        assertEquals(PoIntegrity("opaque", 3600, 0, null), parsePoIntegrity("[\"opaque\",3600]"))
    }
    @Test fun optionalFieldsAndNulls() {
        assertEquals(PoIntegrity("opaque", 3600, 60, "fallback"), parsePoIntegrity("[\"opaque\",3600,60,\"fallback\"]"))
        assertEquals(PoIntegrity("opaque", 3600, 0, null), parsePoIntegrity("[\"opaque\",3600,null,null]"))
    }
    @Test fun incompleteOrExpiredReplyFailsExplicitly() {
        for (body in listOf("[]", "[\"opaque\"]", "[\"\",3600]", "[\"opaque\",0]", "{}")) {
            val failure = runCatching { parsePoIntegrity(body) }.exceptionOrNull()
            assertTrue(failure is IOException)
            assertEquals("PO_INVALID_INTEGRITY", failure?.message)
        }
    }
}

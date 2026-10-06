package com.hhst.youtubelite.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PipAutoEnterTest {

    @Test
    fun shouldAutoEnter_requiresApi31AndNotSuppressed() {
        while (PipAutoEnter.isSuppressed()) PipAutoEnter.restore()
        assertFalse(PipAutoEnter.shouldAutoEnter(eligible = true, sdk = 30))
        assertTrue(PipAutoEnter.shouldAutoEnter(eligible = true, sdk = 31))
        assertFalse(PipAutoEnter.shouldAutoEnter(eligible = false, sdk = 34))
        val handle = PipAutoEnter.suppress()
        assertFalse(PipAutoEnter.shouldAutoEnter(eligible = true, sdk = 34))
        handle.restore()
        assertTrue(PipAutoEnter.shouldAutoEnter(eligible = true, sdk = 34))
    }

    @Test
    fun suppress_keepsEligibilitySoRestoreCanReenable() {
        while (PipAutoEnter.isSuppressed()) PipAutoEnter.restore()
        assertTrue(PipAutoEnter.shouldAutoEnter(eligible = true, sdk = 33))
        val first = PipAutoEnter.suppress()
        val nested = PipAutoEnter.suppress()
        assertFalse(PipAutoEnter.shouldAutoEnter(eligible = true, sdk = 33))
        first.restore()
        assertTrue(PipAutoEnter.isSuppressed())
        nested.restore()
        assertTrue(PipAutoEnter.shouldAutoEnter(eligible = true, sdk = 33))
    }
}

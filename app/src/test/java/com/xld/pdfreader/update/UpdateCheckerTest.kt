package com.xld.pdfreader.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateCheckerTest {

    @Test
    fun `normalizeVersion strips v prefix and pre-release suffix`() {
        assertEquals("1.2.3", UpdateChecker.normalizeVersion("v1.2.3-beta"))
        assertEquals("1.2.3", UpdateChecker.normalizeVersion("V1.2.3"))
        assertEquals("1.0", UpdateChecker.normalizeVersion("1.0"))
        assertEquals("", UpdateChecker.normalizeVersion(""))
        assertEquals("", UpdateChecker.normalizeVersion("release"))
    }

    @Test
    fun `isNewer detects newer versions numerically`() {
        assertTrue(UpdateChecker.isNewer("1.1", "1.0"))
        assertTrue(UpdateChecker.isNewer("1.10", "1.9"))
        assertTrue(UpdateChecker.isNewer("2.0", "1.9"))
        assertTrue(UpdateChecker.isNewer("1.0.1", "1.0"))
    }

    @Test
    fun `isNewer returns false for same older or unparsable versions`() {
        assertFalse(UpdateChecker.isNewer("1.0", "1.0"))
        assertFalse(UpdateChecker.isNewer("1.0", "1.1"))
        assertFalse(UpdateChecker.isNewer("1.0", "2.0"))
        assertFalse(UpdateChecker.isNewer("", "1.0"))
        assertFalse(UpdateChecker.isNewer("abc", "1.0"))
        assertFalse(UpdateChecker.isNewer("1.1", ""))
    }
}

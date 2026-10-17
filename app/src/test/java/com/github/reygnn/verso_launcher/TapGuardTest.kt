package com.github.reygnn.verso_launcher

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TapGuardTest {

    @Test fun `same generation passes - list unchanged since the finger went down`() {
        assertTrue(TapGuard.stillValid(downGeneration = 7, shownGeneration = 7))
    }

    @Test fun `bumped generation vetoes - list replaced mid-gesture`() {
        assertFalse(TapGuard.stillValid(downGeneration = 7, shownGeneration = 8))
        assertFalse(TapGuard.stillValid(downGeneration = 7, shownGeneration = 99))
    }

    @Test fun `an out-of-order pair also vetoes - the guard is strict equality, not a comparison`() {
        // Does not arise in practice; rules out a `>=` slip (the test above rules out `<=`).
        assertFalse(TapGuard.stillValid(downGeneration = 8, shownGeneration = 7))
    }

    @Test fun `NO_TOUCH passes for any current generation - accessibility or hardware click`() {
        assertTrue(TapGuard.stillValid(downGeneration = TapGuard.NO_TOUCH, shownGeneration = 0))
        assertTrue(TapGuard.stillValid(downGeneration = TapGuard.NO_TOUCH, shownGeneration = 42))
    }
}

package com.theemrock.smooth.sensus.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SmoothNavigationStateTest {
    @Test
    fun startsAtSmoothHome() {
        assertEquals(SmoothDestination.HOME, SmoothNavigationState().destination)
    }

    @Test
    fun opensLearnScenarioAndBackReturnsHome() {
        val navigation = SmoothNavigationState()

        navigation.openLearnScenario()
        assertEquals(SmoothDestination.LEARN_SCENARIO, navigation.destination)

        assertTrue(navigation.backToHome())
        assertEquals(SmoothDestination.HOME, navigation.destination)
        assertFalse(navigation.backToHome())
    }

    @Test
    fun opensChatAndBackReturnsHome() {
        val navigation = SmoothNavigationState()

        navigation.openChat()
        assertEquals(SmoothDestination.CHAT, navigation.destination)

        assertTrue(navigation.backToHome())
        assertEquals(SmoothDestination.HOME, navigation.destination)
    }
}

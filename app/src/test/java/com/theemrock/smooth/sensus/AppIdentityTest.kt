package com.theemrock.smooth.sensus

import org.junit.Assert.assertEquals
import org.junit.Test

class AppIdentityTest {
    @Test
    fun usesSmoothSensusApplicationId() {
        assertEquals("com.theemrock.smooth.sensus", BuildConfig.APPLICATION_ID)
    }
}

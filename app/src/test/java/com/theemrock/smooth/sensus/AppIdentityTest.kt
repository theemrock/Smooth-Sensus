package com.theemrock.smooth.sensus

import org.junit.Assert.assertEquals
import org.junit.Test

class AppIdentityTest {
    @Test
    fun usesSmoothDialogueApplicationId() {
        assertEquals("com.theemrock.smooth.sensus", BuildConfig.APPLICATION_ID)
    }
}

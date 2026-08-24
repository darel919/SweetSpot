package com.darelisme.sweetspot

import org.junit.Assert.assertEquals
import org.junit.Test

class MeasurementContextTest {
    @Test
    fun parsesAndLabelsAValidRoutedTake() {
        val context = MeasurementContext("right", 2, 5, "left", 1, 3, "measurement")

        assertEquals(true, context.isValid())
        assertEquals("Position 3 of 5 · left channel · take 2 of 3", context.label())
    }

    @Test
    fun rejectsOutOfRangeContext() {
        val context = MeasurementContext("center", 5, 5, "right", 0, 3, "measurement")

        assertEquals(false, context.isValid())
    }
}

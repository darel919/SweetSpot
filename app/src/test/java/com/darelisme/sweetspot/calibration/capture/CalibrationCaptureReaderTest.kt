package com.darelisme.sweetspot.calibration.capture

import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertArrayEquals
import org.junit.Test

class CalibrationCaptureReaderTest {
    @Test
    fun readsLittleEndianFloat32AcrossInputBoundaries() {
        val bytes = ByteBuffer.allocate(12)
            .order(ByteOrder.LITTLE_ENDIAN)
            .putFloat(0.25f)
            .putFloat(-0.5f)
            .putFloat(1.0f)
            .array()

        assertArrayEquals(
            floatArrayOf(0.25f, -0.5f, 1.0f),
            CalibrationCaptureReader(null).readFloat32(ByteArrayInputStream(bytes), bytes.size.toLong()),
            0.0f,
        )
    }
}

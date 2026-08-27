package com.darelisme.sweetspot.calibration

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class CalibrationCaptureWireTest {
    @Test
    fun roundTripPreservesMetadataAndPcm() {
        val pcm = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
            .putFloat(-0.25f)
            .putFloat(0.75f)
            .array()

        val frame = CalibrationCaptureWire.encode("{\"captureId\":\"center-left-0\"}", pcm)
        val decoded = CalibrationCaptureWire.decode(frame)

        assertEquals("{\"captureId\":\"center-left-0\"}", decoded.metadataJson)
        assertArrayEquals(pcm, decoded.pcm)
    }

    @Test
    fun rejectsBadMagicVersionMetadataAndPcmFraming() {
        val valid = CalibrationCaptureWire.encode("{}", ByteArray(4))

        assertThrows(IllegalArgumentException::class.java) {
            CalibrationCaptureWire.decode(valid.copyOf().also { it[0] = 'X'.code.toByte() })
        }
        assertThrows(IllegalArgumentException::class.java) {
            CalibrationCaptureWire.decode(valid.copyOf().also { it[7] = 2 })
        }
        assertThrows(IllegalArgumentException::class.java) {
            CalibrationCaptureWire.decode(valid.copyOfRange(0, 11))
        }
        assertThrows(IllegalArgumentException::class.java) {
            CalibrationCaptureWire.encode("{}", ByteArray(3))
        }
    }
}

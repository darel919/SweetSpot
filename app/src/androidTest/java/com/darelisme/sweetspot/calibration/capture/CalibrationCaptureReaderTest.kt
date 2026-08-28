package com.darelisme.sweetspot.calibration.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class CalibrationCaptureReaderTest {
    @Test
    fun parsesCanonicalCaptureMetadataWithoutCoercion() {
        val metadata = CalibrationCaptureReader(null).parse(validMetadata(), 16)

        assertEquals(4L, metadata.sampleCount)
        assertEquals(16L, metadata.byteCount)
        assertEquals("fixture-mic", metadata.microphoneProfileId)
    }

    @Test
    fun rejectsStringEncodedNumericMetadata() {
        assertThrows(IllegalArgumentException::class.java) {
            CalibrationCaptureReader(null).parse(validMetadata().replace("\"sampleCount\":4", "\"sampleCount\":\"4\""), 16)
        }
    }

    @Test
    fun rejectsStructuredCaptureSettingValues() {
        assertThrows(IllegalArgumentException::class.java) {
            CalibrationCaptureReader(null).parse(validMetadata().replace("\"settings\":{}", "\"settings\":{\"echoCancellation\":{}}"), 16)
        }
    }

    private fun validMetadata(): String = """
        {
          "jobId":"job-1",
          "captureId":"capture-1",
          "positionId":"center",
          "attemptIndex":0,
          "channel":"both",
          "sampleRate":48000,
          "channelCount":1,
          "sampleCount":4,
          "byteCount":16,
          "settings":{},
          "userAgent":"test-browser",
          "microphoneProfileId":"fixture-mic",
          "microphoneProfileRevision":"v1",
          "microphoneProfile":{
            "id":"fixture-mic",
            "revision":"v1",
            "capturePathStatus":"validated",
            "frequenciesHz":[20,20000],
            "responseDb":[0,0],
            "normalizeAtHz":1000,
            "trustMinHz":30,
            "trustFullMaxHz":8000,
            "trustTaperToHz":12000
          },
          "capturedAtMs":1,
          "contentSha256":"0000000000000000000000000000000000000000000000000000000000000000"
        }
    """.trimIndent()
}

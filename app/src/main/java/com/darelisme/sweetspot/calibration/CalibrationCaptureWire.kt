package com.darelisme.sweetspot.calibration

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets

data class CalibrationCaptureFrame(
    val metadataJson: String,
    val pcm: ByteArray,
) {
    init {
        require(metadataJson.isNotBlank())
        require(pcm.isNotEmpty() && pcm.size % 4 == 0)
    }
}

object CalibrationCaptureWire {
    const val VERSION = 1
    const val MAX_METADATA_BYTES = 64 * 1024
    const val MAX_FRAME_BYTES = 8 * 1024 * 1024

    private val MAGIC = byteArrayOf('S'.code.toByte(), 'S'.code.toByte(), 'C'.code.toByte(), 'P'.code.toByte())
    private const val HEADER_BYTES = 12

    fun encode(metadataJson: String, pcm: ByteArray): ByteArray {
        val metadata = metadataJson.toByteArray(StandardCharsets.UTF_8)
        require(metadata.isNotEmpty() && metadata.size <= MAX_METADATA_BYTES)
        require(pcm.isNotEmpty() && pcm.size % 4 == 0)
        require(HEADER_BYTES + metadata.size + pcm.size <= MAX_FRAME_BYTES)
        return ByteBuffer.allocate(HEADER_BYTES + metadata.size + pcm.size)
            .order(ByteOrder.BIG_ENDIAN)
            .put(MAGIC)
            .putInt(VERSION)
            .putInt(metadata.size)
            .put(metadata)
            .put(pcm)
            .array()
    }

    fun decode(frame: ByteArray): CalibrationCaptureFrame {
        require(frame.size in HEADER_BYTES + 1..MAX_FRAME_BYTES)
        val input = ByteBuffer.wrap(frame).order(ByteOrder.BIG_ENDIAN)
        val magic = ByteArray(MAGIC.size)
        input.get(magic)
        require(magic.contentEquals(MAGIC))
        require(input.int == VERSION)
        val metadataLength = input.int
        require(metadataLength in 1..MAX_METADATA_BYTES)
        val pcmLength = frame.size - HEADER_BYTES - metadataLength
        require(pcmLength > 0 && pcmLength % 4 == 0)
        val metadataBytes = ByteArray(metadataLength)
        input.get(metadataBytes)
        val pcm = ByteArray(pcmLength)
        input.get(pcm)
        return CalibrationCaptureFrame(String(metadataBytes, StandardCharsets.UTF_8), pcm)
    }
}

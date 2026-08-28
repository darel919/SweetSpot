package com.darelisme.sweetspot

import org.json.JSONObject
import org.json.JSONArray

internal data class CalibrationPackageSourceDevice(
    val id: String,
    val name: String,
    val appVersion: String,
    val buildId: String? = null,
)

internal data class CalibrationPackage(
    val exportedAt: Double,
    val analysisRevision: String = CalibrationPackageCodec.ANALYSIS_REVISION,
    val sourceDevice: CalibrationPackageSourceDevice,
    val active: Boolean,
    val frequenciesHz: DoubleArray,
    val bandsDb: FloatArray,
    val leftBandsDb: FloatArray? = null,
    val rightBandsDb: FloatArray? = null,
    val effectiveBandsDb: FloatArray? = null,
    val effectiveLeftBandsDb: FloatArray? = null,
    val effectiveRightBandsDb: FloatArray? = null,
)

internal sealed interface CalibrationPackageParseResult {
    data class Accepted(val value: CalibrationPackage) : CalibrationPackageParseResult
    data class Rejected(val error: String) : CalibrationPackageParseResult
}

internal object CalibrationPackageCodec {
    const val FORMAT = "sweetspot.calibration"
    const val VERSION = 1
    const val ANALYSIS_REVISION = "response-direct-arrival-v3"

    private const val MAX_GAIN_DB = 12f
    private const val MAX_SOURCE_ID_LENGTH = 256
    private const val MAX_SOURCE_NAME_LENGTH = 256
    private const val MAX_SOURCE_APP_VERSION_LENGTH = 64
    private const val MAX_SOURCE_BUILD_ID_LENGTH = 128

    fun parse(
        payload: JSONObject,
        expectedFrequenciesHz: IntArray,
        independentRoutingVerified: Boolean,
        requireActive: Boolean = false,
    ): CalibrationPackageParseResult {
        val format = payload.opt("format") as? String
            ?: return rejected("format must be $FORMAT")
        if (format != FORMAT) return rejected("Unsupported calibration package format")

        val version = payload.opt("version") as? Number
        if (version == null || version.toDouble() != VERSION.toDouble()) {
            return rejected("Unsupported calibration package version")
        }

        val exportedAt = finiteNumber(payload.opt("exportedAt"))
        if (exportedAt == null || exportedAt <= 0.0) return rejected("exportedAt must be a positive finite number")

        val analysisRevision = payload.opt("analysisRevision") as? String
        if (analysisRevision != ANALYSIS_REVISION) return rejected("Unsupported calibration analysis revision")

        val sourceDevice = parseSourceDevice(payload.opt("sourceDevice"))
            ?: return rejected("sourceDevice metadata is invalid")

        val active = payload.opt("active") as? Boolean
            ?: return rejected("active must be a boolean")
        if (requireActive && !active) return rejected("Inactive calibration packages cannot be imported")

        val frequenciesHz = parseFrequencyArray(payload.opt("frequenciesHz"))
            ?: return rejected("frequenciesHz must contain 64 increasing finite positive numbers")
        if (expectedFrequenciesHz.size != DynamicsProcessingEq.INTERNAL_BANDS
            || frequenciesHz.indices.any { frequenciesHz[it] != expectedFrequenciesHz[it].toDouble() }
        ) {
            return rejected("Calibration package frequency grid does not match this TV")
        }

        val bandsDb = parseGainArray(payload.opt("bandsDb"), "bandsDb")
            ?: return rejected("bandsDb must contain 64 finite gains within ±$MAX_GAIN_DB dB")

        val hasLeft = payload.has("leftBandsDb")
        val hasRight = payload.has("rightBandsDb")
        if (hasLeft != hasRight) return rejected("leftBandsDb and rightBandsDb must be paired")
        val leftBandsDb = if (hasLeft) {
            parseGainArray(payload.opt("leftBandsDb"), "leftBandsDb")
                ?: return rejected("leftBandsDb must contain 64 finite gains within ±$MAX_GAIN_DB dB")
        } else {
            null
        }
        val rightBandsDb = if (hasRight) {
            parseGainArray(payload.opt("rightBandsDb"), "rightBandsDb")
                ?: return rejected("rightBandsDb must contain 64 finite gains within ±$MAX_GAIN_DB dB")
        } else {
            null
        }

        val effectiveBandsDb = if (payload.has("effectiveBandsDb")) {
            parseGainArray(payload.opt("effectiveBandsDb"), "effectiveBandsDb")
                ?: return rejected("effectiveBandsDb must contain 64 finite gains within ±$MAX_GAIN_DB dB")
        } else {
            null
        }

        val hasEffectiveLeft = payload.has("effectiveLeftBandsDb")
        val hasEffectiveRight = payload.has("effectiveRightBandsDb")
        if (hasEffectiveLeft != hasEffectiveRight) {
            return rejected("effectiveLeftBandsDb and effectiveRightBandsDb must be paired")
        }
        val effectiveLeftBandsDb = if (hasEffectiveLeft) {
            parseGainArray(payload.opt("effectiveLeftBandsDb"), "effectiveLeftBandsDb")
                ?: return rejected("effectiveLeftBandsDb must contain 64 finite gains within ±$MAX_GAIN_DB dB")
        } else {
            null
        }
        val effectiveRightBandsDb = if (hasEffectiveRight) {
            parseGainArray(payload.opt("effectiveRightBandsDb"), "effectiveRightBandsDb")
                ?: return rejected("effectiveRightBandsDb must contain 64 finite gains within ±$MAX_GAIN_DB dB")
        } else {
            null
        }

        if ((leftBandsDb != null || rightBandsDb != null || effectiveLeftBandsDb != null || effectiveRightBandsDb != null)
            && !independentRoutingVerified
        ) {
            return rejected("Independent channel calibration is not verified on this TV")
        }

        val value = CalibrationPackage(
            exportedAt = exportedAt,
            analysisRevision = analysisRevision,
            sourceDevice = sourceDevice,
            active = active,
            frequenciesHz = frequenciesHz,
            bandsDb = bandsDb,
            leftBandsDb = leftBandsDb,
            rightBandsDb = rightBandsDb,
            effectiveBandsDb = effectiveBandsDb,
            effectiveLeftBandsDb = effectiveLeftBandsDb,
            effectiveRightBandsDb = effectiveRightBandsDb,
        )
        if (requireActive) {
            validateForImport(value, expectedFrequenciesHz, independentRoutingVerified)?.let { return rejected(it) }
        }
        return CalibrationPackageParseResult.Accepted(value)
    }

    fun parseForImport(
        payload: JSONObject,
        expectedFrequenciesHz: IntArray,
        independentRoutingVerified: Boolean,
    ): CalibrationPackageParseResult =
        parse(payload, expectedFrequenciesHz, independentRoutingVerified, requireActive = true)

    internal fun validateForImport(
        value: CalibrationPackage,
        expectedFrequenciesHz: IntArray,
        independentRoutingVerified: Boolean,
    ): String? {
        if (!value.active) return "Inactive calibration packages cannot be imported"
        if (value.analysisRevision != ANALYSIS_REVISION) return "Unsupported calibration analysis revision"
        if (expectedFrequenciesHz.size != DynamicsProcessingEq.INTERNAL_BANDS
            || !value.frequenciesHz.contentEquals(expectedFrequenciesHz.map(Int::toDouble).toDoubleArray())
        ) {
            return "Calibration package frequency grid does not match this TV"
        }
        if (!value.frequenciesHz.isStrictlyIncreasing()) return "frequenciesHz must be increasing"
        if (!value.bandsDb.isValidGainArray()) return "bandsDb contains an invalid gain"
        if ((value.leftBandsDb == null) != (value.rightBandsDb == null)) {
            return "leftBandsDb and rightBandsDb must be paired"
        }
        if (value.leftBandsDb != null && !value.leftBandsDb.isValidGainArray()) return "leftBandsDb contains an invalid gain"
        if (value.rightBandsDb != null && !value.rightBandsDb.isValidGainArray()) return "rightBandsDb contains an invalid gain"
        if ((value.effectiveLeftBandsDb == null) != (value.effectiveRightBandsDb == null)) {
            return "effectiveLeftBandsDb and effectiveRightBandsDb must be paired"
        }
        if (value.effectiveBandsDb != null && !value.effectiveBandsDb.isValidGainArray()) return "effectiveBandsDb contains an invalid gain"
        if (value.effectiveLeftBandsDb != null && !value.effectiveLeftBandsDb.isValidGainArray()) return "effectiveLeftBandsDb contains an invalid gain"
        if (value.effectiveRightBandsDb != null && !value.effectiveRightBandsDb.isValidGainArray()) return "effectiveRightBandsDb contains an invalid gain"
        if ((value.leftBandsDb != null || value.rightBandsDb != null
                || value.effectiveLeftBandsDb != null || value.effectiveRightBandsDb != null)
            && !independentRoutingVerified
        ) {
            return "Independent channel calibration is not verified on this TV"
        }
        return null
    }

    fun serialize(value: CalibrationPackage): JSONObject {
        require(value.frequenciesHz.isStrictlyIncreasing())
        require(value.bandsDb.isValidGainArray())
        require((value.leftBandsDb == null) == (value.rightBandsDb == null))
        require(value.leftBandsDb == null || value.leftBandsDb.isValidGainArray())
        require(value.rightBandsDb == null || value.rightBandsDb.isValidGainArray())
        require(value.effectiveBandsDb == null || value.effectiveBandsDb.isValidGainArray())
        require((value.effectiveLeftBandsDb == null) == (value.effectiveRightBandsDb == null))
        require(value.effectiveLeftBandsDb == null || value.effectiveLeftBandsDb.isValidGainArray())
        require(value.effectiveRightBandsDb == null || value.effectiveRightBandsDb.isValidGainArray())

        return JSONObject().apply {
            put("format", FORMAT)
            put("version", VERSION)
            put("exportedAt", value.exportedAt)
            put("analysisRevision", value.analysisRevision)
            put("sourceDevice", JSONObject().apply {
                put("id", value.sourceDevice.id)
                put("name", value.sourceDevice.name)
                put("appVersion", value.sourceDevice.appVersion)
                value.sourceDevice.buildId?.let { put("buildId", it) }
            })
            put("active", value.active)
            put("frequenciesHz", JSONArray().apply { value.frequenciesHz.forEach { put(it) } })
            put("bandsDb", value.bandsDb.toJsonArray())
            value.leftBandsDb?.let { put("leftBandsDb", it.toJsonArray()) }
            value.rightBandsDb?.let { put("rightBandsDb", it.toJsonArray()) }
            value.effectiveBandsDb?.let { put("effectiveBandsDb", it.toJsonArray()) }
            value.effectiveLeftBandsDb?.let { put("effectiveLeftBandsDb", it.toJsonArray()) }
            value.effectiveRightBandsDb?.let { put("effectiveRightBandsDb", it.toJsonArray()) }
        }
    }

    private fun parseSourceDevice(value: Any?): CalibrationPackageSourceDevice? {
        val source = value as? JSONObject ?: return null
        val id = source.opt("id") as? String ?: return null
        val name = source.opt("name") as? String ?: return null
        val appVersion = source.opt("appVersion") as? String ?: return null
        val buildId = source.opt("buildId") as? String
        if (id.isBlank() || id.length > MAX_SOURCE_ID_LENGTH) return null
        if (name.isBlank() || name.length > MAX_SOURCE_NAME_LENGTH) return null
        if (appVersion.isBlank() || appVersion.length > MAX_SOURCE_APP_VERSION_LENGTH) return null
        if (buildId != null && (buildId.isBlank() || buildId.length > MAX_SOURCE_BUILD_ID_LENGTH)) return null
        return CalibrationPackageSourceDevice(id, name, appVersion, buildId)
    }

    private fun parseFrequencyArray(value: Any?): DoubleArray? {
        val array = value as? JSONArray ?: return null
        if (array.length() != DynamicsProcessingEq.INTERNAL_BANDS) return null
        val frequencies = DoubleArray(array.length())
        for (index in frequencies.indices) {
            val frequency = finiteNumber(array.opt(index)) ?: return null
            if (frequency <= 0.0 || (index > 0 && frequency <= frequencies[index - 1])) return null
            frequencies[index] = frequency
        }
        return frequencies
    }

    private fun parseGainArray(value: Any?, field: String): FloatArray? {
        if (field.isEmpty()) return null
        val array = value as? JSONArray ?: return null
        if (array.length() != DynamicsProcessingEq.INTERNAL_BANDS) return null
        val gains = FloatArray(array.length())
        for (index in gains.indices) {
            val gain = finiteNumber(array.opt(index))?.toFloat() ?: return null
            if (!gain.isValidGain()) return null
            gains[index] = gain
        }
        return gains
    }

    private fun finiteNumber(value: Any?): Double? = (value as? Number)?.toDouble()?.takeIf { it.isFinite() }

    private fun Float.isValidGain(): Boolean = isFinite() && this >= -MAX_GAIN_DB && this <= MAX_GAIN_DB

    private fun FloatArray.isValidGainArray(): Boolean =
        size == DynamicsProcessingEq.INTERNAL_BANDS && all { it.isValidGain() }

    private fun DoubleArray.isStrictlyIncreasing(): Boolean =
        size == DynamicsProcessingEq.INTERNAL_BANDS
            && all { it.isFinite() && it > 0.0 }
            && indices.drop(1).all { this[it] > this[it - 1] }

    private fun FloatArray.toJsonArray(): JSONArray = JSONArray().apply { forEach { put(it.toDouble()) } }

    private fun rejected(message: String): CalibrationPackageParseResult =
        CalibrationPackageParseResult.Rejected(message)
}

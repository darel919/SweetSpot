package com.darelisme.sweetspot

import android.media.audiofx.DynamicsProcessing
import android.util.Log
import kotlin.math.max
import kotlin.math.pow

/** Diagnostic-only capability probe for DynamicsProcessing on global session 0. */
class DynamicsProcessingProbe {

    companion object {
        private const val TAG = "SweetSpotDP"
        private const val SESSION_ID = 0
        private const val PRIORITY = 1000
        private const val F_MIN = 20f
        private const val F_MAX = 20000f

        /** Diagnostic curves may cut deeply, but positive probe gain is bounded. */
        const val MIN_PROBE_GAIN_DB = -18f
        const val MAX_PROBE_GAIN_DB = 6f

        /** Candidate band counts used by diagnostics. */
        private val CANDIDATE_BANDS = intArrayOf(10, 20, 32, 64)
    }

    data class ProbeResult(
        val requested: Int,
        val constructed: Boolean,
        val hasControl: Boolean,
        val enabled: Boolean,
        val actualBands: Int,
        val exception: String? = null
    )

    data class CurveSummary(
        val bandsTotal: Int,
        val bandsCut: Int,
        val bandsFlat: Int
    )

    /** Runs the candidate ladder, logs a summary, and returns the results. */
    fun run(): List<ProbeResult> {
        Log.i(TAG, "=== DynamicsProcessing Probe ===")
        Log.i(TAG, "Target session: $SESSION_ID (global output mix)")
        val results = CANDIDATE_BANDS.map(::testBandCount)
        logSummary(results)
        return results
    }

    /** Probes the standard candidate ladder capped at [cap]; used by transport-driven probes. */
    fun runFor(cap: Int): List<ProbeResult> =
        CANDIDATE_BANDS.filter { it <= cap }.map { testBandCount(it) }

    /**
     * Probes a single band count. Always releases the effect in finally.
     * Never throws; failures are captured in the returned [ProbeResult]. The
     * effect targets global output session 0, which may produce an AOSP
     * deprecation warning.
     */
    fun testBandCount(n: Int): ProbeResult {
        Log.i(TAG, "--- Testing requested bands: $n ---")
        var dp: DynamicsProcessing? = null
        return try {
            val config = buildConfig(n)
            dp = DynamicsProcessing(PRIORITY, SESSION_ID, config)

            val constructed = true
            val hasControl = try { dp.hasControl() } catch (_: Throwable) { false }
            dp.setEnabled(true)
            val enabled = try { dp.enabled } catch (_: Throwable) { false }
            val actualBands = readBackBandCount(dp, n)

            val pass = constructed && hasControl && enabled && actualBands == n
            Log.i(TAG, "Requested bands: $n")
            Log.i(TAG, "Constructed: $constructed")
            Log.i(TAG, "Session: $SESSION_ID")
            Log.i(TAG, "Has control: $hasControl")
            Log.i(TAG, "Enabled: $enabled")
            Log.i(TAG, "Actual bands: $actualBands")
            Log.i(TAG, "Result: ${if (pass) "PASS" else "FAIL"}")

            ProbeResult(
                requested = n,
                constructed = constructed,
                hasControl = hasControl,
                enabled = enabled,
                actualBands = actualBands
            )
        } catch (e: Throwable) {
            Log.e(TAG, "Requested bands: $n")
            Log.e(TAG, "Constructed: false")
            Log.e(TAG, "Exception: ${e.javaClass.name}: ${e.message}")
            Log.e(TAG, "Stack: ${Log.getStackTraceString(e)}")
            Log.e(TAG, "Result: FAIL")
            ProbeResult(
                requested = n,
                constructed = false,
                hasControl = false,
                enabled = false,
                actualBands = -1,
                exception = "${e.javaClass.name}: ${e.message}"
            )
        } finally {
            try {
                dp?.release()
                Log.i(TAG, "Released DynamicsProcessing instance for $n bands")
            } catch (e: Throwable) {
                Log.w(TAG, "Release failed for $n bands: ${e.message}")
            }
        }
    }

    /**
     * Builds, constructs, and enables a [DynamicsProcessing] instance for [n]
     * bands on the global output mix (session 0), WITHOUT releasing it.
     *
     * Used by the persistent-instance variant so memory/CPU/reliability can be
     * measured with a long-lived enabled effect. The caller owns the instance
     * and MUST release it (see [releaseInstance]) when finished.
     */
    fun createEnabled(n: Int, channels: Int = 1): DynamicsProcessing {
        val config = buildConfig(n, channels)
        val dp = DynamicsProcessing(PRIORITY, SESSION_ID, config)
        dp.setEnabled(true)
        if (dp.channelCount != channels) {
            dp.release()
            throw IllegalStateException("DynamicsProcessing created ${dp.channelCount} channels, requested $channels")
        }
        return dp
    }

    /** Releases a previously created instance; already-invalid instances are ignored. */
    fun releaseInstance(dp: DynamicsProcessing?) {
        try {
            dp?.release()
        } catch (_: Throwable) {}
    }

    /** Reads back the live gain curve for verification. */
    fun readCurveSummary(dp: DynamicsProcessing, n: Int, channel: Int = 0): CurveSummary {
        var cut = 0
        var flat = 0
        for (i in 0 until n) {
            val g = try { dp.getPreEqBandByChannelIndex(channel, i).gain } catch (_: Throwable) { 0f }
            if (kotlin.math.abs(g) > 1f) cut++ else flat++
        }
        return CurveSummary(n, cut, flat)
    }

    /** Reads live band state first, then falls back to the resolved configuration. */
    private fun readBackBandCount(dp: DynamicsProcessing, requested: Int): Int {
        try {
            return dp.getPreEqByChannelIndex(0).bandCount
        } catch (_: Throwable) {
            try {
                return dp.getConfig().preEqBandCount
            } catch (_: Throwable) {
                Log.w(TAG, "Could not read back band count for $requested bands")
                return -1
            }
        }
    }

    /** Builds a pre-EQ-only configuration with flat initial gains. */
    fun buildConfig(n: Int, channels: Int = 1): DynamicsProcessing.Config {
        val variant = DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION
        val channelCount = channels
        val builder = DynamicsProcessing.Config.Builder(
            variant,
            channelCount,
            true,
            n,
            false,
            0,
            false,
            0,
            false
        )

        val eq = DynamicsProcessing.Eq(true, true, n)
        for (i in 0 until n) {
            val freq = F_MIN * (F_MAX / F_MIN).pow((i + 1).toFloat() / n)
            eq.setBand(i, DynamicsProcessing.EqBand(true, freq, 0f))
        }
        builder.setPreEqAllChannelsTo(eq)
        return builder.build()
    }

    private fun logSummary(results: List<ProbeResult>) {
        Log.i(TAG, "=== DynamicsProcessing Summary ===")
        var highest = -1
        for (r in results) {
            val status = if (r.constructed && r.hasControl && r.enabled && r.actualBands == r.requested) "PASS" else "FAIL"
            Log.i(TAG, "${r.requested} bands: $status")
            if (r.constructed && r.hasControl && r.enabled && r.actualBands == r.requested) {
                highest = max(highest, r.requested)
            }
        }
        Log.i(TAG, "Highest successful count: $highest")
        Log.i(TAG, "Recommended SweetSpot calibration band count: $highest")
    }
}

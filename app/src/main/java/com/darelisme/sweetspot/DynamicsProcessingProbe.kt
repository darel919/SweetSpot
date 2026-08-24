package com.darelisme.sweetspot

import android.media.audiofx.DynamicsProcessing
import android.os.Build
import android.util.Log
import kotlin.math.max
import kotlin.math.pow

/**
 * Diagnostic-only probe for Android's DynamicsProcessing effect on the global
 * audio session (session 0).
 *
 * This is a TEMPORARY, development-only capability probe. It must NOT replace
 * the production [EqualizerEngine] (the confirmed NXP 5-band Equalizer). It:
 *   - does not modify saved user profiles or calibration data,
 *   - does not alter existing EQ presets,
 *   - releases every test instance after probing,
 *   - leaves the app in the same functional state afterwards.
 *
 * Trigger : adb shell am broadcast -a com.darelisme.sweetspot.PROBE_DYNAMICS
 * Inspect : adb logcat -d -s SweetSpotDP:I *:S
 *
 * The goal is to answer two questions:
 *   1. Does DynamicsProcessing work on global session 0?
 *   2. What is the highest reliable EQ band count on this TCL?
 */
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

        /** Candidate band counts required by the milestone. */
        private val CANDIDATE_BANDS = intArrayOf(10, 20, 32, 64)
        /** Additional candidates are enabled only after the 64-band check passes. */
        private val EXTRA_BANDS = intArrayOf()
        /** The calibration-resolution cap. No higher ladder is currently probed. */
        private val CEILING_BANDS = intArrayOf()
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

    /**
     * Runs the full probe sequence, logs a summary, and returns the results.
     * The 64-band result gates the extra candidates, the 128-band result gates
     * the ceiling ladder, and capacity is treated as monotonic on that ladder.
     */
    fun run(): List<ProbeResult> {
        Log.i(TAG, "=== DynamicsProcessing Probe ===")
        Log.i(TAG, "Target session: $SESSION_ID (global output mix)")
        val results = ArrayList<ProbeResult>()

        for (n in CANDIDATE_BANDS) {
            results.add(testBandCount(n))
        }
        val sixtyFour = results.find { it.requested == 64 }
        if (sixtyFour != null && sixtyFour.constructed && sixtyFour.actualBands == sixtyFour.requested) {
            Log.i(TAG, "64 bands passed — probing extended counts (96, 128)")
            for (n in EXTRA_BANDS) {
                results.add(testBandCount(n))
            }
        } else {
            Log.i(TAG, "64 bands did not pass cleanly — skipping extended counts")
        }

        val oneTwentyEight = results.find { it.requested == 128 }
        if (oneTwentyEight != null && oneTwentyEight.constructed && oneTwentyEight.actualBands == oneTwentyEight.requested) {
            Log.i(TAG, "128 bands passed — probing ceiling ladder (192, 256, 384, 512, 768, 1024)")
            for (n in CEILING_BANDS) {
                val r = testBandCount(n)
                results.add(r)
                if (!(r.constructed && r.actualBands == r.requested)) {
                    Log.i(TAG, "Ceiling reached: $n bands failed — stopping ladder")
                    break
                }
            }
        } else {
            Log.i(TAG, "128 bands did not pass cleanly — skipping ceiling ladder")
        }

        logSummary(results)
        return results
    }

    /** Probes the standard candidate ladder capped at [cap]; used by mailbox-driven probes. */
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
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
                throw UnsupportedOperationException(
                    "DynamicsProcessing requires API 28+ (device is ${Build.VERSION.SDK_INT})"
                )
            }

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
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            throw UnsupportedOperationException(
                "DynamicsProcessing requires API 28+ (device is ${Build.VERSION.SDK_INT})"
            )
        }
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

    /**
     * Applies a frequency-dependent gain curve to a live, enabled
     * [DynamicsProcessing] instance (pre-EQ stage, all channels).
     * [gainForFreq] maps a band's center frequency (Hz) to a gain in dB.
     * Commits per-band via [DynamicsProcessing.setPreEqBandByChannelIndex]. A
     * missing channel ends processing for that channel, which supports mono
     * instances.
     */
    fun applyCurve(dp: DynamicsProcessing, n: Int, gainForFreq: (Float) -> Float): Boolean {
        if (n <= 0) return false
        val common = FloatArray(n) { index ->
            val freq = F_MIN * (F_MAX / F_MIN).pow((index + 1).toFloat() / n)
            gainForFreq(freq)
        }
        return applyChannelCurves(dp, n, common)
    }

    /**
     * Applies common or independent diagnostic curves and verifies every live
     * band readback. Channel 0 is left and channel 1 is right. Independent
     * curves are rejected on a mono effect instead of being silently folded
     * into a common curve, because that would make a routing experiment lie.
     */
    fun applyChannelCurves(
        dp: DynamicsProcessing,
        n: Int,
        common: FloatArray,
        left: FloatArray? = null,
        right: FloatArray? = null,
    ): Boolean {
        if (dp.channelCount <= 0 || n <= 0 || common.size != n) return false
        if ((left == null) != (right == null)) return false
        if (left != null && (left.size != n || right?.size != n || dp.channelCount < 2)) return false

        for (ch in 0 until dp.channelCount) {
            val curve = when {
                ch == 0 && left != null -> left
                ch == 1 && right != null -> right
                else -> common
            }
            for (i in 0 until n) {
                val requested = curve[i]
                if (!requested.isFinite() || requested < MIN_PROBE_GAIN_DB || requested > MAX_PROBE_GAIN_DB) return false
                try {
                    val band = dp.getPreEqBandByChannelIndex(ch, i)
                    band.setGain(requested)
                    dp.setPreEqBandByChannelIndex(ch, i, band)
                    val actual = dp.getPreEqBandByChannelIndex(ch, i).gain
                    if (!actual.isFinite() || kotlin.math.abs(actual - requested) > 0.25f) {
                        Log.w(TAG, "Probe readback mismatch channel=$ch band=$i requested=$requested actual=$actual")
                        return false
                    }
                } catch (error: Throwable) {
                    Log.w(TAG, "Probe band write failed channel=$ch band=$i", error)
                    return false
                }
            }
        }
        return true
    }

    /** Hollow / recessed-mids test curve: cuts 300 Hz–3 kHz by 15 dB. */
    fun applyHollowCurve(dp: DynamicsProcessing, n: Int): Boolean {
        return applyCurve(dp, n) { freq ->
            when {
                freq < 300f -> 0f
                freq < 3000f -> -15f
                else -> 0f
            }
        }
    }

    /** Resets all bands to 0 dB (flat) for A/B comparison. */
    fun applyFlatCurve(dp: DynamicsProcessing, n: Int): Boolean {
        return applyCurve(dp, n) { 0f }
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

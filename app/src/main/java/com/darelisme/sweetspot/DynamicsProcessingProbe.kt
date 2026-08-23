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

        // Primary candidate band counts required by the milestone.
        private val CANDIDATE_BANDS = intArrayOf(10, 20, 32, 64)
        // Only probed if 64 passes cleanly.
        private val EXTRA_BANDS = intArrayOf()
        // 64 is the maximum band count we use — the calibration-resolution
        // cap (derived from iPhone-mic calibration). No higher ladder is probed.
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
        val bandsCut: Int,   // |gain| > 1 dB
        val bandsFlat: Int   // |gain| <= 1 dB
    )

    /** Runs the full probe sequence, logs a summary, and returns the results. */
    fun run(): List<ProbeResult> {
        Log.i(TAG, "=== DynamicsProcessing Probe ===")
        Log.i(TAG, "Target session: $SESSION_ID (global output mix)")
        val results = ArrayList<ProbeResult>()

        for (n in CANDIDATE_BANDS) {
            results.add(testBandCount(n))
        }

        // Only push further if the highest required candidate (64) passed cleanly.
        val sixtyFour = results.find { it.requested == 64 }
        if (sixtyFour != null && sixtyFour.constructed && sixtyFour.actualBands == sixtyFour.requested) {
            Log.i(TAG, "64 bands passed — probing extended counts (96, 128)")
            for (n in EXTRA_BANDS) {
                results.add(testBandCount(n))
            }
        } else {
            Log.i(TAG, "64 bands did not pass cleanly — skipping extended counts")
        }

        // Only probe the ceiling ladder if 128 passed cleanly.
        val oneTwentyEight = results.find { it.requested == 128 }
        if (oneTwentyEight != null && oneTwentyEight.constructed && oneTwentyEight.actualBands == oneTwentyEight.requested) {
            Log.i(TAG, "128 bands passed — probing ceiling ladder (192, 256, 384, 512, 768, 1024)")
            for (n in CEILING_BANDS) {
                val r = testBandCount(n)
                results.add(r)
                // Band-count capacity is monotonic: stop at the first failure
                // to report the highest reliable count without wasting time.
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

    /**
     * Probes a single band count. Always releases the effect in finally.
     * Never throws — failures are captured in the returned [ProbeResult].
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
            // 3-arg constructor: attaches to the global output mix (session 0).
            // AOSP logs a deprecation warning for session 0 but still constructs.
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
        val config = buildConfig(n)
        val dp = DynamicsProcessing(PRIORITY, SESSION_ID, config)
        dp.setEnabled(true)
        return dp
    }

    /** Releases a previously created instance (safe to call once). */
    fun releaseInstance(dp: DynamicsProcessing?) {
        try {
            dp?.release()
        } catch (_: Throwable) {
            // already released or invalid — ignore
        }
    }

    /**
     * Applies a frequency-dependent gain curve to a live, enabled
     * [DynamicsProcessing] instance (pre-EQ stage, all channels).
     * [gainForFreq] maps a band's center frequency (Hz) to a gain in dB.
     * Commits per-band via [DynamicsProcessing.setPreEqBandByChannelIndex].
     */
    fun applyCurve(dp: DynamicsProcessing, n: Int, gainForFreq: (Float) -> Float) {
        for (ch in 0..1) {
            for (i in 0 until n) {
                try {
                    val freq = F_MIN * (F_MAX / F_MIN).pow(i.toFloat() / (n - 1))
                    val band = dp.getPreEqBandByChannelIndex(ch, i)
                    band.setGain(gainForFreq(freq))
                    dp.setPreEqBandByChannelIndex(ch, i, band)
                } catch (_: Throwable) {
                    // Channel not present (e.g. mono instance) — stop this channel.
                    break
                }
            }
        }
    }

    /** Hollow / recessed-mids test curve: cuts 300 Hz–3 kHz by 15 dB. */
    fun applyHollowCurve(dp: DynamicsProcessing, n: Int) {
        applyCurve(dp, n) { freq ->
            when {
                freq < 300f -> 0f
                freq < 3000f -> -15f
                else -> 0f
            }
        }
    }

    /** Resets all bands to 0 dB (flat) for A/B comparison. */
    fun applyFlatCurve(dp: DynamicsProcessing, n: Int) {
        applyCurve(dp, n) { 0f }
    }

    /** Reads back the live gain curve for verification. */
    fun readCurveSummary(dp: DynamicsProcessing, n: Int): CurveSummary {
        var cut = 0
        var flat = 0
        for (i in 0 until n) {
            val g = try { dp.getPreEqBandByChannelIndex(0, i).gain } catch (_: Throwable) { 0f }
            if (kotlin.math.abs(g) > 1f) cut++ else flat++
        }
        return CurveSummary(n, cut, flat)
    }

    private fun readBackBandCount(dp: DynamicsProcessing, requested: Int): Int {
        // Primary: live effect state for channel 0.
        try {
            return dp.getPreEqByChannelIndex(0).bandCount
        } catch (_: Throwable) {
            // Fallback: configured band count from the resolved Config.
            try {
                return dp.getConfig().preEqBandCount
            } catch (_: Throwable) {
                Log.w(TAG, "Could not read back band count for $requested bands")
                return -1
            }
        }
    }

    fun buildConfig(n: Int, channels: Int = 1): DynamicsProcessing.Config {
        val variant = DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION
        val channelCount = channels
        val builder = DynamicsProcessing.Config.Builder(
            variant,
            channelCount,
            true,   // preEqInUse
            n,      // preEqBandCount
            false,  // mbcInUse
            0,      // mbcBandCount
            false,  // postEqInUse
            0,      // postEqBandCount
            false   // limiterInUse
        )

        // Logarithmic frequency spacing: f(i) = fMin * (fMax / fMin)^(i / (N-1))
        // Both Eq booleans are true (inUse + enabled); order is irrelevant here.
        val eq = DynamicsProcessing.Eq(true, true, n)
        for (i in 0 until n) {
            val freq = F_MIN * (F_MAX / F_MIN).pow(i.toFloat() / (n - 1))
            // All gains at 0 dB during the capability probe (engine test, not sound change).
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

package com.darelisme.sweetspot

import android.media.AudioFormat
import android.media.audiofx.AudioEffect
import android.media.audiofx.BassBoost
import android.media.audiofx.DynamicsProcessing
import android.media.audiofx.Equalizer
import android.media.audiofx.LoudnessEnhancer
import android.media.audiofx.Virtualizer
import android.os.Build
import android.os.SystemClock
import android.util.Log

/**
 * Capability diagnostics for the TV's global audio effect chain.
 *
 * Answers, with real API calls on the target device:
 *   1. Which audio effects does the platform expose (queryEffects), including
 *      vendor implementations (name/connectMode/insert vs post-processing)?
 *   2. Can each stock effect type be instantiated on the global output mix
 *      (session 0) and what are its supported parameters?
 *
 * This is the "First Implementation Check" for stereo matrixing: arbitrary L/R
 * mixing needs either a cross-channel-capable effect here or a PCM-intercepting
 * architecture. The results decide which.
 */
class AudioEffectDiagnostics {

    companion object {
        private const val TAG = "SweetSpotFx"
        private const val SESSION_ID = 0
        private const val PRIORITY = 1000

        // AOSP multichannel downmix effect type; present here as a vendor
        // "Insert" implementation. The impl UUID is discovered at runtime.
        private const val DOWNMIX_TYPE_UUID = "381e49cc-a858-4aa2-87f6-e8388e7601b2"

        // Built per-access because EFFECT_TYPE_HAPTIC_GENERATOR exists only
        // on API 31+; referencing it in a static init crashes older devices.
        private fun knownTypes(): Map<String, String> = buildMap {
            put(AudioEffect.EFFECT_TYPE_AEC.toString(), "AEC")
            put(AudioEffect.EFFECT_TYPE_AGC.toString(), "AGC")
            put(AudioEffect.EFFECT_TYPE_BASS_BOOST.toString(), "BassBoost")
            put(AudioEffect.EFFECT_TYPE_DYNAMICS_PROCESSING.toString(), "DynamicsProcessing")
            put(AudioEffect.EFFECT_TYPE_ENV_REVERB.toString(), "EnvironmentalReverb")
            put(AudioEffect.EFFECT_TYPE_EQUALIZER.toString(), "Equalizer")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                put(AudioEffect.EFFECT_TYPE_HAPTIC_GENERATOR.toString(), "HapticGenerator")
            }
            put(AudioEffect.EFFECT_TYPE_LOUDNESS_ENHANCER.toString(), "LoudnessEnhancer")
            put(AudioEffect.EFFECT_TYPE_NS.toString(), "NoiseSuppressor")
            put(AudioEffect.EFFECT_TYPE_PRESET_REVERB.toString(), "PresetReverb")
            put(AudioEffect.EFFECT_TYPE_VIRTUALIZER.toString(), "Virtualizer")
        }

        /** Canonical JSON payload shared by the LAN API and the mailbox relay reply. */
        fun payloadJson(
            inv: List<EffectInventoryEntry>,
            probes: List<SessionProbe>
        ): org.json.JSONObject = org.json.JSONObject().apply {
            put("inventory", org.json.JSONArray().apply {
                inv.forEach {
                    put(org.json.JSONObject().apply {
                        put("name", it.name)
                        put("typeName", it.typeName)
                        put("typeUuid", it.typeUuid)
                        put("implUuid", it.implUuid)
                        put("connectMode", it.connectMode)
                        put("isVendor", it.isVendor)
                    })
                }
            })
            put("sessionProbes", org.json.JSONArray().apply {
                probes.forEach { p ->
                    put(org.json.JSONObject().apply {
                        put("effectType", p.effectType)
                        put("constructed", p.constructed)
                        put("hasControl", p.hasControl)
                        put("enabled", p.enabled)
                        put("parameters", p.parameters)
                        put("exception", p.exception ?: org.json.JSONObject.NULL)
                    })
                }
            })
        }
    }

    data class EffectInventoryEntry(
        val name: String,
        val typeName: String,
        val typeUuid: String,
        val implUuid: String,
        val connectMode: String,
        val isVendor: Boolean
    )

    data class SessionProbe(
        val effectType: String,
        val constructed: Boolean,
        val hasControl: Boolean,
        val enabled: Boolean,
        val parameters: String,
        val exception: String? = null
    )

    /** Enumerates every effect implementation the platform reports. */
    fun inventory(): List<EffectInventoryEntry> {
        val entries = try {
            AudioEffect.queryEffects().toList()
        } catch (e: Throwable) {
            Log.e(TAG, "queryEffects failed", e)
            emptyList()
        }
        return entries.map { d ->
            val typeStr = d.type.toString()
            val known = knownTypes()
            EffectInventoryEntry(
                name = d.name,
                typeName = known[typeStr] ?: "Unknown($typeStr)",
                typeUuid = typeStr,
                implUuid = d.uuid.toString(),
                connectMode = d.connectMode,
                isVendor = !known.containsKey(typeStr)
            )
        }.also { list ->
            Log.i(TAG, "=== Effect inventory (${list.size} entries) ===")
            list.forEach {
                Log.i(TAG, "${it.typeName} \"${it.name}\" mode=${it.connectMode} vendor=${it.isVendor} impl=${it.implUuid}")
            }
        }
    }

    /**
     * Instantiates each stock effect type on session 0 and reads back its
     * capabilities. Every instance is released in finally; nothing here
     * touches the production engine or saved profiles.
     */
    fun probeSessionZero(): List<SessionProbe> = listOf(
        probe("Equalizer") {
            var eq: Equalizer? = null
            try {
                eq = Equalizer(PRIORITY, SESSION_ID)
                var params = "bands=${eq.numberOfBands}"
                try {
                    val range = eq.bandLevelRange
                    params += ",levelRange=[${range[0]},${range[1]}]"
                    params += ",presets=${eq.numberOfPresets}"
                    params += ",centers=["
                    for (i in 0 until eq.numberOfBands) {
                        if (i > 0) params += ","
                        params += eq.getCenterFreq(i.toShort())
                    }
                    params += "]Hz"
                } catch (_: Throwable) {}
                SessionProbe("Equalizer", true, eq.hasControl(), eq.enabled, params)
            } finally {
                try { eq?.release() } catch (_: Throwable) {}
            }
        },
        probe("Virtualizer") {
            var v: Virtualizer? = null
            try {
                v = Virtualizer(PRIORITY, SESSION_ID)
                var params = "strengthSupported=${v.strengthSupported}"
                try {
                    val can = v.canVirtualize(AudioFormat.CHANNEL_OUT_STEREO, Virtualizer.VIRTUALIZATION_MODE_AUTO)
                    params += ",canVirtualizeStereo=$can"
                } catch (_: Throwable) {
                    params += ",canVirtualizeStereo=error"
                }
                if (v.strengthSupported) v.setStrength(1000)
                val enableResult = v.setEnabled(true)
                SystemClock.sleep(2000)
                params += ",strengthSet=${v.roundedStrength},enableResult=$enableResult," +
                    "enabledAfter=${v.enabled}"
                SessionProbe("Virtualizer", true, v.hasControl(), v.enabled, params)
            } catch (e: Throwable) {
                SessionProbe("Virtualizer", false, false, false, "", "${e.javaClass.simpleName}: ${e.message}")
            } finally {
                try { v?.release() } catch (_: Throwable) {}
            }
        },
        probe("BassBoost") {
            var b: BassBoost? = null
            try {
                b = BassBoost(PRIORITY, SESSION_ID)
                SessionProbe("BassBoost", true, b.hasControl(), b.enabled, "strengthSupported=${b.strengthSupported}")
            } finally {
                try { b?.release() } catch (_: Throwable) {}
            }
        },
        probe("LoudnessEnhancer") {
            var le: LoudnessEnhancer? = null
            try {
                le = LoudnessEnhancer(SESSION_ID)
                SessionProbe("LoudnessEnhancer", true, le.hasControl(), le.enabled, "gainmB=${le.targetGain}")
            } finally {
                try { le?.release() } catch (_: Throwable) {}
            }
        },
        dynamicsProcessingProbe(),
        downmixProbe()
    )

    /**
     * Instantiates the platform downmix implementation on session 0 via the
     * base AudioEffect constructor. Read-only: no parameter writes. The
     * downmixer is the only cross-channel effect this device reports, so
     * control of it decides whether any matrixing route exists here.
     */
    private fun downmixProbe(): SessionProbe {
        var fx: AudioEffect? = null
        return try {
            val desc = AudioEffect.queryEffects().firstOrNull {
                it.type.toString().equals(DOWNMIX_TYPE_UUID, ignoreCase = true)
            } ?: return SessionProbe(
                "Downmix", false, false, false, "",
                "no implementation of type $DOWNMIX_TYPE_UUID in queryEffects()"
            )
            fx = AudioEffect::class.java
                .getConstructor(
                    java.util.UUID::class.java,
                    java.util.UUID::class.java,
                    Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType
                )
                .newInstance(desc.type, desc.uuid, PRIORITY, SESSION_ID)
            val enableResult = fx.setEnabled(true)
            SystemClock.sleep(2000)
            SessionProbe(
                "Downmix", true, fx.hasControl(), fx.enabled,
                "impl=${desc.uuid},connectMode=${desc.connectMode},name=${desc.name}," +
                    "enableResult=$enableResult,enabledAfter=${fx.enabled}"
            )
        } catch (e: Throwable) {
            SessionProbe("Downmix", false, false, false, "", "${e.javaClass.simpleName}: ${e.message}")
        } finally {
            try { fx?.release() } catch (_: Throwable) {}
        }
    }

    private inline fun probe(effectType: String, block: () -> SessionProbe): SessionProbe =
        try {
            val p = block()
            Log.i(TAG, "Session-0 probe $effectType: constructed=${p.constructed} control=${p.hasControl} enabled=${p.enabled} params=[${p.parameters}]")
            p
        } catch (e: Throwable) {
            Log.w(TAG, "Session-0 probe $effectType FAILED: ${e.javaClass.simpleName}: ${e.message}")
            SessionProbe(effectType, false, false, false, "", "${e.javaClass.simpleName}: ${e.message}")
        }

    /**
     * Probes DynamicsProcessing on session 0 beyond the EQ stage the production
     * engine already uses: limiter availability and per-channel independence.
     * The limiter matters for matrix headroom; per-channel reads matter because
     * channel-independent processing is all DP offers (no cross-channel mix).
     */
    private fun dynamicsProcessingProbe(): SessionProbe {
        var dp: DynamicsProcessing? = null
        return try {
            dp = DynamicsProcessingProbe().createEnabled(DynamicsProcessingEq.INTERNAL_BANDS, 2)
            val d = dp!!
            var params = "channels=${d.channelCount}"
            try {
                d.setLimiterAllChannelsTo(
                    DynamicsProcessing.Limiter(true, true, 1, 60f, 60f, 10f, -2f, 0f)
                )
                params += ",limiter=set-ok"
            } catch (_: Throwable) {
                params += ",limiter=unsupported"
            }
            // Per-channel independence: set ch0 band 0 gain, confirm ch1 unchanged.
            var independent = "unknown"
            try {
                val before1 = d.getPreEqBandByChannelIndex(1, 0).gain
                val b0 = d.getPreEqBandByChannelIndex(0, 0)
                b0.gain = -3f
                d.setPreEqBandByChannelIndex(0, 0, b0)
                val after0 = d.getPreEqBandByChannelIndex(0, 0).gain
                val after1 = d.getPreEqBandByChannelIndex(1, 0).gain
                independent = if (after0 == -3f && after1 == before1) "confirmed" else "leaked(a0=$after0,a1=$after1)"
            } catch (_: Throwable) {
                independent = "unreadable"
            }
            SessionProbe("DynamicsProcessing", true, d.hasControl(), d.enabled, "$params,perChannelIndependent=$independent")
        } catch (e: Throwable) {
            SessionProbe("DynamicsProcessing", false, false, false, "", "${e.javaClass.simpleName}: ${e.message}")
        } finally {
            try { dp?.release() } catch (_: Throwable) {}
        }
    }

    /** Runs inventory + session-0 probes, logs a verdict-ready summary. */
    fun runAll(): Pair<List<EffectInventoryEntry>, List<SessionProbe>> {
        Log.i(TAG, "=== Audio Effect Diagnostics (session $SESSION_ID, API ${Build.VERSION.SDK_INT}) ===")
        val inv = inventory()
        val probes = probeSessionZero()
        Log.i(TAG, "=== Diagnostics summary: ${inv.count { it.isVendor }} vendor effect(s), ${probes.count { it.constructed }}/${probes.size} stock types constructible on session 0 ===")
        return inv to probes
    }
}

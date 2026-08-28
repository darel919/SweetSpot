package com.darelisme.sweetspot.service

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

internal class SweetSpotDiagnosticsCommandHandler(
    private val host: SweetSpotPeerCommandHost,
) {
    companion object {
        private const val TAG = "SweetSpot"
    }

    fun handle(type: String, payload: JSONObject, replyTo: (String, JSONObject) -> Unit): Boolean = when (type) {
        "probe.run" -> {
            runProbe(payload.optInt("bands", 128), replyTo)
            true
        }
        "probe.status" -> {
            postProbeStatus(replyTo)
            true
        }
        "probe.persistent.start" -> {
            host.commandDiagnostics.runPersistentProbe(payload.optInt("bands", 64))
            replyState(replyTo, true, null)
            true
        }
        "probe.persistent.release" -> {
            host.commandDiagnostics.releasePersistentProbe()
            replyState(replyTo, true, null)
            true
        }
        "virtualizer.on" -> {
            host.commandDiagnostics.setVirtualizer(true)
            replyState(replyTo, true, null)
            true
        }
        "virtualizer.off" -> {
            host.commandDiagnostics.setVirtualizer(false)
            replyState(replyTo, true, null)
            true
        }
        "probe.curve.apply" -> {
            val ok = applyProbeCurve(payload)
            replyTo(
                "state.snapshot",
                host.stateSnapshotJson().put("ok", ok).apply {
                    if (!ok) put("error", "Persistent probe curve was rejected")
                },
            )
            true
        }
        "diagnostics.deviceInfo" -> {
            replyTo("diagnostics.deviceInfo", host.deviceInfoJson())
            true
        }
        "diagnostics.transport" -> {
            replyTo("diagnostics.transport", host.transportDiagnosticsJson())
            true
        }
        "diagnostics.effects" -> {
            val diagnostics = host.commandDiagnostics.runEffectDiagnosticsBlocking()
            if (diagnostics.has("error")) {
                replyTo("state.snapshot", host.stateSnapshotJson().put("ok", false).put("error", diagnostics.optString("error")))
            } else {
                replyTo("diagnostics.effects", diagnostics)
            }
            true
        }
        else -> false
    }

    private fun replyState(replyTo: (String, JSONObject) -> Unit, ok: Boolean, error: String?) {
        replyTo("state.snapshot", host.stateSnapshotJson().put("ok", ok).apply {
            error?.let { put("error", it) }
        })
    }

    private fun runProbe(bands: Int, replyTo: (String, JSONObject) -> Unit) {
        val diagnostics = host.commandDiagnostics
        if (!diagnostics.runProbe(bands) { postProbeStatus(replyTo) }) {
            replyTo("state.snapshot", host.stateSnapshotJson().put("ok", false).put("error", "Probe is unavailable while calibration is active"))
        }
    }

    private fun applyProbeCurve(payload: JSONObject): Boolean {
        val commonArray = payload.optJSONArray("bandsDb")
        val leftArray = payload.optJSONArray("leftBandsDb")
        val rightArray = payload.optJSONArray("rightBandsDb")
        val diagnostics = host.commandDiagnostics
        return if (commonArray != null || leftArray != null || rightArray != null) {
            val expectedBands = diagnostics.persistentProbeBands
            val common = parseStrictProbeArray(commonArray, expectedBands)
            val left = parseStrictProbeArray(leftArray, expectedBands)
            val right = parseStrictProbeArray(rightArray, expectedBands)
            common != null
                && ((leftArray == null && rightArray == null && diagnostics.applyPersistentBands(common, null, null))
                    || (leftArray != null && rightArray != null && left != null && right != null && diagnostics.applyPersistentBands(common, left, right)))
        } else {
            diagnostics.applyPersistentCurve(payload.optString("curve", "hollow"))
        }
    }

    private fun postProbeStatus(replyTo: (String, JSONObject) -> Unit) {
        val diagnostics = host.commandDiagnostics
        val results = diagnostics.lastProbeResults.orEmpty()
        var highest = -1
        val array = JSONArray()
        for (result in results) {
            val pass = result.constructed && result.hasControl && result.enabled && result.actualBands == result.requested
            if (pass) highest = maxOf(highest, result.requested)
            array.put(JSONObject().apply {
                put("requested", result.requested)
                put("constructed", result.constructed)
                put("hasControl", result.hasControl)
                put("enabled", result.enabled)
                put("actualBands", result.actualBands)
                put("pass", pass)
                put("exception", result.exception ?: JSONObject.NULL)
            })
        }
        val persistent = if (diagnostics.isPersistentProbeActive()) JSONObject().apply {
            put("active", true)
            put("bands", diagnostics.persistentProbeBands)
            put("curve", diagnostics.persistentProbeCurveName ?: JSONObject.NULL)
            diagnostics.getPersistentProbeCurveSummary(0)?.let { summary ->
                put("curveSummary", JSONObject().apply {
                    put("bandsTotal", summary.bandsTotal)
                    put("bandsCut", summary.bandsCut)
                    put("bandsFlat", summary.bandsFlat)
                })
            }
            diagnostics.getPersistentProbeCurveSummary(1)?.let { summary ->
                put("rightCurveSummary", JSONObject().apply {
                    put("bandsTotal", summary.bandsTotal)
                    put("bandsCut", summary.bandsCut)
                    put("bandsFlat", summary.bandsFlat)
                })
            }
        } else JSONObject().put("active", false).put("bands", 0)
        persistent.put("error", diagnostics.persistentProbeError ?: JSONObject.NULL)
        replyTo("probe.status", JSONObject().apply {
            put("running", diagnostics.probeRunning)
            put("available", diagnostics.lastProbeResults != null)
            put("results", array)
            put("highest", highest)
            put("recommended", highest)
            put("persistent", persistent)
        })
    }
}

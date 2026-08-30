package com.darelisme.sweetspot.calibration.playback

import com.darelisme.sweetspot.calibration.model.MeasurementContext
import com.darelisme.sweetspot.calibration.model.ValidationRecoveryGate
import com.darelisme.sweetspot.calibration.model.ValidationRecoveryResult
import org.json.JSONObject

internal sealed interface SessionState {
    data object Idle : SessionState
    data class AwaitingUi(val session: Session) : SessionState
    data class Ready(
        val session: Session,
        val sweep: MeasurementSweep,
        val channel: String,
        val context: MeasurementContext?
    ) : SessionState
    data class Playing(
        val session: Session,
        val sweep: MeasurementSweep,
        val channel: String,
        val context: MeasurementContext?
    ) : SessionState
    data class Loudness(val session: Session) : SessionState
    data class AwaitingValidationFinalization(val session: Session) : SessionState
    data class ValidationFinalized(val session: Session) : SessionState
    data class Finishing(val session: Session) : SessionState
}

internal data class Session(
    val id: String,
    val channel: String,
    val phase: String,
    val candidateId: String?,
    var emit: (String, JSONObject, String?) -> Unit,
    var replyTo: String?,
    val rollbackTargetActive: Boolean? = null,
    var continuedPositionContext: MeasurementContext? = null,
    var validationFinalizationBlocked: Boolean = false,
    var validationFatal: Boolean = false,
    var validationOverrideApplied: Boolean = false,
    var validationOverrideRestored: Boolean = false,
    val validationRecoveryGate: ValidationRecoveryGate = ValidationRecoveryGate(),
    var validationRecoveryResult: ValidationRecoveryResult? = null,
    var validationRecoveryEventsPublished: Boolean = false,
    var endedEventPublished: Boolean = false,
    var validationAbortError: Pair<String, String>? = null,
    var cancellationRequested: Boolean = false,
    var audioOperationHeld: Boolean = false,
    var finalOutcome: String? = null,
)

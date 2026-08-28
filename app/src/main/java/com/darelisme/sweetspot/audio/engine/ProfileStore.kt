package com.darelisme.sweetspot.audio.engine

import com.darelisme.sweetspot.calibration.model.CalibrationCandidateTransaction
import com.darelisme.sweetspot.calibration.model.CalibrationCurveState
import com.darelisme.sweetspot.calibration.model.CalibrationValidationStatus
import android.content.Context
import android.content.SharedPreferences

/**
 * Persists EQ profiles on the TV.
 *
 * Two layers:
 *  - "last active": a single auto-saved snapshot (enabled, preset, custom levels)
 *    used to restore state on service start / after a reboot.
 *  - "named profiles": an explicit, user-created collection the UI lists and
 *    lets the user choose from. Each is stored under a name.
 *  - Calibration keys hold the currently applied calibration. When no candidate
 *    transaction exists, those keys are the committed calibration. Candidate
 *    keys hold the persisted transaction and its pre-candidate live calibration.
 */
class ProfileStore private constructor(
    private val prefs: SharedPreferences,
    @Suppress("UNUSED_PARAMETER") constructorMarker: Unit,
) {

    constructor(context: Context) : this(
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE),
        Unit,
    )

    internal constructor(prefs: SharedPreferences) : this(prefs, Unit)

    fun isEnabled(): Boolean = prefs.getBoolean(KEY_ENABLED, DEFAULT_ENABLED)

    fun isStartOnBootEnabled(): Boolean = prefs.getBoolean(KEY_START_ON_BOOT, DEFAULT_START_ON_BOOT)

    fun saveStartOnBootEnabled(enabled: Boolean): Boolean =
        prefs.edit().putBoolean(KEY_START_ON_BOOT, enabled).commit()

    fun load(): SavedProfile {
        val enabled = isEnabled()
        val preset = prefs.getInt(KEY_PRESET, 1)
        val levels = prefs.getString(KEY_LEVELS, null)
            ?.split(',')
            ?.mapNotNull { it.toIntOrNull() }
            ?.toIntArray()
        return SavedProfile(enabled, preset, levels)
    }

    fun save(enabled: Boolean, preset: Int, levels: IntArray?) {
        val editor = prefs.edit().apply {
            putBoolean(KEY_ENABLED, enabled)
            putInt(KEY_PRESET, preset)
            putString(KEY_LEVELS, levels?.joinToString(","))
        }
        if (isEnabled() != enabled) editor.commit() else editor.apply()
    }

    fun saveNamed(name: String, enabled: Boolean, preset: Int, levels: IntArray?) {
        val key = profileKey(name)
        prefs.edit().apply {
            putBoolean(key + SUFFIX_ENABLED, enabled)
            putInt(key + SUFFIX_PRESET, preset)
            putString(key + SUFFIX_LEVELS, levels?.joinToString(","))
            val names = listNames().toMutableSet()
            names.add(name)
            putStringSet(KEY_NAMES, names)
            apply()
        }
    }

    fun loadNamed(name: String): SavedProfile? {
        val key = profileKey(name)
        if (!prefs.contains(key + SUFFIX_PRESET)) return null
        val enabled = prefs.getBoolean(key + SUFFIX_ENABLED, true)
        val preset = prefs.getInt(key + SUFFIX_PRESET, 1)
        val levels = prefs.getString(key + SUFFIX_LEVELS, null)
            ?.split(',')
            ?.mapNotNull { it.toIntOrNull() }
            ?.toIntArray()
        return SavedProfile(enabled, preset, levels)
    }

    fun listNames(): List<String> =
        prefs.getStringSet(KEY_NAMES, emptySet())?.toList()?.sorted() ?: emptyList()

    fun deleteNamed(name: String) {
        val key = profileKey(name)
        prefs.edit().apply {
            remove(key + SUFFIX_ENABLED)
            remove(key + SUFFIX_PRESET)
            remove(key + SUFFIX_LEVELS)
            val names = listNames().toMutableList()
            names.remove(name)
            putStringSet(KEY_NAMES, names.toSet())
            apply()
        }
    }

    fun loadCalibration(): FloatArray? = loadCalibrationArray(KEY_CALIBRATION)

    fun saveCalibration(gains: FloatArray): Boolean {
        return prefs.edit().also { editor ->
            writeActiveCalibration(editor, CalibrationCurveState(gains, null, null, true))
            clearTransaction(editor)
        }.commit()
    }

    fun loadCalibrationChannels(): Pair<FloatArray, FloatArray>? {
        val left = loadCalibrationArray(KEY_CALIBRATION_LEFT)
        val right = loadCalibrationArray(KEY_CALIBRATION_RIGHT)
        return if (left != null && right != null) left to right else null
    }

    fun isCalibrationEnabled(): Boolean {
        if (loadCalibration() == null && loadCalibrationChannels() == null) return false
        return prefs.getBoolean(KEY_CALIBRATION_ACTIVE, true)
    }

    fun saveCalibrationEnabled(enabled: Boolean): Boolean {
        if (loadCalibration() == null && loadCalibrationChannels() == null) return false
        return prefs.edit().putBoolean(KEY_CALIBRATION_ACTIVE, enabled).commit()
    }

    fun saveCalibrationChannels(left: FloatArray, right: FloatArray): Boolean {
        return prefs.edit().also { editor ->
            writeActiveCalibration(editor, CalibrationCurveState(
                common = FloatArray(64) { (left[it] + right[it]) / 2f },
                left = left,
                right = right,
                active = true,
            ))
            clearTransaction(editor)
        }.commit()
    }

    fun clearCalibration(): Boolean = prefs.edit().also { editor ->
        clearActiveCalibration(editor)
        clearTransaction(editor)
    }.commit()

    internal fun loadCalibrationTransaction(): CalibrationCandidateTransaction? {
        val candidateId = prefs.getString(KEY_CANDIDATE_ID, null)
            ?.takeIf { it.isNotBlank() && it.length <= 128 }
            ?: return null
        val preCandidateLiveState = readStoredCurve(KEY_CANDIDATE_PREVIOUS) ?: return null
        val candidate = readStoredCurve(KEY_CANDIDATE_VALUE) ?: return null
        val status = when (prefs.getString(KEY_CANDIDATE_STATUS, null)) {
            STATUS_APPLYING -> CalibrationValidationStatus.APPLYING
            STATUS_ROLLING_BACK -> CalibrationValidationStatus.ROLLING_BACK
            STATUS_PENDING_VALIDATION -> CalibrationValidationStatus.PENDING
            STATUS_PASSED -> CalibrationValidationStatus.PASSED
            STATUS_NEUTRAL -> CalibrationValidationStatus.NEUTRAL
            STATUS_WORSE -> CalibrationValidationStatus.WORSE
            STATUS_INCONCLUSIVE -> CalibrationValidationStatus.INCONCLUSIVE
            STATUS_FAILED -> CalibrationValidationStatus.FAILED
            STATUS_IMPORTED -> CalibrationValidationStatus.IMPORTED
            else -> return null
        }
        val beforeRaw = prefs.getString(KEY_CANDIDATE_BEFORE_DB, null)
        val afterRaw = prefs.getString(KEY_CANDIDATE_AFTER_DB, null)
        val before = beforeRaw?.toFloatOrNull()?.takeIf { it.isFinite() }
        val after = afterRaw?.toFloatOrNull()?.takeIf { it.isFinite() }
        if ((beforeRaw != null && before == null) || (afterRaw != null && after == null)) return null
        if (status == CalibrationValidationStatus.PASSED || status == CalibrationValidationStatus.NEUTRAL || status == CalibrationValidationStatus.WORSE) {
            if (before == null || after == null) return null
        }
        return CalibrationCandidateTransaction(
            candidateId = candidateId,
            previous = preCandidateLiveState,
            candidate = candidate,
            validationStatus = status,
            beforeDb = before,
            afterDb = after,
            reason = prefs.getString(KEY_CANDIDATE_REASON, null),
        )
    }

    internal fun saveCandidateApplying(transaction: CalibrationCandidateTransaction): Boolean =
        prefs.edit().also { editor ->
            writeTransaction(editor, transaction.copy(validationStatus = CalibrationValidationStatus.PENDING), STATUS_APPLYING)
        }.commit()

    internal fun saveCandidatePendingValidation(transaction: CalibrationCandidateTransaction): Boolean =
        prefs.edit().also { editor ->
            writeActiveCalibration(editor, transaction.candidate)
            writeTransaction(editor, transaction, STATUS_PENDING_VALIDATION)
        }.commit()

    internal fun saveCandidateRollingBack(transaction: CalibrationCandidateTransaction): Boolean =
        prefs.edit().also { editor ->
            writeTransaction(editor, transaction, STATUS_ROLLING_BACK)
        }.commit()

    internal fun saveCandidateValidation(transaction: CalibrationCandidateTransaction): Boolean =
        prefs.edit().also { editor ->
            writeTransaction(editor, transaction, statusKey(transaction.validationStatus))
        }.commit()

    internal fun saveCandidateImported(transaction: CalibrationCandidateTransaction): Boolean =
        prefs.edit().also { editor ->
            writeTransaction(editor, transaction.copy(validationStatus = CalibrationValidationStatus.IMPORTED), STATUS_IMPORTED)
        }.commit()

    /** Commits the supplied calibration state and resolves the candidate transaction. */
    internal fun saveActiveCalibrationAndClearCandidate(curve: CalibrationCurveState): Boolean =
        prefs.edit().also { editor ->
            if (curve.active) writeActiveCalibration(editor, curve) else clearActiveCalibration(editor)
            clearTransaction(editor)
        }.commit()

    /** Applies the supplied pre-candidate state while retaining its persisted outcome. */
    internal fun saveActiveCalibrationPreservingCandidate(curve: CalibrationCurveState): Boolean =
        prefs.edit().also { editor ->
            if (curve.active) writeActiveCalibration(editor, curve) else clearActiveCalibration(editor)
        }.commit()

    internal fun clearActiveCalibrationOnly(): Boolean = prefs.edit().also { editor ->
        clearActiveCalibration(editor)
    }.commit()

    internal fun hasCalibrationTransactionData(): Boolean = listOf(
        KEY_CANDIDATE_ID,
        KEY_CANDIDATE_STATUS,
        KEY_CANDIDATE_BEFORE_DB,
        KEY_CANDIDATE_AFTER_DB,
        KEY_CANDIDATE_REASON,
        KEY_CANDIDATE_PREVIOUS + COMMON_SUFFIX,
        KEY_CANDIDATE_PREVIOUS + LEFT_SUFFIX,
        KEY_CANDIDATE_PREVIOUS + RIGHT_SUFFIX,
        KEY_CANDIDATE_PREVIOUS + ACTIVE_SUFFIX,
        KEY_CANDIDATE_VALUE + COMMON_SUFFIX,
        KEY_CANDIDATE_VALUE + LEFT_SUFFIX,
        KEY_CANDIDATE_VALUE + RIGHT_SUFFIX,
        KEY_CANDIDATE_VALUE + ACTIVE_SUFFIX,
    ).any(prefs::contains)

    internal fun clearCalibrationTransaction(): Boolean = prefs.edit().also { editor -> clearTransaction(editor) }.commit()

    private fun writeActiveCalibration(editor: SharedPreferences.Editor, curve: CalibrationCurveState) {
        if (curve.left != null && curve.right != null) {
            editor.remove(KEY_CALIBRATION)
                .putString(KEY_CALIBRATION_LEFT, curve.left.joinToString(","))
                .putString(KEY_CALIBRATION_RIGHT, curve.right.joinToString(","))
        } else {
            editor.putString(KEY_CALIBRATION, curve.common.joinToString(","))
                .remove(KEY_CALIBRATION_LEFT)
                .remove(KEY_CALIBRATION_RIGHT)
        }
        editor.putBoolean(KEY_CALIBRATION_ACTIVE, curve.active)
    }

    private fun clearActiveCalibration(editor: SharedPreferences.Editor) {
        editor.remove(KEY_CALIBRATION)
            .remove(KEY_CALIBRATION_LEFT)
            .remove(KEY_CALIBRATION_RIGHT)
            .remove(KEY_CALIBRATION_ACTIVE)
    }

    private fun writeTransaction(editor: SharedPreferences.Editor, transaction: CalibrationCandidateTransaction, status: String) {
        editor.putString(KEY_CANDIDATE_ID, transaction.candidateId)
            .putString(KEY_CANDIDATE_STATUS, status)
            .putString(KEY_CANDIDATE_BEFORE_DB, transaction.beforeDb?.toString())
            .putString(KEY_CANDIDATE_AFTER_DB, transaction.afterDb?.toString())
            .putString(KEY_CANDIDATE_REASON, transaction.reason)
        writeStoredCurve(editor, KEY_CANDIDATE_PREVIOUS, transaction.previous)
        writeStoredCurve(editor, KEY_CANDIDATE_VALUE, transaction.candidate)
    }

    private fun writeStoredCurve(editor: SharedPreferences.Editor, prefix: String, curve: CalibrationCurveState) {
        editor.putString(prefix + COMMON_SUFFIX, curve.common.joinToString(","))
            .putString(prefix + LEFT_SUFFIX, curve.left?.joinToString(","))
            .putString(prefix + RIGHT_SUFFIX, curve.right?.joinToString(","))
            .putBoolean(prefix + ACTIVE_SUFFIX, curve.active)
    }

    private fun readStoredCurve(prefix: String): CalibrationCurveState? {
        val common = loadCalibrationArray(prefix + COMMON_SUFFIX) ?: return null
        val leftPresent = prefs.contains(prefix + LEFT_SUFFIX)
        val rightPresent = prefs.contains(prefix + RIGHT_SUFFIX)
        if (leftPresent != rightPresent) return null
        val left = if (leftPresent) loadCalibrationArray(prefix + LEFT_SUFFIX) ?: return null else null
        val right = if (rightPresent) loadCalibrationArray(prefix + RIGHT_SUFFIX) ?: return null else null
        if ((left == null) != (right == null)) return null
        return CalibrationCurveState(common, left, right, prefs.getBoolean(prefix + ACTIVE_SUFFIX, true))
    }

    private fun clearTransaction(editor: SharedPreferences.Editor) {
        editor.remove(KEY_CANDIDATE_ID)
            .remove(KEY_CANDIDATE_STATUS)
            .remove(KEY_CANDIDATE_BEFORE_DB)
            .remove(KEY_CANDIDATE_AFTER_DB)
            .remove(KEY_CANDIDATE_REASON)
        listOf(KEY_CANDIDATE_PREVIOUS, KEY_CANDIDATE_VALUE).forEach { prefix ->
            editor.remove(prefix + COMMON_SUFFIX)
                .remove(prefix + LEFT_SUFFIX)
                .remove(prefix + RIGHT_SUFFIX)
                .remove(prefix + ACTIVE_SUFFIX)
        }
    }

    private fun statusKey(status: CalibrationValidationStatus): String = when (status) {
        CalibrationValidationStatus.PENDING -> STATUS_PENDING_VALIDATION
        CalibrationValidationStatus.APPLYING -> STATUS_APPLYING
        CalibrationValidationStatus.ROLLING_BACK -> STATUS_ROLLING_BACK
        CalibrationValidationStatus.PASSED -> STATUS_PASSED
        CalibrationValidationStatus.NEUTRAL -> STATUS_NEUTRAL
        CalibrationValidationStatus.WORSE -> STATUS_WORSE
        CalibrationValidationStatus.INCONCLUSIVE -> STATUS_INCONCLUSIVE
        CalibrationValidationStatus.FAILED -> STATUS_FAILED
        CalibrationValidationStatus.IMPORTED -> STATUS_IMPORTED
    }

    private fun loadCalibrationArray(key: String): FloatArray? {
        val values = prefs.getString(key, null)
            ?.split(',')
            ?.map { it.toFloatOrNull() ?: return null }
            ?.toFloatArray()
        return if (values != null && values.size == 64 && values.all { it.isFinite() }) values else null
    }

    private fun profileKey(name: String) = "p_$name"

    companion object {
        private const val PREFS_NAME = "sweetspot"
        private const val DEFAULT_ENABLED = true
        private const val DEFAULT_START_ON_BOOT = true
        private const val KEY_ENABLED = "enabled"
        private const val KEY_START_ON_BOOT = "start_on_boot"
        private const val KEY_PRESET = "preset"
        private const val KEY_LEVELS = "levels"
        private const val KEY_NAMES = "names"
        private const val SUFFIX_ENABLED = "_e"
        private const val SUFFIX_PRESET = "_p"
        private const val SUFFIX_LEVELS = "_l"
        private const val KEY_CALIBRATION = "calibration"
        private const val KEY_CALIBRATION_LEFT = "calibration_left"
        private const val KEY_CALIBRATION_RIGHT = "calibration_right"
        private const val KEY_CALIBRATION_ACTIVE = "calibration_active"
        private const val KEY_CANDIDATE_ID = "calibration_candidate_id"
        private const val KEY_CANDIDATE_STATUS = "calibration_candidate_status"
        private const val KEY_CANDIDATE_BEFORE_DB = "calibration_candidate_before_db"
        private const val KEY_CANDIDATE_AFTER_DB = "calibration_candidate_after_db"
        private const val KEY_CANDIDATE_REASON = "calibration_candidate_reason"
        private const val KEY_CANDIDATE_PREVIOUS = "calibration_candidate_previous_"
        private const val KEY_CANDIDATE_VALUE = "calibration_candidate_value_"
        private const val COMMON_SUFFIX = "common"
        private const val LEFT_SUFFIX = "left"
        private const val RIGHT_SUFFIX = "right"
        private const val ACTIVE_SUFFIX = "active"
        private const val STATUS_APPLYING = "applying"
        private const val STATUS_ROLLING_BACK = "rolling_back"
        private const val STATUS_PENDING_VALIDATION = "pending_validation"
        private const val STATUS_PASSED = "passed"
        private const val STATUS_NEUTRAL = "neutral"
        private const val STATUS_WORSE = "worse"
        private const val STATUS_INCONCLUSIVE = "inconclusive"
        private const val STATUS_FAILED = "failed"
        private const val STATUS_IMPORTED = "imported"
    }
}

data class SavedProfile(
    val enabled: Boolean,
    val preset: Int,
    val levels: IntArray?
)

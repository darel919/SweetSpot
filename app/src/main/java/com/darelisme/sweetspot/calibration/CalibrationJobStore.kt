package com.darelisme.sweetspot.calibration

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

open class CalibrationSnapshotException(message: String, cause: Throwable? = null) : IOException(message, cause)

class CalibrationSnapshotIncompatibleException(message: String) : CalibrationSnapshotException(message)

class StaleCalibrationSnapshotException(message: String) : CalibrationSnapshotException(message)

class CalibrationJobStore(
    private val directory: File,
    private val expectedAnalyzerRevision: AnalyzerRevision? = null,
    private val expectedSweepRevision: SweepRevision? = null,
) {
    fun save(job: CalibrationJob) {
        validateJob(job)
        if (expectedAnalyzerRevision != null && job.analyzerRevision != expectedAnalyzerRevision) {
            throw CalibrationSnapshotIncompatibleException("Analyzer revision does not match this store")
        }
        if (expectedSweepRevision != null && job.sweepRevision != expectedSweepRevision) {
            throw CalibrationSnapshotIncompatibleException("Sweep revision does not match this store")
        }
        ensureDirectory()
        val target = snapshotFile(job.id)
        if (target.exists()) {
            val current = load(job.id)
            if (current != null && current.revision > job.revision) {
                throw StaleCalibrationSnapshotException(
                    "Snapshot ${job.id.value} is at revision ${current.revision}, received ${job.revision}",
                )
            }
        }
        val temporary = File(directory, "${target.name}.tmp")
        try {
            FileOutputStream(temporary).use { fileOutput ->
                DataOutputStream(BufferedOutputStream(fileOutput)).use { output ->
                    CalibrationSnapshotCodec.write(output, job)
                    output.flush()
                    fileOutput.fd.sync()
                }
            }
            moveAtomically(temporary, target)
        } catch (error: Exception) {
            temporary.delete()
            if (error is CalibrationSnapshotException) throw error
            throw CalibrationSnapshotException("Unable to save calibration snapshot", error)
        } finally {
            if (temporary.exists()) temporary.delete()
        }
    }

    fun load(id: CalibrationJobId): CalibrationJob? {
        val file = snapshotFile(id)
        if (!file.exists()) return null
        if (!file.isFile) throw CalibrationSnapshotException("Calibration snapshot is not a file")
        return try {
            FileInputStream(file).use { fileInput ->
                DataInputStream(BufferedInputStream(fileInput)).use { input ->
                    val job = CalibrationSnapshotCodec.read(input)
                    if (input.available() != 0) throw IOException("Trailing snapshot data")
                    if (job.id != id) throw IOException("Snapshot job ID does not match its path")
                    validateRevision(job)
                    validateJob(job)
                    job
                }
            }
        } catch (error: CalibrationSnapshotException) {
            throw error
        } catch (error: Exception) {
            throw CalibrationSnapshotException("Unable to read calibration snapshot", error)
        }
    }

    fun list(): List<CalibrationJob> {
        if (!directory.exists()) return emptyList()
        val files = directory.listFiles { file -> file.isFile && file.name.endsWith(SNAPSHOT_SUFFIX) }
            ?: throw CalibrationSnapshotException("Unable to list calibration snapshots")
        return files.map { file ->
            val id = try {
                CalibrationSnapshotCodec.readJobId(file)
            } catch (error: Exception) {
                throw CalibrationSnapshotException("Unable to identify calibration snapshot", error)
            }
            load(id) ?: throw CalibrationSnapshotException("Calibration snapshot disappeared while listing")
        }.sortedBy { it.createdAtMs }
    }

    fun delete(id: CalibrationJobId) {
        val target = snapshotFile(id)
        if (target.exists() && !target.delete()) {
            throw CalibrationSnapshotException("Unable to delete calibration snapshot")
        }
        val temporary = File(directory, "${target.name}.tmp")
        if (temporary.exists() && !temporary.delete()) {
            throw CalibrationSnapshotException("Unable to delete temporary calibration snapshot")
        }
    }

    fun cleanupTemporarySnapshots() {
        if (!directory.exists()) return
        directory.listFiles { file -> file.isFile && file.name.endsWith(TEMP_SUFFIX) }
            ?.forEach { file -> file.delete() }
    }

    private fun validateRevision(job: CalibrationJob) {
        if (expectedAnalyzerRevision != null && job.analyzerRevision != expectedAnalyzerRevision) {
            throw CalibrationSnapshotIncompatibleException("Analyzer revision does not match this store")
        }
        if (expectedSweepRevision != null && job.sweepRevision != expectedSweepRevision) {
            throw CalibrationSnapshotIncompatibleException("Sweep revision does not match this store")
        }
    }

    private fun validateJob(job: CalibrationJob) {
        require(job.revision >= 0) { "Calibration revision must not be negative" }
        val usable = job.usability as? CalibrationUsability.Usable
        if (usable != null) {
            require(job.ledger.solutionSourcesAreAccepted(usable.best)) {
                "Calibration solution references an incomplete position"
            }
        }
        val candidate = job.candidate
        if (candidate != null) {
            require(usable != null && candidate.solutionId == usable.best.id) {
                "Calibration candidate does not reference the best solution"
            }
        }
    }

    private fun ensureDirectory() {
        if ((!directory.exists() && !directory.mkdirs()) || !directory.isDirectory) {
            throw CalibrationSnapshotException("Unable to create calibration snapshot directory")
        }
    }

    private fun snapshotFile(id: CalibrationJobId): File =
        File(directory, "${hash(id.value)}$SNAPSHOT_SUFFIX")

    private fun moveAtomically(from: File, to: File) {
        try {
            Files.move(
                from.toPath(),
                to.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(from.toPath(), to.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private companion object {
        const val SNAPSHOT_SUFFIX = ".snapshot"
        const val TEMP_SUFFIX = ".snapshot.tmp"

        fun hash(value: String): String = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }
}

private object CalibrationSnapshotCodec {
    private const val MAGIC = 0x5353434A
    private const val SCHEMA_VERSION = 1
    private const val MAX_STRING_BYTES = 1 shl 20
    private const val MAX_ATTEMPTS = 4_096
    private const val MAX_VALIDATION_RECORDS = 256

    fun write(output: DataOutputStream, job: CalibrationJob) {
        output.writeInt(MAGIC)
        output.writeInt(SCHEMA_VERSION)
        writeJob(output, job)
    }

    fun read(input: DataInputStream): CalibrationJob {
        if (input.readInt() != MAGIC) throw IOException("Unknown calibration snapshot format")
        val schema = input.readInt()
        if (schema != SCHEMA_VERSION) throw CalibrationSnapshotIncompatibleException("Unsupported snapshot schema $schema")
        return readJob(input)
    }

    fun readJobId(file: File): CalibrationJobId {
        FileInputStream(file).use { fileInput ->
            DataInputStream(BufferedInputStream(fileInput)).use { input ->
                if (input.readInt() != MAGIC) throw IOException("Unknown calibration snapshot format")
                if (input.readInt() != SCHEMA_VERSION) throw IOException("Unsupported snapshot schema")
                return CalibrationJobId(readString(input))
            }
        }
    }

    private fun writeJob(output: DataOutputStream, job: CalibrationJob) {
        writeString(output, job.id.value)
        output.writeLong(job.createdAtMs)
        output.writeLong(job.revision)
        writeString(output, job.analyzerRevision.value)
        writeString(output, job.sweepRevision.value)
        writePhase(output, job.phase)
        writeLedger(output, job.ledger)
        writeUsability(output, job.usability)
        writeNullable(output, job.confidence, ::writeConfidence)
        writeNullable(output, job.nextAction, ::writeAction)
        writeNullable(output, job.candidate, ::writeCandidate)
        writeList(output, job.validationHistory, ::writeValidationRecord)
        writeNullable(output, job.pendingEffect, ::writePendingEffect)
        writeNullable(output, job.lastError, ::writeError)
    }

    private fun readJob(input: DataInputStream): CalibrationJob {
        val id = CalibrationJobId(readString(input))
        val createdAtMs = input.readLong()
        val revision = input.readLong()
        val analyzerRevision = AnalyzerRevision(readString(input))
        val sweepRevision = SweepRevision(readString(input))
        val phase = readPhase(input)
        val ledger = readLedger(input)
        val usability = readUsability(input, ledger)
        val confidence = readNullable(input, ::readConfidence)
        val nextAction = readNullable(input, ::readAction)
        val candidate = readNullable(input, ::readCandidate)
        val validationHistory = readList(input, MAX_VALIDATION_RECORDS, ::readValidationRecord)
        val pendingEffect = readNullable(input, ::readPendingEffect)
        val lastError = readNullable(input, ::readError)
        return CalibrationJob(
            id = id,
            createdAtMs = createdAtMs,
            revision = revision,
            analyzerRevision = analyzerRevision,
            sweepRevision = sweepRevision,
            phase = phase,
            ledger = ledger,
            usability = usability,
            confidence = confidence,
            nextAction = nextAction,
            candidate = candidate,
            validationHistory = validationHistory,
            pendingEffect = pendingEffect,
            lastError = lastError,
        )
    }

    private fun writePhase(output: DataOutputStream, phase: CalibrationPhase) {
        when (phase) {
            CalibrationPhase.CenterPreflight -> output.writeByte(0)
            CalibrationPhase.MeasuringRequired -> output.writeByte(1)
            CalibrationPhase.Usable -> output.writeByte(2)
            CalibrationPhase.Refining -> output.writeByte(3)
            CalibrationPhase.CandidatePending -> output.writeByte(4)
            CalibrationPhase.Validating -> output.writeByte(5)
            CalibrationPhase.Reoptimizing -> output.writeByte(6)
            CalibrationPhase.Restoring -> output.writeByte(7)
            CalibrationPhase.Complete -> output.writeByte(8)
            is CalibrationPhase.Failed -> {
                output.writeByte(9)
                writeString(output, phase.reason)
            }
            CalibrationPhase.Cancelled -> output.writeByte(10)
        }
    }

    private fun readPhase(input: DataInputStream): CalibrationPhase = when (input.readUnsignedByte()) {
        0 -> CalibrationPhase.CenterPreflight
        1 -> CalibrationPhase.MeasuringRequired
        2 -> CalibrationPhase.Usable
        3 -> CalibrationPhase.Refining
        4 -> CalibrationPhase.CandidatePending
        5 -> CalibrationPhase.Validating
        6 -> CalibrationPhase.Reoptimizing
        7 -> CalibrationPhase.Restoring
        8 -> CalibrationPhase.Complete
        9 -> CalibrationPhase.Failed(readString(input))
        10 -> CalibrationPhase.Cancelled
        else -> throw IOException("Unknown calibration phase")
    }

    private fun writeLedger(output: DataOutputStream, ledger: PositionLedger) {
        writeList(output, ledger.attempts, ::writeAttempt)
    }

    private fun readLedger(input: DataInputStream): PositionLedger =
        PositionLedger.fromAttempts(readList(input, MAX_ATTEMPTS, ::readAttempt))

    private fun writeAttempt(output: DataOutputStream, attempt: CaptureAttempt) {
        when (attempt) {
            is CaptureAttempt.Accepted -> {
                output.writeByte(0)
                writeEvidence(output, attempt.evidence)
            }
            is CaptureAttempt.Rejected -> {
                output.writeByte(1)
                writeRequest(output, attempt.request)
                writeEnum(output, attempt.reason)
            }
        }
    }

    private fun readAttempt(input: DataInputStream): CaptureAttempt = when (input.readUnsignedByte()) {
        0 -> CaptureAttempt.Accepted(readEvidence(input))
        1 -> CaptureAttempt.Rejected(readRequest(input), readEnum(input))
        else -> throw IOException("Unknown capture attempt")
    }

    private fun writeEvidence(output: DataOutputStream, evidence: AcceptedChannelEvidence) {
        writeRequest(output, evidence.request)
        writeCurve(output, evidence.responseDb)
        output.writeFloat(evidence.quality.snrDb)
        output.writeFloat(evidence.quality.markerConfidence)
        output.writeFloat(evidence.quality.directArrivalConfidence)
    }

    private fun readEvidence(input: DataInputStream): AcceptedChannelEvidence = AcceptedChannelEvidence(
        request = readRequest(input),
        responseDb = readCurve(input),
        quality = CaptureQuality(input.readFloat(), input.readFloat(), input.readFloat()),
    )

    private fun writeRequest(output: DataOutputStream, request: CaptureRequest) {
        writeString(output, request.captureId.value)
        writeEnum(output, request.position)
        writeEnum(output, request.channel)
        output.writeInt(request.attemptIndex)
        output.writeBoolean(request.optional)
    }

    private fun readRequest(input: DataInputStream): CaptureRequest = CaptureRequest(
        captureId = CaptureId(readString(input)),
        position = readEnum(input),
        channel = readEnum(input),
        attemptIndex = input.readInt(),
        optional = input.readBoolean(),
    )

    private fun writeUsability(output: DataOutputStream, usability: CalibrationUsability) {
        when (usability) {
            CalibrationUsability.NotYetUsable -> output.writeByte(0)
            is CalibrationUsability.Usable -> {
                output.writeByte(1)
                writeEnum(output, usability.grade)
                writeSolution(output, usability.best)
            }
        }
    }

    private fun readUsability(input: DataInputStream, ledger: PositionLedger): CalibrationUsability =
        when (input.readUnsignedByte()) {
            0 -> CalibrationUsability.NotYetUsable
            1 -> {
                val grade = readEnum<UsabilityGrade>(input)
                CalibrationUsability.Usable(readSolution(input, ledger), grade)
            }
            else -> throw IOException("Unknown calibration usability")
        }

    private fun writeSolution(output: DataOutputStream, solution: CalibrationSolution) {
        writeString(output, solution.id.value)
        writeList(output, solution.sourcePositions.sortedBy { it.ordinal }, ::writeEnum)
        writeCurve(output, solution.correctionDb)
        writeConfidence(output, solution.confidence)
        output.writeFloat(solution.score)
        writeEnum(output, solution.correctionMode)
    }

    private fun readSolution(input: DataInputStream, ledger: PositionLedger): CalibrationSolution {
        val id = SolutionId(readString(input))
        val sourcePositions = readList(
            input,
            CalibrationPosition.entries.size,
        ) { stream -> readEnum<CalibrationPosition>(stream) }
        val completePositions = sourcePositions.map { position ->
            ledger.complete(position) ?: throw IOException("Solution references incomplete position")
        }
        return CalibrationSolution.fromCompletePositions(
            id = id,
            positions = completePositions,
            correctionDb = readCurve(input),
            confidence = readConfidence(input),
            score = input.readFloat(),
            correctionMode = readEnum(input),
        )
    }

    private fun writeConfidence(output: DataOutputStream, confidence: CalibrationConfidence) {
        confidence.bands.forEach { band ->
            output.writeFloat(band.frequencyHz)
            output.writeFloat(band.confidence)
            output.writeFloat(band.spatialSpreadDb)
            output.writeBoolean(band.usable)
        }
        output.writeInt(confidence.usableBandCount)
        output.writeFloat(confidence.score)
        output.writeBoolean(confidence.grade != null)
        if (confidence.grade != null) writeEnum(output, confidence.grade)
    }

    private fun readConfidence(input: DataInputStream): CalibrationConfidence {
        val bands = List(CalibrationBandGrid.BAND_COUNT) {
            BandConfidence(input.readFloat(), input.readFloat(), input.readFloat(), input.readBoolean())
        }
        val usableBandCount = input.readInt()
        val score = input.readFloat()
        val grade = if (input.readBoolean()) readEnum<UsabilityGrade>(input) else null
        return CalibrationConfidence(bands, usableBandCount, score, grade)
    }

    private fun writeAction(output: DataOutputStream, action: CalibrationAction) {
        when (action) {
            is CalibrationAction.Capture -> {
                output.writeByte(0)
                writeRequest(output, action.request)
                writeString(output, action.instruction)
            }
            is CalibrationAction.Validate -> {
                output.writeByte(1)
                writeString(output, action.captureId.value)
                writeEnum(output, action.position)
                writeString(output, action.candidateId.value)
                output.writeInt(action.attemptIndex)
                writeString(output, action.instruction)
            }
            is CalibrationAction.Wait -> {
                output.writeByte(2)
                writeString(output, action.message)
            }
            is CalibrationAction.Complete -> {
                output.writeByte(3)
                writeString(output, action.solutionId.value)
            }
        }
    }

    private fun readAction(input: DataInputStream): CalibrationAction = when (input.readUnsignedByte()) {
        0 -> CalibrationAction.Capture(readRequest(input), readString(input))
        1 -> CalibrationAction.Validate(
            captureId = CaptureId(readString(input)),
            position = readEnum(input),
            candidateId = CandidateId(readString(input)),
            attemptIndex = input.readInt(),
            instruction = readString(input),
        )
        2 -> CalibrationAction.Wait(readString(input))
        3 -> CalibrationAction.Complete(SolutionId(readString(input)))
        else -> throw IOException("Unknown calibration action")
    }

    private fun writeCandidate(output: DataOutputStream, candidate: CalibrationCandidateState) {
        writeString(output, candidate.id.value)
        writeString(output, candidate.solutionId.value)
        writeEnum(output, candidate.mode)
        output.writeInt(candidate.validationAttemptIndex)
    }

    private fun readCandidate(input: DataInputStream): CalibrationCandidateState = CalibrationCandidateState(
        id = CandidateId(readString(input)),
        solutionId = SolutionId(readString(input)),
        mode = readEnum(input),
        validationAttemptIndex = input.readInt(),
    )

    private fun writeValidationRecord(output: DataOutputStream, record: ValidationRecord) {
        writeString(output, record.candidateId.value)
        writeEnum(output, record.outcome)
        output.writeInt(record.attemptIndex)
    }

    private fun readValidationRecord(input: DataInputStream): ValidationRecord = ValidationRecord(
        candidateId = CandidateId(readString(input)),
        outcome = readEnum(input),
        attemptIndex = input.readInt(),
    )

    private fun writePendingEffect(output: DataOutputStream, effect: PendingCalibrationEffect) {
        when (effect) {
            is PendingCalibrationEffect.StageCandidate -> {
                output.writeByte(0)
                writeString(output, effect.solutionId.value)
            }
            is PendingCalibrationEffect.RollbackThenReoptimize -> {
                output.writeByte(1)
                writeString(output, effect.candidateId.value)
                output.writeBoolean(effect.nextMode != null)
                if (effect.nextMode != null) writeEnum(output, effect.nextMode)
            }
            is PendingCalibrationEffect.RestorePrevious -> {
                output.writeByte(2)
                writeString(output, effect.candidateId.value)
            }
        }
    }

    private fun readPendingEffect(input: DataInputStream): PendingCalibrationEffect = when (input.readUnsignedByte()) {
        0 -> PendingCalibrationEffect.StageCandidate(SolutionId(readString(input)))
        1 -> PendingCalibrationEffect.RollbackThenReoptimize(
            candidateId = CandidateId(readString(input)),
            nextMode = if (input.readBoolean()) readEnum(input) else null,
        )
        2 -> PendingCalibrationEffect.RestorePrevious(CandidateId(readString(input)))
        else -> throw IOException("Unknown pending calibration effect")
    }

    private fun writeError(output: DataOutputStream, error: CalibrationJobError) {
        writeString(output, error.code)
        writeString(output, error.message)
    }

    private fun readError(input: DataInputStream): CalibrationJobError =
        CalibrationJobError(readString(input), readString(input))

    private fun writeCurve(output: DataOutputStream, curve: BandCurve) {
        curve.toFloatArray().forEach(output::writeFloat)
    }

    private fun readCurve(input: DataInputStream): BandCurve =
        BandCurve.of(FloatArray(CalibrationBandGrid.BAND_COUNT) { input.readFloat() })

    private fun <T> writeNullable(output: DataOutputStream, value: T?, writer: (DataOutputStream, T) -> Unit) {
        output.writeBoolean(value != null)
        if (value != null) writer(output, value)
    }

    private fun <T> readNullable(input: DataInputStream, reader: (DataInputStream) -> T): T? =
        if (input.readBoolean()) reader(input) else null

    private fun <T> writeList(output: DataOutputStream, values: List<T>, writer: (DataOutputStream, T) -> Unit) {
        output.writeInt(values.size)
        values.forEach { writer(output, it) }
    }

    private fun <T> readList(
        input: DataInputStream,
        maxSize: Int,
        reader: (DataInputStream) -> T,
    ): List<T> {
        val size = input.readInt()
        if (size < 0 || size > maxSize) throw IOException("Invalid snapshot list size $size")
        return List(size) { reader(input) }
    }

    private fun writeString(output: DataOutputStream, value: String) {
        val bytes = value.toByteArray(StandardCharsets.UTF_8)
        require(bytes.size <= MAX_STRING_BYTES) { "Snapshot string is too large" }
        output.writeInt(bytes.size)
        output.write(bytes)
    }

    private fun readString(input: DataInputStream): String {
        val length = input.readInt()
        if (length < 0 || length > MAX_STRING_BYTES) throw IOException("Invalid snapshot string length")
        val bytes = ByteArray(length)
        try {
            input.readFully(bytes)
        } catch (error: EOFException) {
            throw IOException("Truncated snapshot string", error)
        }
        return String(bytes, StandardCharsets.UTF_8)
    }

    private inline fun <reified T : Enum<T>> writeEnum(output: DataOutputStream, value: T) =
        writeString(output, value.name)

    private inline fun <reified T : Enum<T>> readEnum(input: DataInputStream): T {
        val name = readString(input)
        return enumValues<T>().firstOrNull { it.name == name }
            ?: throw IOException("Unknown enum value $name")
    }
}

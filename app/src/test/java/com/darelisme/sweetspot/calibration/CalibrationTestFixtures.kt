package com.darelisme.sweetspot.calibration

internal object CalibrationTestFixtures {
    fun accepted(
        position: CalibrationPosition,
        channel: CaptureChannel,
        response: FloatArray = FloatArray(CalibrationBandGrid.BAND_COUNT),
        attemptIndex: Int = 0,
    ): CalibrationEvent.ChannelAccepted = CalibrationEvent.ChannelAccepted(
        AcceptedChannelEvidence(
            request = CaptureRequest(
                captureId = CaptureId("${position.name.lowercase()}-${channel.name.lowercase()}-$attemptIndex"),
                position = position,
                channel = channel,
                attemptIndex = attemptIndex,
                optional = position.optional,
            ),
            responseDb = BandCurve.of(response),
            quality = CaptureQuality(30f, 0.95f, 0.95f),
        ),
    )

    fun complete(
        position: CalibrationPosition,
        response: FloatArray = FloatArray(CalibrationBandGrid.BAND_COUNT),
    ): CompletePosition = CompletePosition(
        position,
        accepted(position, CaptureChannel.LEFT, response).evidence,
        accepted(position, CaptureChannel.RIGHT, response).evidence,
    )

    fun newJob(): CalibrationJob = CalibrationJob.new(
        id = CalibrationJobId("job-test"),
        createdAtMs = 1L,
        analyzerRevision = AnalyzerRevision("android-response-v1"),
        sweepRevision = SweepRevision("android-sweep-v3"),
    )

    fun usableJob(
        machine: CalibrationStateMachine = CalibrationStateMachine(),
        responseFor: (CalibrationPosition) -> FloatArray = {
            FloatArray(CalibrationBandGrid.BAND_COUNT)
        },
    ): CalibrationJob {
        var job = newJob()
        PositionLedger.MANDATORY_POSITIONS.sortedBy { it.ordinal }.forEach { position ->
            job = machine.reduce(job, accepted(position, CaptureChannel.LEFT, responseFor(position))).job
            job = machine.reduce(job, accepted(position, CaptureChannel.RIGHT, responseFor(position))).job
        }
        return job
    }
}

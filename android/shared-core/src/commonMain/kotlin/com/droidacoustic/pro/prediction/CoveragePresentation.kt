package com.droidacoustic.pro.prediction

/** Coverage/inspection semantics shared by clients; no prediction or persisted inputs are changed. */
data class CoveragePresentation(
    val mapLabel: String,
    val showFrequencyButtons: Boolean,
    val status: String,
    val action: String,
    val showAction: Boolean,
    val actionEnabled: Boolean,
    val needsCalculation: Boolean
) {
    companion object {
        fun describe(signalType: String, frequencyHz: Int, state: PredictionState, automatic: Boolean, validity: PredictionValidity? = null): CoveragePresentation {
            require(signalType == "BAND" || signalType == "SPECTRUM")
            val spectrum = signalType == "SPECTRUM"
            val needed = state in listOf(PredictionState.EMPTY, PredictionState.STALE, PredictionState.FAILED)
            return CoveragePresentation(
                if (spectrum) "Broadband coverage • summed SPL" else "Band coverage • $frequencyHz Hz",
                !spectrum,
                when (state) {
                    PredictionState.EMPTY -> if (automatic) "No results — automatic calculation enabled" else "No results — tap Calculate"
                    PredictionState.STALE -> if (automatic) "Settings changed — awaiting automatic calculation" else "Recalculation required — tap Recalculate"
                    PredictionState.RUNNING -> "Calculating… results are not ready"
                    PredictionState.CURRENT -> validity?.status() ?: "Results up to date"
                    PredictionState.FAILED -> "Calculation failed — tap Retry calculation"
                },
                when(state) {
                    PredictionState.EMPTY -> "Calculate"
                    PredictionState.RUNNING -> "Calculating…"
                    PredictionState.FAILED -> "Retry calculation"
                    else -> "Recalculate"
                },
                true,
                state != PredictionState.RUNNING,
                needed
            )
        }
    }
}

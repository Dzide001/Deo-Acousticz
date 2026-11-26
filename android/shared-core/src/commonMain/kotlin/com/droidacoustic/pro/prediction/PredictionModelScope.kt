package com.droidacoustic.pro.prediction

import kotlinx.serialization.json.*

/** Shared product contract for the implemented solver, not a claim of external validation.
 * No platform may infer room metrics or enable the legacy optimizer from these predictions.
 */
object PredictionModelScope {
    const val maxReflectionOrder = 1
    const val automaticOptimizationAvailable = false
    const val reflectionLabel = "First order only"
    const val profileLabel = "Coverage grid spacing"
    const val summary = "Direct sound + first-order reflections and scattering (experimental)."
    val limitations: List<String> = listOf(
        "Geometric blocking only: no edge diffraction or sound transmission. Shadows are approximate.",
        "Finite reflectors use clipped image paths; no edge transition is modelled.",
        "Venue walls and ceiling are visual context. Only assigned stage, Blocks and imported faces act as boundaries; the optional ground model is separate.",
        "No reverberant tail, RT60 or STI prediction. No maximum-output or headroom prediction.",
        "A 0 dB display value is a reporting floor, not proof of silence. Unavailable data is shown separately.",
        "Not externally validated against manufacturer predictions or controlled measurements."
    )
    /** Persisted profile IDs are retained; they specify receiver spacing, not solver order. */
    fun coverageGridSpacingM(profile: String): Float = when (profile) {
        "Fast" -> 3f
        "Precision" -> 1f
        else -> 2f
    }
    fun reportJson(): String = buildJsonObject {
        put("id", "direct-first-order-v1")
        put("maxReflectionOrder", maxReflectionOrder)
        put("diffraction", false)
        put("transmission", false)
        put("roomMetrics", false)
        put("headroom", false)
        put("automaticOptimization", automaticOptimizationAvailable)
        put("externalValidation", "pending")
        put("limitations", JsonArray(limitations.map(::JsonPrimitive)))
    }.toString()
}

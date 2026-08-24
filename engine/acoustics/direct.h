// =============================================================================
// acoustics/direct.h  —  Direct-field SPL calculation
// =============================================================================
//
// Implements the corrected direct-field SPL formula from the spec:
//
//   SPL_1m   = sensitivity + inputGainDB
//   Lp_direct = SPL_1m − 20·log10(distance)
//   Lp_direct += directivityInterpolation(horzAngle, vertAngle, frequency)
//   Lp_direct -= atmosphericAbsorption(distance, frequency, temp, humidity)
//
// Note: the erroneous −11 constant from the original spec has been removed.
//       This formula already applies the free-field divergence correction
//       that gives 0 dB at 1 m (by definition of sensitivity).
//
// Phase 0: Only inverse-square law term is implemented.
// Phase 5: atmosphericAbsorption() will be added.

#pragma once
#include <cmath>

namespace dac {
namespace acoustics {

// ─── Constants ────────────────────────────────────────────────────────────────

constexpr float SPEED_OF_SOUND_MS = 343.0f;   // m/s at 20°C, sea level
constexpr float REF_DISTANCE_M    = 1.0f;      // Sensitivity reference distance

// ─── SPL helpers ──────────────────────────────────────────────────────────────

/// Convert SPL value to linear energy (pressure squared, arbitrary units).
inline float splToEnergy(float splDB) noexcept {
    return std::pow(10.f, splDB / 10.f);
}

/// Convert summed linear energy back to SPL (dB).
inline float energyToSpl(float energy) noexcept {
    if (energy <= 0.f) return -200.f;
    return 10.f * std::log10(energy);
}

// ─── Direct-field SPL ─────────────────────────────────────────────────────────

/// Compute on-axis direct-field SPL at a given distance.
///
/// @param sensitivityDB   Manufacturer 1-W/1-m sensitivity (dB SPL).
/// @param inputGainDB     Input signal level relative to rated power (dB).
///                        0 dB = full rated power.
/// @param distanceM       Distance from acoustic centre to listener (metres).
///                        Clamped to >= 0.01 m to avoid singularity.
/// @return Lp_direct in dB SPL.
inline float directSPL(float sensitivityDB, float inputGainDB, float distanceM) noexcept {
    float d = (distanceM < 0.01f) ? 0.01f : distanceM;
    float spl1m = sensitivityDB + inputGainDB;
    return spl1m - 20.f * std::log10(d / REF_DISTANCE_M);
}

/// Compute arrival time at a given distance.
/// @return Time in milliseconds.
inline float arrivalTimeMs(float distanceM) noexcept {
    return (distanceM / SPEED_OF_SOUND_MS) * 1000.f;
}

// ─── Incoherent summation ─────────────────────────────────────────────────────

/// Sum multiple SPL values incoherently (energy addition).
/// Use for broadband, uncorrelated sources.
///
/// @param spl   Array of SPL values in dB.
/// @param count Number of values.
/// @return Total SPL in dB.
inline float sumIncoherent(const float* spl, uint32_t count) noexcept {
    float energy = 0.f;
    for (uint32_t i = 0; i < count; ++i) energy += splToEnergy(spl[i]);
    return energyToSpl(energy);
}

} // namespace acoustics
} // namespace dac

// =============================================================================
// AcousticEngine.h  —  JNI Bridge Contract
// =============================================================================
//
// THIS IS THE STABLE C++ API BETWEEN THE KOTLIN UI AND THE C++ ACOUSTIC ENGINE.
//
// Rules:
//  1. Never add or remove functions without bumping DAC_ENGINE_VERSION.
//  2. This header must compile cleanly on both Android (NDK) and desktop (gtest CI).
//  3. All pointer parameters are non-owning — the caller retains ownership.
//  4. All functions are thread-safe internally; external synchronisation is the
//     caller's responsibility.
//
// Companion Kotlin file: engine/AcousticEngine.kt
// JNI glue:             android/app/src/main/cpp/jni_bridge.cpp
//
// =============================================================================

#pragma once

#include <cstdint>
#include <stdbool.h>

// Android-only headers — guarded so this file compiles on desktop too.
#ifdef __ANDROID__
  #include <android/asset_manager.h>
#else
  // Stub so the engine compiles for desktop unit tests without the NDK.
  #ifndef AASSETMANAGER_DEFINED
  #define AASSETMANAGER_DEFINED
  typedef void AAssetManager;
  #endif
#endif

// Current API version — increment when the signature of any function changes.
#define DAC_ENGINE_VERSION "0.1.0-phase0"

namespace dac {

// =============================================================================
// Opaque engine state — forward declaration only.
// Kotlin stores this as a jlong (native pointer cast).
// =============================================================================
struct EngineState;

// =============================================================================
// Lifecycle
// =============================================================================

/// Allocate and return a new EngineState.
/// Must be called once per engine instance.
/// @return Non-null pointer on success, nullptr on allocation failure.
EngineState* engine_create();

/// Destroy the EngineState and release all resources (GPU buffers, pipelines).
/// Safe to call with nullptr. Sets *state to nullptr after destruction.
void engine_destroy(EngineState* state);

/// Human-readable engine version string — safe to call before engine_create().
const char* engine_version();

// =============================================================================
// Phase 0 — Vulkan Initialisation
// =============================================================================

/// Initialise the Vulkan compute pipeline.
/// Loads the distance.spv SPIR-V shader from the Android asset bundle.
///
/// @param state        Engine handle from engine_create().
/// @param assetManager Android AssetManager for SPIR-V shader loading.
///                     Pass nullptr on desktop — CPU fallback will be used.
/// @return true on success. On failure, computeDistances() falls back to CPU.
bool engine_init_vulkan(EngineState* state, AAssetManager* assetManager);

// =============================================================================
// Phase 0 — GPU Distance Kernel
// =============================================================================

/// Compute Euclidean distances between all speaker/grid-point pairs.
///
/// INPUT LAYOUT:
///   speakerPositions[s*3 + 0] = x   (metres)
///   speakerPositions[s*3 + 1] = y
///   speakerPositions[s*3 + 2] = z
///
/// OUTPUT LAYOUT:
///   outDistances[g * speakerCount + s] = distance(gridPoint[g], speaker[s])
///
/// Performance: GPU path uses the Vulkan distance.comp kernel (Kernel 1).
///              If Vulkan failed to init, CPU fallback is used transparently.
///
/// @param state            Engine handle.
/// @param speakerPositions Flat float array, length = speakerCount * 3.
/// @param speakerCount     Number of speakers (<= MemoryManager::MAX_SPEAKERS).
/// @param gridPoints       Flat float array, length = gridCount * 3.
/// @param gridCount        Number of audience grid points.
/// @param outDistances     Caller-allocated output, length = gridCount * speakerCount.
/// @return true on success.
bool engine_compute_distances(
    EngineState*  state,
    const float*  speakerPositions,
    uint32_t      speakerCount,
    const float*  gridPoints,
    uint32_t      gridCount,
    float*        outDistances
);

// =============================================================================
// Phase 3+ stubs — Speaker Management (not implemented in Phase 0)
// =============================================================================
// These declarations exist so AcousticEngine.kt can reference them at
// compile time. Implementations return false/no-op until Phase 3.

// bool engine_add_speaker(EngineState*, float x, float y, float z,
//                         float yaw, float pitch, const char* modelJson);
// bool engine_remove_speaker(EngineState*, uint32_t speakerIndex);

// =============================================================================
// Phase 4+ stubs — SPL Simulation (not implemented in Phase 0)
// =============================================================================
// bool engine_calculate(EngineState*);
// bool engine_get_grid_results(EngineState*, float* outSpl, uint32_t count);

} // namespace dac

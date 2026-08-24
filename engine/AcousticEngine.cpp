#include "AcousticEngine.h"

#include <cstdlib>
#include <cstring>
#include <cmath>
#include <memory>

#include "gpu/vulkan_compute.h"

namespace dac {

// =============================================================================
// Internal engine state
// =============================================================================

struct EngineState {
    std::unique_ptr<VulkanCompute> vulkan;
    bool vulkanReady = false;
};

// =============================================================================
// Lifecycle
// =============================================================================

EngineState* engine_create() {
    auto* state = new (std::nothrow) EngineState();
    if (!state) return nullptr;
    state->vulkan = std::make_unique<VulkanCompute>();
    return state;
}

void engine_destroy(EngineState* state) {
    if (!state) return;
    if (state->vulkan) {
        state->vulkan->destroy();
    }
    delete state;
}

const char* engine_version() {
    return DAC_ENGINE_VERSION;
}

// =============================================================================
// Vulkan initialisation
// =============================================================================

bool engine_init_vulkan(EngineState* state, AAssetManager* assetManager) {
    if (!state || !state->vulkan) return false;
    state->vulkanReady = state->vulkan->init(assetManager);
    return state->vulkanReady;
}

// =============================================================================
// Distance kernel dispatch
// =============================================================================

bool engine_compute_distances(
    EngineState*  state,
    const float*  speakerPositions,
    uint32_t      speakerCount,
    const float*  gridPoints,
    uint32_t      gridCount,
    float*        outDistances)
{
    if (!state || !speakerPositions || !gridPoints || !outDistances) return false;
    if (speakerCount == 0 || gridCount == 0) return false;

    // ── GPU path ──────────────────────────────────────────────────────────────
    if (state->vulkanReady && state->vulkan) {
        return state->vulkan->computeDistances(
            speakerPositions, speakerCount,
            gridPoints,       gridCount,
            outDistances
        );
    }

    // ── CPU fallback (also serves as the reference implementation for tests) ──
    for (uint32_t g = 0; g < gridCount; ++g) {
        const float px = gridPoints[g * 3 + 0];
        const float py = gridPoints[g * 3 + 1];
        const float pz = gridPoints[g * 3 + 2];

        for (uint32_t s = 0; s < speakerCount; ++s) {
            const float dx = px - speakerPositions[s * 3 + 0];
            const float dy = py - speakerPositions[s * 3 + 1];
            const float dz = pz - speakerPositions[s * 3 + 2];
            outDistances[g * speakerCount + s] = std::sqrt(dx*dx + dy*dy + dz*dz);
        }
    }
    return true;
}

} // namespace dac

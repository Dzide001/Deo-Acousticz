// =============================================================================
// test_inverse_square.cpp  —  Phase 4 acoustic formula validation
// =============================================================================
//
// CRITICAL: These tests are the acoustic correctness gatekeepers.
// If these fail, all heatmap output will be wrong.
//
// Reference values calculated by hand using the corrected SPL formula:
//   Lp = sensitivity + gain − 20·log10(distance)
//
// Test speaker: sensitivity = 100 dBSPL@1W/1m, input = 0 dBref (rated power)
//
// Expected values at various distances:
//   1 m  → 100 dB
//   2 m  →  94 dB  (−6 dB per doubling — inverse square law)
//   4 m  →  88 dB
//  10 m  →  80 dB
//  20 m  →  74 dB
// 100 m  →  60 dB

#include <gtest/gtest.h>
#include <cmath>
#include <vector>

#include "acoustics/direct.h"
#include "AcousticEngine.h"

using namespace dac;
using namespace dac::acoustics;

// =============================================================================
// directSPL() formula validation
// =============================================================================

TEST(InverseSquareLaw, At1m) {
    float spl = directSPL(100.f, 0.f, 1.f);
    EXPECT_NEAR(spl, 100.f, 0.01f);
}

TEST(InverseSquareLaw, At2m) {
    float spl = directSPL(100.f, 0.f, 2.f);
    // 100 − 20·log10(2) = 93.979 dB  ("−6 dB per doubling" is rounded; exact = 6.021 dB)
    EXPECT_NEAR(spl, 93.979f, 0.01f);
}

TEST(InverseSquareLaw, At4m) {
    float spl = directSPL(100.f, 0.f, 4.f);
    // 100 − 20·log10(4) = 87.959 dB
    EXPECT_NEAR(spl, 87.959f, 0.01f);
}

TEST(InverseSquareLaw, At10m) {
    float spl = directSPL(100.f, 0.f, 10.f);
    // 100 − 20·log10(10) = 100 − 20 = 80 dB
    EXPECT_NEAR(spl, 80.f, 0.01f);
}

TEST(InverseSquareLaw, At100m) {
    float spl = directSPL(100.f, 0.f, 100.f);
    // 100 − 20·log10(100) = 100 − 40 = 60 dB
    EXPECT_NEAR(spl, 60.f, 0.01f);
}

TEST(InverseSquareLaw, DoublingDistanceDrops6dB) {
    float s1 = directSPL(100.f, 0.f, 5.f);
    float s2 = directSPL(100.f, 0.f, 10.f);
    // 20·log10(2) = 6.021 dB; the "−6 dB per doubling" rule is a rounded approximation
    EXPECT_NEAR(s1 - s2, 6.021f, 0.01f);
}

TEST(InverseSquareLaw, InputGainAddsLinear) {
    float s_0dB = directSPL(100.f,  0.f, 10.f);
    float s_6dB = directSPL(100.f,  6.f, 10.f);
    float s_m6dB= directSPL(100.f, -6.f, 10.f);
    EXPECT_NEAR(s_6dB  - s_0dB, +6.f, 0.01f);
    EXPECT_NEAR(s_m6dB - s_0dB, -6.f, 0.01f);
}

TEST(InverseSquareLaw, VeryShortDistanceClamped) {
    // Should not crash or produce +Inf
    float spl = directSPL(100.f, 0.f, 0.0f);
    EXPECT_TRUE(std::isfinite(spl));
}

// =============================================================================
// splToEnergy / energyToSpl round-trip
// =============================================================================

TEST(EnergyConversion, RoundTrip) {
    float originalSPL = 94.3f;
    float energy      = splToEnergy(originalSPL);
    float recovered   = energyToSpl(energy);
    EXPECT_NEAR(recovered, originalSPL, 1e-4f);
}

TEST(EnergyConversion, TwoEqualSourcesAdd3dB) {
    float spl1 = 80.f, spl2 = 80.f;
    float spls[] = { spl1, spl2 };
    float total = sumIncoherent(spls, 2);
    EXPECT_NEAR(total, 83.f, 0.1f);  // +3 dB for equal incoherent sources
}

TEST(EnergyConversion, TenEqualSourcesAdd10dB) {
    float spls[10];
    for (auto& s : spls) s = 80.f;
    float total = sumIncoherent(spls, 10);
    EXPECT_NEAR(total, 90.f, 0.1f);  // +10 dB for 10 identical sources
}

// =============================================================================
// engine_compute_distances() — integration test (CPU path, no GPU required)
// =============================================================================

TEST(EngineDistances, CPUFallback_KnownDistances) {
    // Speaker at origin, grid point at (3, 4, 0) → distance = 5.0m
    float speakers[] = { 0.f, 0.f, 0.f };  // 1 speaker at origin
    float grid[]     = { 3.f, 4.f, 0.f };  // 1 point at (3,4,0)
    float output[1]  = { 0.f };

    EngineState* eng = engine_create();
    ASSERT_NE(eng, nullptr);

    // init_vulkan not called → CPU fallback automatically active
    bool ok = engine_compute_distances(eng, speakers, 1, grid, 1, output);
    EXPECT_TRUE(ok);
    EXPECT_NEAR(output[0], 5.f, 1e-4f);  // Pythagorean: √(9+16) = 5

    engine_destroy(eng);
}

TEST(EngineDistances, CPUFallback_MultiSpeaker) {
    // 2 speakers: one at origin, one at (10,0,0)
    float speakers[] = {
        0.f, 0.f, 0.f,
       10.f, 0.f, 0.f,
    };
    // Grid point at (5, 0, 0)
    float grid[]     = { 5.f, 0.f, 0.f };
    float output[2]  = { 0.f, 0.f };

    EngineState* eng = engine_create();
    ASSERT_NE(eng, nullptr);

    bool ok = engine_compute_distances(eng, speakers, 2, grid, 1, output);
    EXPECT_TRUE(ok);
    EXPECT_NEAR(output[0], 5.f, 1e-4f);  // Grid point is 5m from speaker 0
    EXPECT_NEAR(output[1], 5.f, 1e-4f);  // and 5m from speaker 1 (symmetry)

    engine_destroy(eng);
}

TEST(EngineDistances, CPUFallback_MultiGridPoints) {
    float speakers[] = { 0.f, 0.f, 0.f };  // 1 speaker at origin
    float grid[]     = {
        1.f, 0.f, 0.f,   // 1m
        2.f, 0.f, 0.f,   // 2m
        4.f, 0.f, 0.f,   // 4m
       10.f, 0.f, 0.f,   // 10m
    };
    float output[4] = {};

    EngineState* eng = engine_create();
    ASSERT_NE(eng, nullptr);

    bool ok = engine_compute_distances(eng, speakers, 1, grid, 4, output);
    EXPECT_TRUE(ok);
    EXPECT_NEAR(output[0],  1.f, 1e-4f);
    EXPECT_NEAR(output[1],  2.f, 1e-4f);
    EXPECT_NEAR(output[2],  4.f, 1e-4f);
    EXPECT_NEAR(output[3], 10.f, 1e-4f);

    engine_destroy(eng);
}

// =============================================================================
// arrivalTimeMs()
// =============================================================================

TEST(ArrivalTime, At343m_Is1000ms) {
    // Speed of sound = 343 m/s → 343m takes 1000ms
    float t = arrivalTimeMs(343.f);
    EXPECT_NEAR(t, 1000.f, 0.1f);
}

TEST(ArrivalTime, At34_3m_Is100ms) {
    float t = arrivalTimeMs(34.3f);
    EXPECT_NEAR(t, 100.f, 0.1f);
}

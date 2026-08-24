// =============================================================================
// test_summation.cpp  —  Incoherent SPL summation tests
// =============================================================================

#include <gtest/gtest.h>
#include "acoustics/direct.h"

using namespace dac::acoustics;

TEST(Summation, SingleSource) {
    float spls[] = { 85.f };
    EXPECT_NEAR(sumIncoherent(spls, 1), 85.f, 1e-4f);
}

TEST(Summation, TwoEqualSourcesGive3dBIncrease) {
    float spls[] = { 80.f, 80.f };
    EXPECT_NEAR(sumIncoherent(spls, 2), 83.01f, 0.01f);
}

TEST(Summation, TenEqualSourcesGive10dBIncrease) {
    float spls[10];
    for (auto& s : spls) s = 70.f;
    EXPECT_NEAR(sumIncoherent(spls, 10), 80.f, 0.05f);
}

TEST(Summation, DominantSourceResult) {
    // One source 20 dB higher than the other — result ≈ dominant source
    float spls[] = { 90.f, 70.f };
    float total = sumIncoherent(spls, 2);
    EXPECT_NEAR(total, 90.04f, 0.05f);  // 70 dB adds < 0.1 dB
}

TEST(Summation, EnergyConversionSymmetry) {
    float e = splToEnergy(94.f);
    EXPECT_NEAR(energyToSpl(e), 94.f, 1e-4f);
}

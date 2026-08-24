// =============================================================================
// test_biquad.cpp  —  DSP biquad filter coefficient validation (Phase 6 preview)
// =============================================================================
//
// Verifies that BiquadDesign coefficients produce the correct gain at the
// design frequency. Uses the Biquad::gainDB() analytical evaluator.

#include <gtest/gtest.h>
#include <cmath>
#include "dsp/biquad.h"

using namespace dac::dsp;
using namespace dac::dsp::BiquadDesign;

static constexpr float FS = 48000.f;  // 48 kHz — standard for processing

// ─── PEQ ─────────────────────────────────────────────────────────────────────

TEST(Biquad, PEQ_GainAtDesignFreq) {
    // +6 dB boost at 1 kHz, Q=0.707
    Biquad f = peq(1000.f, 6.f, 0.707f, FS);
    float g = f.gainDB(1000.f, FS);
    EXPECT_NEAR(g, 6.f, 0.05f);
}

TEST(Biquad, PEQ_CutAtDesignFreq) {
    // −6 dB cut at 500 Hz, Q=1.0
    Biquad f = peq(500.f, -6.f, 1.f, FS);
    float g = f.gainDB(500.f, FS);
    EXPECT_NEAR(g, -6.f, 0.05f);
}

TEST(Biquad, PEQ_ZeroGainIsUnity) {
    Biquad f = peq(1000.f, 0.f, 0.707f, FS);
    // At 0 dB gain, filter should pass through (gain ≈ 0 dB everywhere)
    EXPECT_NEAR(f.gainDB(1000.f, FS), 0.f, 0.01f);
    EXPECT_NEAR(f.gainDB(100.f,  FS), 0.f, 0.01f);
    EXPECT_NEAR(f.gainDB(8000.f, FS), 0.f, 0.01f);
}

// ─── High-pass ────────────────────────────────────────────────────────────────

TEST(Biquad, HighPass_AttenBelowCutoff) {
    // HPF at 200 Hz — should heavily attenuate at 20 Hz
    Biquad f = highPass(200.f, 0.707f, FS);
    float g  = f.gainDB(20.f, FS);
    EXPECT_LT(g, -30.f);  // Well below passband
}

TEST(Biquad, HighPass_PassAboveCutoff) {
    Biquad f = highPass(200.f, 0.707f, FS);
    float g  = f.gainDB(8000.f, FS);
    EXPECT_NEAR(g, 0.f, 0.5f);  // Near 0 dB well above cutoff
}

// ─── Low-pass ─────────────────────────────────────────────────────────────────

TEST(Biquad, LowPass_AttenAboveCutoff) {
    Biquad f = lowPass(5000.f, 0.707f, FS);
    float g  = f.gainDB(20000.f, FS);
    EXPECT_LT(g, -20.f);
}

TEST(Biquad, LowPass_PassBelowCutoff) {
    Biquad f = lowPass(5000.f, 0.707f, FS);
    float g  = f.gainDB(100.f, FS);
    EXPECT_NEAR(g, 0.f, 0.5f);
}

// ─── State reset ─────────────────────────────────────────────────────────────

TEST(Biquad, ResetClearsState) {
    Biquad f = peq(1000.f, 6.f, 0.707f, FS);
    // Process some samples to fill the delay line
    for (int i = 0; i < 100; ++i) f.process(1.f);
    f.reset();
    // After reset, process a single 0 sample — output should be 0
    float out = f.process(0.f);
    EXPECT_FLOAT_EQ(out, 0.f);
}

// =============================================================================
// dsp/biquad.h  —  Biquad (second-order IIR) filter
// =============================================================================
//
// Direct Form 1 implementation with coefficient calculation for all filter
// types needed by the DSP pipeline (Phase 6).
//
// References:
//   - Audio EQ Cookbook by Robert Bristow-Johnson
//   - https://webaudio.github.io/Audio-EQ-Cookbook/audio-eq-cookbook.html

#pragma once
#include <cmath>

#ifndef M_PI
#define M_PI 3.14159265358979323846
#endif

namespace dac {
namespace dsp {

// ─── Biquad filter ────────────────────────────────────────────────────────────

struct Biquad {
    // Feed-forward and feedback coefficients
    float b0 = 1.f, b1 = 0.f, b2 = 0.f;  // Numerator
    float a1 = 0.f, a2 = 0.f;             // Denominator (a0 normalised to 1.0)

    // Delay line — Direct Form 1
    float x1 = 0.f, x2 = 0.f;  // Input history
    float y1 = 0.f, y2 = 0.f;  // Output history

    /// Process one sample.
    inline float process(float in) noexcept {
        float out = b0*in + b1*x1 + b2*x2 - a1*y1 - a2*y2;
        x2 = x1;  x1 = in;
        y2 = y1;  y1 = out;
        return out;
    }

    /// Reset delay line without changing coefficients.
    void reset() noexcept { x1=x2=y1=y2=0.f; }

    // ─── Gain in dB at a given frequency ─────────────────────────────────────
    // Used for DSP frequency-domain application (Phase 6).
    // Returns gain in dB at the specified frequency.
    float gainDB(float freq, float sampleRate) const noexcept {
        float w  = 2.f * static_cast<float>(M_PI) * freq / sampleRate;
        // Evaluate magnitude of H(e^jw) = (b0 + b1*z^-1 + b2*z^-2) /
        //                                 (1  + a1*z^-1 + a2*z^-2)
        float cosw  = std::cos(w),  sinw  = std::sin(w);
        float cos2w = std::cos(2*w), sin2w = std::sin(2*w);

        float numR = b0 + b1*cosw + b2*cos2w;
        float numI =      b1*sinw + b2*sin2w;
        float denR = 1.f + a1*cosw + a2*cos2w;
        float denI =        a1*sinw + a2*sin2w;

        float num2 = numR*numR + numI*numI;
        float den2 = denR*denR + denI*denI;
        if (den2 < 1e-30f) return 0.f;
        return 10.f * std::log10(num2 / den2);
    }
};

// ─── Filter designers ─────────────────────────────────────────────────────────

namespace BiquadDesign {

/// Parametric EQ (peaking) band.
inline Biquad peq(float freq, float gainDB, float q, float sampleRate) noexcept {
    float w0   = 2.f * static_cast<float>(M_PI) * freq / sampleRate;
    float A    = std::pow(10.f, gainDB / 40.f);
    float cosw = std::cos(w0);
    float sinw = std::sin(w0);
    float alpha = sinw / (2.f * q);

    Biquad f;
    float a0 = 1.f + alpha / A;
    f.b0 = (1.f + alpha * A) / a0;
    f.b1 = (-2.f * cosw)     / a0;
    f.b2 = (1.f - alpha * A) / a0;
    f.a1 = f.b1;
    f.a2 = (1.f - alpha / A) / a0;
    return f;
}

/// Low-shelf filter.
inline Biquad lowShelf(float freq, float gainDB, float q, float sampleRate) noexcept {
    float w0   = 2.f * static_cast<float>(M_PI) * freq / sampleRate;
    float A    = std::pow(10.f, gainDB / 40.f);
    float cosw = std::cos(w0);
    float sinw = std::sin(w0);
    float alpha = sinw / 2.f * std::sqrt((A + 1.f/A) * (1.f/q - 1.f) + 2.f);

    float sqA   = std::sqrt(A);
    float ap1   = A + 1.f, am1 = A - 1.f;

    Biquad f;
    float a0 = (ap1 + am1*cosw) + 2.f*sqA*alpha;
    f.b0 =  A * ((ap1 - am1*cosw) + 2.f*sqA*alpha) / a0;
    f.b1 =  2.f*A * (am1 - ap1*cosw)               / a0;
    f.b2 =  A * ((ap1 - am1*cosw) - 2.f*sqA*alpha) / a0;
    f.a1 = -2.f * (am1 + ap1*cosw)                  / a0;
    f.a2 = ((ap1 + am1*cosw) - 2.f*sqA*alpha)       / a0;
    return f;
}

/// High-shelf filter.
inline Biquad highShelf(float freq, float gainDB, float q, float sampleRate) noexcept {
    float w0   = 2.f * static_cast<float>(M_PI) * freq / sampleRate;
    float A    = std::pow(10.f, gainDB / 40.f);
    float cosw = std::cos(w0);
    float sinw = std::sin(w0);
    float alpha = sinw / 2.f * std::sqrt((A + 1.f/A) * (1.f/q - 1.f) + 2.f);

    float sqA   = std::sqrt(A);
    float ap1   = A + 1.f, am1 = A - 1.f;

    Biquad f;
    float a0 = (ap1 - am1*cosw) + 2.f*sqA*alpha;
    f.b0 =  A * ((ap1 + am1*cosw) + 2.f*sqA*alpha) / a0;
    f.b1 = -2.f*A * (am1 + ap1*cosw)               / a0;
    f.b2 =  A * ((ap1 + am1*cosw) - 2.f*sqA*alpha) / a0;
    f.a1 =  2.f * (am1 - ap1*cosw)                  / a0;
    f.a2 = ((ap1 - am1*cosw) - 2.f*sqA*alpha)       / a0;
    return f;
}

/// 2nd-order Butterworth high-pass filter.
inline Biquad highPass(float freq, float q, float sampleRate) noexcept {
    float w0    = 2.f * static_cast<float>(M_PI) * freq / sampleRate;
    float cosw  = std::cos(w0);
    float sinw  = std::sin(w0);
    float alpha = sinw / (2.f * q);

    Biquad f;
    float a0 =  1.f + alpha;
    f.b0 =  (1.f + cosw) / 2.f / a0;
    f.b1 = -(1.f + cosw)       / a0;
    f.b2 =  (1.f + cosw) / 2.f / a0;
    f.a1 = -2.f * cosw         / a0;
    f.a2 =  (1.f - alpha)      / a0;
    return f;
}

/// 2nd-order Butterworth low-pass filter.
inline Biquad lowPass(float freq, float q, float sampleRate) noexcept {
    float w0    = 2.f * static_cast<float>(M_PI) * freq / sampleRate;
    float cosw  = std::cos(w0);
    float sinw  = std::sin(w0);
    float alpha = sinw / (2.f * q);

    Biquad f;
    float a0 = 1.f + alpha;
    f.b0 = (1.f - cosw) / 2.f / a0;
    f.b1 = (1.f - cosw)       / a0;
    f.b2 = (1.f - cosw) / 2.f / a0;
    f.a1 = -2.f * cosw        / a0;
    f.a2 = (1.f - alpha)      / a0;
    return f;
}

} // namespace BiquadDesign
} // namespace dsp
} // namespace dac

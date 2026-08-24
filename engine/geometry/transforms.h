// =============================================================================
// geometry/transforms.h  —  Speaker world↔local coordinate transforms
// =============================================================================
//
// Converts world-space points into a speaker's local coordinate system so that
// horizontal and vertical angles can be computed for directivity look-up.
//
// Convention:
//   Speaker local coordinate system:
//     +Z = forward (main axis / on-axis direction)
//     +Y = up
//     +X = right (from speaker's perspective)
//
// Usage sequence (Phase 3+):
//   TransformEngine te;
//   te.setSpeakerTransform(position, yawDeg, pitchDeg);
//   auto local = te.toSpeakerLocal(worldPoint);
//   float horzDeg = te.horizontalAngle(local);  // for directivity interpolation
//   float vertDeg = te.verticalAngle(local);

#pragma once
#include "vector3.h"
#include "matrix4.h"
#include <cmath>

#ifndef M_PI
#define M_PI 3.14159265358979323846f
#endif

namespace dac {

class TransformEngine {
public:
    TransformEngine() : mWorldToSpeaker(Matrix4::identity()) {}

    // ─── Setup ────────────────────────────────────────────────────────────────

    /// Build the world→speaker transform from position and Euler angles.
    /// @param position   Speaker location in world space (metres).
    /// @param yawDeg     Horizontal rotation around the world Y axis.
    /// @param pitchDeg   Vertical tilt (positive = tilts forward/down).
    void setSpeakerTransform(const Vector3& position, float yawDeg, float pitchDeg) {
        // World → local:  translate (−position) then rotate inverse(yaw × pitch)
        // Rotation order: pitch (X) applied first, then yaw (Y) — ZXY Euler
        float yawRad   = yawDeg   * static_cast<float>(M_PI) / 180.f;
        float pitchRad = pitchDeg * static_cast<float>(M_PI) / 180.f;

        Matrix4 R = Matrix4::rotationY(yawRad) * Matrix4::rotationX(pitchRad);
        Matrix4 T = Matrix4::translation(-position.x, -position.y, -position.z);

        // Combine: first translate to origin, then rotate into speaker-local frame
        mWorldToSpeaker = R.transposed() * T;
    }

    // ─── Transform ────────────────────────────────────────────────────────────

    /// Transform a world-space point into the speaker's local coordinate system.
    Vector3 toSpeakerLocal(const Vector3& worldPoint) const noexcept {
        return mWorldToSpeaker.transformPoint(worldPoint);
    }

    // ─── Angle computation ────────────────────────────────────────────────────

    /// Horizontal off-axis angle from a point in speaker-local space (degrees).
    /// 0° = on-axis forward (+Z).  Positive = right.
    static float horizontalAngle(const Vector3& local) noexcept {
        // Project onto the XZ plane and measure angle from +Z
        float rad = std::atan2(local.x, local.z);
        return rad * 180.f / static_cast<float>(M_PI);
    }

    /// Vertical off-axis angle from a point in speaker-local space (degrees).
    /// 0° = on-axis.  Positive = above axis.
    static float verticalAngle(const Vector3& local) noexcept {
        float horzDist = std::sqrt(local.x*local.x + local.z*local.z);
        float rad      = std::atan2(local.y, horzDist);
        return rad * 180.f / static_cast<float>(M_PI);
    }

private:
    Matrix4 mWorldToSpeaker;
};

} // namespace dac

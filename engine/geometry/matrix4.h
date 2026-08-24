// =============================================================================
// geometry/matrix4.h  —  4×4 column-major matrix
// =============================================================================
//
// Used for speaker world/local transforms, camera projection, and 3D picking.
// Stored in column-major order (OpenGL / Vulkan / Filament convention).

#pragma once
#include "vector3.h"
#include <cmath>
#include <array>
#include <string>
#include <sstream>

namespace dac {

struct Matrix4 {
    // ─── Storage: column-major ────────────────────────────────────────────────
    // m[col][row]  →  m[0] = first column, etc.
    float m[4][4];

    // ─── Constructors ─────────────────────────────────────────────────────────
    Matrix4() noexcept { setIdentity(); }

    // ─── Factory methods ──────────────────────────────────────────────────────
    static Matrix4 identity() noexcept {
        Matrix4 r;
        r.setIdentity();
        return r;
    }

    /// Build from 16 floats in column-major order.
    static Matrix4 fromColMajor(const float* d) noexcept {
        Matrix4 r;
        for (int c = 0; c < 4; ++c)
            for (int row = 0; row < 4; ++row)
                r.m[c][row] = d[c*4 + row];
        return r;
    }

    // Translation
    static Matrix4 translation(float tx, float ty, float tz) noexcept {
        Matrix4 r;
        r.m[3][0] = tx;
        r.m[3][1] = ty;
        r.m[3][2] = tz;
        return r;
    }

    // Uniform scale
    static Matrix4 scale(float s) noexcept {
        Matrix4 r;
        r.m[0][0] = r.m[1][1] = r.m[2][2] = s;
        return r;
    }

    // Rotation around Y axis (yaw) — most common speaker orientation
    static Matrix4 rotationY(float radians) noexcept {
        Matrix4 r;
        float c = std::cos(radians), s = std::sin(radians);
        r.m[0][0] =  c;  r.m[2][0] = s;
        r.m[0][2] = -s;  r.m[2][2] = c;
        return r;
    }

    // Rotation around X axis (pitch / tilt)
    static Matrix4 rotationX(float radians) noexcept {
        Matrix4 r;
        float c = std::cos(radians), s = std::sin(radians);
        r.m[1][1] =  c;  r.m[2][1] = -s;
        r.m[1][2] =  s;  r.m[2][2] =  c;
        return r;
    }

    // Rotation around Z axis (roll)
    static Matrix4 rotationZ(float radians) noexcept {
        Matrix4 r;
        float c = std::cos(radians), s = std::sin(radians);
        r.m[0][0] =  c;  r.m[1][0] = -s;
        r.m[0][1] =  s;  r.m[1][1] =  c;
        return r;
    }

    // ─── Multiplication ───────────────────────────────────────────────────────
    Matrix4 operator*(const Matrix4& b) const noexcept {
        Matrix4 r;
        for (int c = 0; c < 4; ++c) {
            for (int row = 0; row < 4; ++row) {
                r.m[c][row] = 0.f;
                for (int k = 0; k < 4; ++k) {
                    r.m[c][row] += m[k][row] * b.m[c][k];
                }
            }
        }
        return r;
    }

    // ─── Transform a point (w=1) ──────────────────────────────────────────────
    Vector3 transformPoint(const Vector3& p) const noexcept {
        float x = m[0][0]*p.x + m[1][0]*p.y + m[2][0]*p.z + m[3][0];
        float y = m[0][1]*p.x + m[1][1]*p.y + m[2][1]*p.z + m[3][1];
        float z = m[0][2]*p.x + m[1][2]*p.y + m[2][2]*p.z + m[3][2];
        float w = m[0][3]*p.x + m[1][3]*p.y + m[2][3]*p.z + m[3][3];
        if (std::fabs(w) > 1e-8f) return {x/w, y/w, z/w};
        return {x, y, z};
    }

    // Transform a direction (w=0) — no translation applied
    Vector3 transformDirection(const Vector3& d) const noexcept {
        return {
            m[0][0]*d.x + m[1][0]*d.y + m[2][0]*d.z,
            m[0][1]*d.x + m[1][1]*d.y + m[2][1]*d.z,
            m[0][2]*d.x + m[1][2]*d.y + m[2][2]*d.z
        };
    }

    // ─── Transpose ────────────────────────────────────────────────────────────
    Matrix4 transposed() const noexcept {
        Matrix4 r;
        for (int c = 0; c < 4; ++c)
            for (int row = 0; row < 4; ++row)
                r.m[row][c] = m[c][row];
        return r;
    }

    // ─── Equality (epsilon) ───────────────────────────────────────────────────
    bool equals(const Matrix4& b, float eps = 1e-5f) const noexcept {
        for (int c = 0; c < 4; ++c)
            for (int row = 0; row < 4; ++row)
                if (std::fabs(m[c][row] - b.m[c][row]) > eps) return false;
        return true;
    }

    // ─── Debug ────────────────────────────────────────────────────────────────
    std::string toString() const {
        std::ostringstream ss;
        for (int row = 0; row < 4; ++row) {
            ss << "[ ";
            for (int c = 0; c < 4; ++c)
                ss << m[c][row] << " ";
            ss << "]\n";
        }
        return ss.str();
    }

private:
    void setIdentity() noexcept {
        for (int c = 0; c < 4; ++c)
            for (int row = 0; row < 4; ++row)
                m[c][row] = (c == row) ? 1.f : 0.f;
    }
};

} // namespace dac

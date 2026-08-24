// =============================================================================
// geometry/vector3.h  —  3D vector maths
// =============================================================================
//
// Intentionally not using GLM here so that the geometry types are visible and
// understandable as plain C++. GLM is used in the GPU path (Vulkan shaders) and
// for matrix operations. These types are the bread-and-butter of the engine.

#pragma once
#include <cmath>
#include <string>
#include <sstream>

namespace dac {

struct Vector3 {
    float x, y, z;

    // ─── Constructors ─────────────────────────────────────────────────────────
    constexpr Vector3()                         noexcept : x(0), y(0), z(0) {}
    constexpr Vector3(float x, float y, float z) noexcept : x(x), y(y), z(z) {}

    // ─── Arithmetic ───────────────────────────────────────────────────────────
    constexpr Vector3 operator+(const Vector3& r) const noexcept { return {x+r.x, y+r.y, z+r.z}; }
    constexpr Vector3 operator-(const Vector3& r) const noexcept { return {x-r.x, y-r.y, z-r.z}; }
    constexpr Vector3 operator*(float s)           const noexcept { return {x*s, y*s, z*s}; }
    constexpr Vector3 operator/(float s)           const noexcept { return {x/s, y/s, z/s}; }
    constexpr Vector3 operator-()                  const noexcept { return {-x, -y, -z}; }

    Vector3& operator+=(const Vector3& r) noexcept { x+=r.x; y+=r.y; z+=r.z; return *this; }
    Vector3& operator-=(const Vector3& r) noexcept { x-=r.x; y-=r.y; z-=r.z; return *this; }
    Vector3& operator*=(float s)          noexcept { x*=s;   y*=s;   z*=s;   return *this; }

    // ─── Comparison (epsilon-based, used in unit tests) ───────────────────────
    bool equals(const Vector3& r, float eps = 1e-5f) const noexcept {
        return std::fabs(x-r.x) < eps
            && std::fabs(y-r.y) < eps
            && std::fabs(z-r.z) < eps;
    }

    // ─── Length & normalisation ───────────────────────────────────────────────
    float lengthSq() const noexcept { return x*x + y*y + z*z; }
    float length()   const noexcept { return std::sqrt(lengthSq()); }

    Vector3 normalised() const noexcept {
        float len = length();
        return (len > 1e-8f) ? (*this / len) : Vector3{};
    }

    // ─── Products ─────────────────────────────────────────────────────────────
    static float dot(const Vector3& a, const Vector3& b) noexcept {
        return a.x*b.x + a.y*b.y + a.z*b.z;
    }

    static Vector3 cross(const Vector3& a, const Vector3& b) noexcept {
        return {
            a.y*b.z - a.z*b.y,
            a.z*b.x - a.x*b.z,
            a.x*b.y - a.y*b.x
        };
    }

    // ─── Geometry helpers ─────────────────────────────────────────────────────

    /// Euclidean distance to another point.
    float distanceTo(const Vector3& other) const noexcept {
        return (*this - other).length();
    }

    /// Angle between this and another vector (radians, [0, π]).
    float angleTo(const Vector3& other) const noexcept {
        float denom = length() * other.length();
        if (denom < 1e-8f) return 0.f;
        float c = dot(*this, other) / denom;
        // Clamp to [-1,1] to guard against floating-point rounding
        c = c < -1.f ? -1.f : (c > 1.f ? 1.f : c);
        return std::acos(c);
    }

    // ─── Debug ────────────────────────────────────────────────────────────────
    std::string toString() const {
        std::ostringstream ss;
        ss << "(" << x << ", " << y << ", " << z << ")";
        return ss.str();
    }
};

inline Vector3 operator*(float s, const Vector3& v) noexcept { return v * s; }

} // namespace dac

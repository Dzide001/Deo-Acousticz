// =============================================================================
// test_geometry.cpp  —  Phase 0 unit tests for Vector3, Matrix4, TransformEngine
// =============================================================================
//
// Run on CI (desktop, no GPU required).
// Pass/fail criteria:
//   All assertions green, no sanitiser errors, execution < 1 second.

#include <gtest/gtest.h>
#include <cmath>

#include "geometry/vector3.h"
#include "geometry/matrix4.h"
#include "geometry/transforms.h"

using namespace dac;

#ifndef M_PI
#define M_PI 3.14159265358979323846
#endif

// =============================================================================
// Vector3
// =============================================================================

TEST(Vector3, DefaultConstructorIsZero) {
    Vector3 v;
    EXPECT_FLOAT_EQ(v.x, 0.f);
    EXPECT_FLOAT_EQ(v.y, 0.f);
    EXPECT_FLOAT_EQ(v.z, 0.f);
}

TEST(Vector3, Addition) {
    Vector3 a{1, 2, 3}, b{4, 5, 6};
    Vector3 c = a + b;
    EXPECT_FLOAT_EQ(c.x, 5.f);
    EXPECT_FLOAT_EQ(c.y, 7.f);
    EXPECT_FLOAT_EQ(c.z, 9.f);
}

TEST(Vector3, Subtraction) {
    Vector3 a{5, 3, 1}, b{1, 1, 1};
    auto c = a - b;
    EXPECT_FLOAT_EQ(c.x, 4.f);
    EXPECT_FLOAT_EQ(c.y, 2.f);
    EXPECT_FLOAT_EQ(c.z, 0.f);
}

TEST(Vector3, ScalarMultiply) {
    Vector3 v{1, 2, 3};
    auto r = v * 3.f;
    EXPECT_FLOAT_EQ(r.x, 3.f);
    EXPECT_FLOAT_EQ(r.y, 6.f);
    EXPECT_FLOAT_EQ(r.z, 9.f);
}

TEST(Vector3, LengthUnit) {
    Vector3 v{1, 0, 0};
    EXPECT_NEAR(v.length(), 1.f, 1e-6f);
}

TEST(Vector3, Length3D) {
    // 3-4-5 Pythagorean triple extended to 3D
    Vector3 v{3, 4, 0};
    EXPECT_NEAR(v.length(), 5.f, 1e-5f);
}

TEST(Vector3, Normalise) {
    Vector3 v{3, 4, 0};
    Vector3 n = v.normalised();
    EXPECT_NEAR(n.length(), 1.f, 1e-6f);
    EXPECT_NEAR(n.x, 0.6f, 1e-5f);
    EXPECT_NEAR(n.y, 0.8f, 1e-5f);
}

TEST(Vector3, NormaliseZeroVector) {
    // Should not crash or produce NaN
    Vector3 z;
    Vector3 n = z.normalised();
    EXPECT_FLOAT_EQ(n.x, 0.f);
    EXPECT_FLOAT_EQ(n.y, 0.f);
    EXPECT_FLOAT_EQ(n.z, 0.f);
}

TEST(Vector3, DotProduct) {
    Vector3 a{1,0,0}, b{0,1,0};
    EXPECT_FLOAT_EQ(Vector3::dot(a, b), 0.f);  // perpendicular

    Vector3 c{1,0,0}, d{1,0,0};
    EXPECT_FLOAT_EQ(Vector3::dot(c, d), 1.f);  // parallel (unit vectors)
}

TEST(Vector3, CrossProduct) {
    Vector3 x{1,0,0}, y{0,1,0};
    Vector3 z = Vector3::cross(x, y);
    EXPECT_NEAR(z.x, 0.f, 1e-6f);
    EXPECT_NEAR(z.y, 0.f, 1e-6f);
    EXPECT_NEAR(z.z, 1.f, 1e-6f);
}

TEST(Vector3, DistanceTo) {
    Vector3 a{0, 0, 0}, b{3, 4, 0};
    EXPECT_NEAR(a.distanceTo(b), 5.f, 1e-5f);
}

TEST(Vector3, AngleTo90Degrees) {
    Vector3 a{1, 0, 0}, b{0, 1, 0};
    float deg = a.angleTo(b) * 180.f / static_cast<float>(M_PI);
    EXPECT_NEAR(deg, 90.f, 1e-4f);
}

// =============================================================================
// Matrix4
// =============================================================================

TEST(Matrix4, IdentityTransformPoint) {
    Matrix4 I = Matrix4::identity();
    Vector3 p{3, 4, 5};
    Vector3 r = I.transformPoint(p);
    EXPECT_TRUE(r.equals(p));
}

TEST(Matrix4, TranslationTransformPoint) {
    Matrix4 T = Matrix4::translation(1, 2, 3);
    Vector3 p{0, 0, 0};
    Vector3 r = T.transformPoint(p);
    EXPECT_NEAR(r.x, 1.f, 1e-5f);
    EXPECT_NEAR(r.y, 2.f, 1e-5f);
    EXPECT_NEAR(r.z, 3.f, 1e-5f);
}

TEST(Matrix4, TranslationDoesNotAffectDirection) {
    Matrix4 T = Matrix4::translation(100, 200, 300);
    Vector3 d{0, 0, 1};
    Vector3 r = T.transformDirection(d);
    EXPECT_TRUE(r.equals(d));
}

TEST(Matrix4, RotationY90) {
    // Rotating +X by 90° around Y should give +Z (right-hand rule)
    float rad = static_cast<float>(M_PI) / 2.f;
    Matrix4 R = Matrix4::rotationY(rad);
    Vector3 x{1, 0, 0};
    Vector3 r = R.transformPoint(x);
    EXPECT_NEAR(r.x,  0.f, 1e-5f);
    EXPECT_NEAR(r.y,  0.f, 1e-5f);
    EXPECT_NEAR(r.z, -1.f, 1e-5f);  // Column-major RH: +X rotated +90° Y → −Z
}

TEST(Matrix4, MultiplyByIdentity) {
    Matrix4 A = Matrix4::translation(3, 1, 4);
    Matrix4 B = A * Matrix4::identity();
    EXPECT_TRUE(A.equals(B));
}

TEST(Matrix4, Transpose) {
    Matrix4 T = Matrix4::translation(1, 2, 3);
    Matrix4 Tt = T.transposed();
    // The transpose of a translation matrix has the translation in the bottom row
    EXPECT_NEAR(Tt.m[0][3], 1.f, 1e-5f);
    EXPECT_NEAR(Tt.m[1][3], 2.f, 1e-5f);
    EXPECT_NEAR(Tt.m[2][3], 3.f, 1e-5f);
}

// =============================================================================
// TransformEngine
// =============================================================================

TEST(TransformEngine, OnAxisPointAtOriginFacing) {
    TransformEngine te;
    // Speaker at (0,0,0) facing +Z (yaw=0, pitch=0)
    te.setSpeakerTransform({0,0,0}, 0.f, 0.f);

    // A point directly in front on the speaker axis
    Vector3 local = te.toSpeakerLocal({0, 0, 10});
    float horzDeg = TransformEngine::horizontalAngle(local);
    float vertDeg = TransformEngine::verticalAngle(local);

    EXPECT_NEAR(horzDeg, 0.f, 0.1f);
    EXPECT_NEAR(vertDeg, 0.f, 0.1f);
}

TEST(TransformEngine, HorizontalOffset45Degrees) {
    TransformEngine te;
    te.setSpeakerTransform({0,0,0}, 0.f, 0.f);

    // Point at 45° to the right (+X) and same distance forward (+Z)
    Vector3 local = te.toSpeakerLocal({10, 0, 10});
    float horzDeg = TransformEngine::horizontalAngle(local);

    EXPECT_NEAR(std::fabs(horzDeg), 45.f, 0.5f);
}

# Build Instructions — DroidAcoustic Pro

## Prerequisites

| Tool | Version | Source |
|---|---|---|
| Android Studio | Meerkat 2024.3+ | developer.android.com/studio |
| Android NDK | 27.0.12077973 | SDK Manager → SDK Tools → NDK |
| CMake | 3.22.1 | SDK Manager → SDK Tools → CMake |
| JDK | 17 | Bundled with Android Studio |
| Git | Any | git-scm.com |

### Install NDK + CMake via SDK Manager

In Android Studio: **Tools → SDK Manager → SDK Tools → check "NDK (Side by side)" and "CMake"**

Then verify they're installed at:
```
~/Library/Android/sdk/ndk/27.0.12077973/
~/Library/Android/sdk/cmake/3.22.1/
```

---

## Opening the Android Project

1. Clone the repo:
   ```
   git clone https://github.com/yourorg/droidacoustic.git
   cd droidacoustic
   ```

2. Open **Android Studio → Open → select `droidacoustic/android/`**

3. Let Gradle sync complete (first sync downloads GLM and GTest via FetchContent — needs internet).

4. Connect the OnePlus Pad 3 via USB with **USB Debugging enabled**.

5. Press **Run** (▶) — Android Studio builds, deploys, and launches the app.

---

## Running C++ Unit Tests Locally (macOS)

These tests run on your Mac — no device or GPU needed.

```bash
cd droidacoustic

# Configure
cmake -S engine -B build/engine -DCMAKE_BUILD_TYPE=Debug

# Build (first run downloads GLM + GTest via FetchContent)
cmake --build build/engine --parallel 4

# Run all tests
cd build/engine/tests && ./dac_tests

# Or run with verbose output
./dac_tests --gtest_output=xml:results.xml
```

### Expected output (Phase 0 — all green)

```
[==========] Running N tests from M test suites.
[----------] Tests for Vector3
[ RUN      ] Vector3.DefaultConstructorIsZero ... OK
...
[----------] Tests for InverseSquareLaw
[ RUN      ] InverseSquareLaw.At1m            ... OK
[ RUN      ] InverseSquareLaw.At2m            ... OK
...
[  PASSED  ] N tests.
```

---

## Shader Compilation (Vulkan SPIR-V)

The GLSL compute shaders in `shaders/` are automatically compiled to SPIR-V
by `glslc` during the Android CMake build. The output goes to:

```
android/app/src/main/assets/shaders/distance.spv
```

**To manually compile (for inspection or debugging):**

```bash
# Path to glslc in the NDK (adjust NDK version as needed):
GLSLC=~/Library/Android/sdk/ndk/27.0.12077973/shader-tools/darwin-x86_64/glslc

$GLSLC --target-env=vulkan1.1 shaders/distance.comp -o shaders/distance.spv
```

If `glslc` is not found, Vulkan compute is unavailable and the engine
automatically falls back to the CPU path — the app still works.

---

## Project Structure Quick Reference

```
droidacoustic/
├── android/              Android project (Kotlin + Compose + NDK)
│   └── app/src/main/
│       ├── cpp/          JNI bridge + CMakeLists.txt
│       ├── kotlin/       Kotlin source
│       └── assets/       Runtime assets (SPIR-V shaders compiled here)
├── engine/               Portable C++17 acoustic engine
│   ├── AcousticEngine.h  JNI bridge CONTRACT — never change without bumping version
│   ├── geometry/         Vector3, Matrix4, TransformEngine
│   ├── acoustics/        direct.h — SPL formulas
│   ├── dsp/              biquad.h — filters (Phase 6)
│   └── gpu/              Vulkan compute (Android) + CPU stub (desktop/CI)
├── shaders/              GLSL compute shaders
├── tests/                gtest unit tests — run on desktop, no GPU needed
└── data/speakers/        Seed speaker JSON files
```

---

## OnePlus Pad 3 — Specific Setup

1. **Enable Developer Options:** Settings → About tablet → tap Build number 7×
2. **Enable USB Debugging:** Developer Options → USB debugging → On
3. **Enable USB Debugging (Security):** same section → Allow ADB authorisation
4. **Select USB mode:** When connecting cable, choose "File Transfer" or "MTP"
5. Android Studio should detect it as `OnePlus Pad 3 (arm64-v8a)`

---

## Troubleshooting

| Problem | Solution |
|---|---|
| Gradle sync fails | Check internet connection — FetchContent needs GitHub access |
| NDK not found | SDK Manager → SDK Tools → NDK (Side by side) → install 27.0.12077973 |
| `glslc not found` warning | GPU compute disabled, CPU fallback runs — see Shader Compilation above |
| Vulkan init FAILED in logcat | Check device supports Vulkan 1.1: Settings → About → Kernel info |
| App crashes on launch | Check Logcat for "JniBridge" tag — likely native lib load failure |
| gtest build fails | Delete `build/engine/` and re-run cmake — stale CMake cache |

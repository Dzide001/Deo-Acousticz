cmake_minimum_required(VERSION 3.22.1)
project(DroidAcousticPro CXX)

# =============================================================================
# Paths — ENGINE_DIR and SHADERS_DIR are injected by app/build.gradle.kts
# =============================================================================

if(NOT DEFINED ENGINE_DIR)
    message(FATAL_ERROR "ENGINE_DIR must be set by Gradle. Check app/build.gradle.kts.")
endif()
if(NOT DEFINED SHADERS_DIR)
    message(FATAL_ERROR "SHADERS_DIR must be set by Gradle. Check app/build.gradle.kts.")
endif()

message(STATUS "ENGINE_DIR  = ${ENGINE_DIR}")
message(STATUS "SHADERS_DIR = ${SHADERS_DIR}")

# =============================================================================
# GLM — header-only mathematics library (vectors, matrices, trig)
# =============================================================================

include(FetchContent)
FetchContent_Declare(
    glm
    GIT_REPOSITORY https://github.com/g-truc/glm.git
    GIT_TAG        1.0.1
    GIT_SHALLOW    TRUE
)
# GLM is header-only — MakeAvailable just sets up the include path.
FetchContent_MakeAvailable(glm)

# =============================================================================
# Vulkan SPIR-V shader compilation
# =============================================================================
# glslc is shipped with the Android NDK inside shader-tools/.
# We locate it automatically so the build is hermetic (no PATH dependency).

# Prefer CMake-provided NDK path, then Gradle-provided, then environment.
set(_DAC_NDK_ROOT "")
if(DEFINED CMAKE_ANDROID_NDK AND NOT "${CMAKE_ANDROID_NDK}" STREQUAL "")
    set(_DAC_NDK_ROOT "${CMAKE_ANDROID_NDK}")
elseif(DEFINED ANDROID_NDK AND NOT "${ANDROID_NDK}" STREQUAL "")
    set(_DAC_NDK_ROOT "${ANDROID_NDK}")
elseif(DEFINED ENV{ANDROID_NDK_HOME} AND NOT "$ENV{ANDROID_NDK_HOME}" STREQUAL "")
    set(_DAC_NDK_ROOT "$ENV{ANDROID_NDK_HOME}")
endif()

if(CMAKE_HOST_SYSTEM_NAME STREQUAL "Darwin")
    # Apple Silicon and Intel Macs both get the darwin-x86_64 binary via Rosetta.
    set(_GLSLC_HINTS
        "${_DAC_NDK_ROOT}/shader-tools/darwin-aarch64"
        "${_DAC_NDK_ROOT}/shader-tools/darwin-x86_64"
        "${ANDROID_SDK_ROOT}/ndk-bundle/shader-tools/darwin-x86_64"
    )
elseif(CMAKE_HOST_SYSTEM_NAME STREQUAL "Windows")
    set(_GLSLC_HINTS
        "${_DAC_NDK_ROOT}/shader-tools/windows-x86_64"
    )
else()
    set(_GLSLC_HINTS
        "${_DAC_NDK_ROOT}/shader-tools/linux-x86_64"
    )
endif()

find_program(GLSLC NAMES glslc glslc.exe HINTS ${_GLSLC_HINTS})
if(NOT GLSLC)
    message(WARNING
        "glslc not found — SPIR-V shaders will NOT be compiled. "
        "Vulkan compute will be unavailable; the CPU fallback will run instead. "
        "Install the Android NDK shader-tools component to enable GPU compute. "
        "Tried NDK root: ${_DAC_NDK_ROOT}")
    set(SHADERS_AVAILABLE FALSE)
else()
    message(STATUS "glslc found: ${GLSLC}")
    set(SHADERS_AVAILABLE TRUE)
endif()

if(SHADERS_AVAILABLE)
    set(DIST_COMP  "${SHADERS_DIR}/distance.comp")
    set(DIST_SPV   "${CMAKE_CURRENT_SOURCE_DIR}/../assets/shaders/distance.spv")

    # Ensure the assets/shaders directory exists
    file(MAKE_DIRECTORY "${CMAKE_CURRENT_SOURCE_DIR}/../assets/shaders")

    add_custom_command(
        OUTPUT  "${DIST_SPV}"
        COMMAND "${GLSLC}" --target-env=vulkan1.1 "${DIST_COMP}" -o "${DIST_SPV}"
        DEPENDS "${DIST_COMP}"
        COMMENT "Compiling distance.comp → distance.spv"
        VERBATIM
    )
    add_custom_target(compile_shaders ALL DEPENDS "${DIST_SPV}")
endif()

# =============================================================================
# Engine C++ sources
# =============================================================================

set(ENGINE_SOURCES
    "${ENGINE_DIR}/AcousticEngine.cpp"
    "${ENGINE_DIR}/gpu/vulkan_compute.cpp"
    "${ENGINE_DIR}/dsp/biquad.cpp"
    # Phase 1+: geometry sources (header-only for now)
    # Phase 4+: "${ENGINE_DIR}/acoustics/direct.cpp"
)

# =============================================================================
# JNI shared library — loaded by System.loadLibrary("droidacoustic")
# =============================================================================

add_library(droidacoustic SHARED
    jni_bridge.cpp
    ${ENGINE_SOURCES}
)

# If shaders were compiled, make the library depend on them
if(SHADERS_AVAILABLE)
    add_dependencies(droidacoustic compile_shaders)
endif()

target_include_directories(droidacoustic
    PRIVATE
        "${ENGINE_DIR}"
        "${CMAKE_CURRENT_SOURCE_DIR}"
)

target_compile_features(droidacoustic PRIVATE cxx_std_17)

target_compile_options(droidacoustic PRIVATE
    -Wall
    -Wextra
    -Wno-unused-parameter
)

target_link_libraries(droidacoustic
    PRIVATE
        android          # AAssetManager, etc.
        log              # __android_log_print
        vulkan           # Vulkan 1.1 via NDK
        glm::glm         # Header-only math
)

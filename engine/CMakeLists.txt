cmake_minimum_required(VERSION 3.22.1)
project(DroidAcousticEngine CXX)

# =============================================================================
# Standalone desktop build — used by CI and local unit-test runs.
# This build does NOT link against the Android NDK.
# The Vulkan paths inside vulkan_compute.cpp are guarded by #ifdef __ANDROID__
# so they are skipped here automatically.
# =============================================================================

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# =============================================================================
# GLM
# =============================================================================

include(FetchContent)
FetchContent_Declare(
    glm
    GIT_REPOSITORY https://github.com/g-truc/glm.git
    GIT_TAG        1.0.1
    GIT_SHALLOW    TRUE
)
FetchContent_MakeAvailable(glm)

# =============================================================================
# Google Test
# =============================================================================

FetchContent_Declare(
    googletest
    GIT_REPOSITORY https://github.com/google/googletest.git
    GIT_TAG        v1.15.2
    GIT_SHALLOW    TRUE
)
# Prevent overriding the parent project's compiler/linker settings on Windows
set(gtest_force_shared_crt ON CACHE BOOL "" FORCE)
FetchContent_MakeAvailable(googletest)

# =============================================================================
# Engine static library (desktop / CI build — no Vulkan, no JNI)
# =============================================================================

add_library(engine STATIC
    AcousticEngine.cpp
    gpu/vulkan_compute.cpp
    dsp/biquad.cpp
)

target_include_directories(engine
    PUBLIC  "${CMAKE_CURRENT_SOURCE_DIR}"
    PRIVATE "${CMAKE_CURRENT_SOURCE_DIR}"
)

target_link_libraries(engine PUBLIC glm::glm)

target_compile_options(engine PRIVATE
    -Wall
    -Wextra
    -Wno-unused-parameter
)

# =============================================================================
# Unit tests — compiled and run on CI (macOS / Linux)
# =============================================================================

enable_testing()
add_subdirectory("${CMAKE_CURRENT_SOURCE_DIR}/../tests"
                 "${CMAKE_CURRENT_BINARY_DIR}/tests")

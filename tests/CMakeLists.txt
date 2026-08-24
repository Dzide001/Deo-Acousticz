cmake_minimum_required(VERSION 3.22.1)
project(DroidAcousticTests CXX)

# Link against gtest and the engine library (both defined in engine/CMakeLists.txt)

add_executable(dac_tests
    test_geometry.cpp
    test_inverse_square.cpp
    test_summation.cpp
    test_biquad.cpp
)

target_include_directories(dac_tests
    PRIVATE "${CMAKE_CURRENT_SOURCE_DIR}/../engine"
)

target_link_libraries(dac_tests
    PRIVATE
        engine
        GTest::gtest_main
)

target_compile_features(dac_tests PRIVATE cxx_std_17)

include(GoogleTest)
gtest_discover_tests(dac_tests)

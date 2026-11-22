cmake_minimum_required(VERSION 3.22.1)
project(xy8dsp LANGUAGES C CXX)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(MI "${CMAKE_CURRENT_SOURCE_DIR}/eurorack")
if(NOT EXISTS "${MI}/plaits/dsp/voice.cc" OR NOT EXISTS "${MI}/rings/dsp/part.cc")
    message(FATAL_ERROR "Mutable Instruments source missing. Run scripts/fetch-mutable.sh before the Gradle build.")
endif()
file(GLOB_RECURSE PLAITS_DSP CONFIGURE_DEPENDS "${MI}/plaits/dsp/*.cc")
file(GLOB_RECURSE RINGS_DSP CONFIGURE_DEPENDS "${MI}/rings/dsp/*.cc")
file(GLOB STMLIB_DSP CONFIGURE_DEPENDS "${MI}/stmlib/dsp/*.cc")
file(GLOB STMLIB_UTILS CONFIGURE_DEPENDS "${MI}/stmlib/utils/*.cc")
add_library(xy8dsp SHARED
    native_synth.cpp
    "${MI}/plaits/resources.cc"
    "${MI}/rings/resources.cc"
    ${PLAITS_DSP} ${RINGS_DSP} ${STMLIB_DSP} ${STMLIB_UTILS})
# Upstream stmlib provides portable alternatives to ARMv7-only inline assembly
# (ssat/usat/vsqrt) when TEST is defined. Apply to every native translation unit,
# including Plaits, Rings, stmlib and our JNI bridge for ABI consistency.
# It also selects Plaits' non-STM32 flash stub.
target_compile_definitions(xy8dsp PRIVATE TEST)
target_include_directories(xy8dsp PRIVATE "${MI}")
target_compile_options(xy8dsp PRIVATE -O3 -fno-strict-aliasing -Wno-unused-variable -Wno-unused-function)
find_library(log-lib log)
target_link_libraries(xy8dsp ${log-lib} m)

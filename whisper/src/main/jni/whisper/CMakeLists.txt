cmake_minimum_required(VERSION 3.10)

project(whisper.cpp)

set(CMAKE_CXX_STANDARD 17)
set(WHISPER_LIB_DIR ${CMAKE_SOURCE_DIR}/../../cpp/whisper.cpp)

file(READ "${WHISPER_LIB_DIR}/CMakeLists.txt" MAIN_CMAKE_CONTENT)
string(REGEX MATCH "project\\(\"whisper\\.cpp\" VERSION ([0-9]+\\.[0-9]+\\.[0-9]+)\\)" VERSION_MATCH "${MAIN_CMAKE_CONTENT}")
if(CMAKE_MATCH_1)
    set(WHISPER_VERSION ${CMAKE_MATCH_1})
else()
    set(WHISPER_VERSION "unknown")
endif()

message(STATUS "Whisper version: ${WHISPER_VERSION}")

set(
    SOURCE_FILES
    ${WHISPER_LIB_DIR}/src/whisper.cpp
    ${CMAKE_SOURCE_DIR}/jni.c
)

find_library(LOG_LIB log)

# --- ggml configuration -----------------------------------------------------
#
# Optimisation is forced on for every build type. Android Studio builds the
# debug variant with CMAKE_BUILD_TYPE=Debug, which compiles ggml's matrix
# kernels at -O0. That is 20-100x slower, and it was the real reason the
# recitation cursor never moved on a TECNO CN5 (Helio G99): a single
# recognition pass took longer than a whole session, so no pass ever finished.
# Debug symbols are unaffected; only the optimiser is turned back on.
add_compile_options(-O3)

# Baseline ARMv8 NEON (no -march). Every arm64 phone runs it, including the
# many low-end Cortex-A53 devices without dotprod/fp16; a -march for newer
# cores would SIGILL on those.
#
# ggml's own thread pool is used instead of OpenMP: one pool rather than two,
# and no extra libomp.so in the APK.
set(GGML_OPENMP OFF CACHE BOOL "ggml: use OpenMP" FORCE)

# 16 KB-aligned ELF segments, which Google Play requires of native code and
# Android 15+ devices with 16 KB pages need to load the library at all. NDK
# r28+ does this by default; r26 must be told. Set before ggml is added so
# every library in the APK gets it.
add_link_options(-Wl,-z,max-page-size=16384)

# ggml is linked statically into libwhisper.so: one native library to load,
# align and ship, which is also what makes a static C++ runtime safe.
set(BUILD_SHARED_LIBS OFF CACHE BOOL "ggml: build static libraries" FORCE)

include(FetchContent)
FetchContent_Declare(ggml SOURCE_DIR ${WHISPER_LIB_DIR}/ggml)
FetchContent_MakeAvailable(ggml)

add_library(whisper SHARED ${SOURCE_FILES})
target_compile_definitions(whisper PUBLIC GGML_USE_CPU)
target_compile_definitions(whisper PRIVATE WHISPER_VERSION="${WHISPER_VERSION}")

if (NOT ${CMAKE_BUILD_TYPE} STREQUAL "Debug")
    target_compile_options(whisper PRIVATE -O3)
    target_compile_options(whisper PRIVATE -fvisibility=hidden -fvisibility-inlines-hidden)
    target_compile_options(whisper PRIVATE -ffunction-sections -fdata-sections)
    target_link_options(whisper PRIVATE -Wl,--gc-sections)
    target_link_options(whisper PRIVATE -Wl,--exclude-libs,ALL)
endif()

target_link_libraries(whisper ${LOG_LIB} android ggml)

include_directories(${WHISPER_LIB_DIR})
include_directories(${WHISPER_LIB_DIR}/src)
include_directories(${WHISPER_LIB_DIR}/include)
include_directories(${WHISPER_LIB_DIR}/ggml/include)
include_directories(${WHISPER_LIB_DIR}/ggml/src)
include_directories(${WHISPER_LIB_DIR}/ggml/src/ggml-cpu)

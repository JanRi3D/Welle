# libirtdab: IRT's DAB stack and Raon USB tuner driver (LGPL-2.1), built as its own
# shared library so it stays replaceable. See irtdab/WELLE-PATCHES.md for local changes.
cmake_minimum_required(VERSION 3.18.1)
project(irtdab C CXX)

set(IRT ${CMAKE_CURRENT_SOURCE_DIR}/irtdab)
file(GLOB IRT_CORE ${IRT}/*.cpp)
file(GLOB IRT_ANDROID ${IRT}/platformspecific/android/*.cpp)

add_library(fec STATIC
        ${IRT}/thirdparty/fec/encode_rs_char.c
        ${IRT}/thirdparty/fec/decode_rs_char.c
        ${IRT}/thirdparty/fec/init_rs_char.c)

add_library(irtdab SHARED ${IRT_CORE} ${IRT_ANDROID})
target_include_directories(irtdab PRIVATE ${IRT} ${IRT}/platformspecific/android ${IRT}/thirdparty/fec)
target_compile_options(irtdab PRIVATE -frtti -fexceptions -Wno-unused-variable)
target_link_libraries(irtdab fec log)

# Android 15+ devices may use 16 KB pages; only the 64-bit ABIs are affected.
if(ANDROID_ABI STREQUAL "arm64-v8a" OR ANDROID_ABI STREQUAL "x86_64")
    target_link_options(irtdab PRIVATE "-Wl,-z,max-page-size=16384")
endif()

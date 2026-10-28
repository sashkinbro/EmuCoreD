cmake_minimum_required(VERSION 3.22)
project(EmuCoreN64 LANGUAGES CXX)

if(NOT ANDROID OR NOT ANDROID_ABI STREQUAL "arm64-v8a")
    message(FATAL_ERROR "Mupen64Plus-Next requires Android arm64-v8a")
endif()
if(CMAKE_HOST_WIN32)
    set(ndk_build "${ANDROID_NDK}/ndk-build.cmd")
else()
    set(ndk_build "${ANDROID_NDK}/ndk-build")
endif()
execute_process(COMMAND git rev-parse HEAD WORKING_DIRECTORY "${CMAKE_CURRENT_SOURCE_DIR}/mupen64plus"
    RESULT_VARIABLE revision_result OUTPUT_VARIABLE core_revision OUTPUT_STRIP_TRAILING_WHITESPACE)
if(NOT revision_result EQUAL 0 OR NOT core_revision MATCHES "^[0-9a-f]+$")
    message(FATAL_ERROR "Cannot identify the Mupen64Plus-Next source revision")
endif()
string(SUBSTRING "${core_revision}" 0 8 core_revision)
get_filename_component(n64_build "${CMAKE_CURRENT_SOURCE_DIR}/../build/n64/${CMAKE_BUILD_TYPE}/${ANDROID_ABI}/${core_revision}" ABSOLUTE)
set(core_output "${n64_build}/libs/${ANDROID_ABI}/libretro.so")
add_custom_command(OUTPUT "${core_output}"
    COMMAND "${ndk_build}"
        "NDK_PROJECT_PATH=${CMAKE_CURRENT_SOURCE_DIR}/mupen64plus"
        "APP_BUILD_SCRIPT=${CMAKE_CURRENT_SOURCE_DIR}/Android.mk"
        "NDK_APPLICATION_MK=${CMAKE_CURRENT_SOURCE_DIR}/Application.mk"
        "NDK_OUT=${n64_build}/obj"
        "NDK_LIBS_OUT=${n64_build}/libs"
        "EMUCORED_CMAKE=${CMAKE_COMMAND}"
        "GLES3=1" "NDK_DEBUG=0" -j4
    WORKING_DIRECTORY "${CMAKE_CURRENT_SOURCE_DIR}/mupen64plus"
    DEPENDS "${CMAKE_CURRENT_SOURCE_DIR}/Android.mk" "${CMAKE_CURRENT_SOURCE_DIR}/gen_offsets.cmake"
    VERBATIM)
add_custom_target(n64_retro_build DEPENDS "${core_output}")
add_library(n64_retro SHARED IMPORTED GLOBAL)
set_target_properties(n64_retro PROPERTIES IMPORTED_LOCATION "${core_output}")
add_library(emucored_n64 SHARED bridge/bootstrap.cpp)
target_compile_features(emucored_n64 PRIVATE cxx_std_17)
target_include_directories(emucored_n64 PRIVATE "${CMAKE_CURRENT_SOURCE_DIR}/mupen64plus/libretro-common/include")
target_link_libraries(emucored_n64 PRIVATE n64_retro)
add_dependencies(emucored_n64 n64_retro_build)

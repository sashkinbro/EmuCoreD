cmake_minimum_required(VERSION 3.22.1)
project(emucored_nintendoui CXX)

# Google Play 16 KiB ELF page alignment support.
add_link_options("-Wl,-z,max-page-size=16384" "-Wl,-z,common-page-size=16384")

option(EMUCORED_DIAGNOSTICS "Enable verbose debug/info logs" OFF)

add_library(emucored_nintendoui SHARED
    nintendo_jni.cpp
    nintendo_renderer.cpp
    nintendo_screens.cpp
    nintendo_input.cpp
)

target_compile_features(emucored_nintendoui PRIVATE cxx_std_17)

target_compile_options(emucored_nintendoui PRIVATE
    -Wall
    -Wextra
    -Wno-unused-parameter
    -O3
    -ffunction-sections
    -fdata-sections
    -fomit-frame-pointer
    -fno-exceptions
    -fno-rtti
    $<$<BOOL:${EMUCORED_DIAGNOSTICS}>:-DEMUCORED_DIAGNOSTICS=1>
    $<$<NOT:$<BOOL:${EMUCORED_DIAGNOSTICS}>>:-DEMUCORED_DIAGNOSTICS=0>
)

if(CMAKE_BUILD_TYPE STREQUAL "Release" OR CMAKE_BUILD_TYPE STREQUAL "RelWithDebInfo")
    if(CMAKE_CXX_COMPILER_ID MATCHES "Clang")
        target_compile_options(emucored_nintendoui PRIVATE "-flto=thin")
        target_link_options(emucored_nintendoui PRIVATE "-flto=thin" "-Wl,--gc-sections" "-Wl,--icf=safe")
    endif()
endif()

set_target_properties(emucored_nintendoui PROPERTIES
    CXX_VISIBILITY_PRESET hidden
    VISIBILITY_INLINES_HIDDEN ON
)

find_library(android-lib android)
find_library(log-lib log)
find_library(GLESv3-lib GLESv3)

target_link_libraries(emucored_nintendoui PRIVATE
    ${android-lib}
    ${log-lib}
    ${GLESv3-lib}
)

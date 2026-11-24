cmake_minimum_required(VERSION 3.22.1)
project(emucored_nintendoui LANGUAGES CXX)

option(EMUCORED_DIAGNOSTICS "Enable debug logging in Nintendo UI" ON)

add_library(emucored_nintendoui SHARED
    nintendo_jni.cpp
    nintendo_renderer.cpp
)

target_compile_features(emucored_nintendoui PRIVATE cxx_std_20)
target_compile_definitions(emucored_nintendoui PRIVATE
    $<$<BOOL:${EMUCORED_DIAGNOSTICS}>:EMUCORED_DIAGNOSTICS=1>
    $<$<NOT:$<BOOL:${EMUCORED_DIAGNOSTICS}>>:EMUCORED_DIAGNOSTICS=0>
    GL_GLEXT_PROTOTYPES
)

target_link_libraries(emucored_nintendoui PRIVATE
    GLESv3
    EGL
    android
    log
    m
)

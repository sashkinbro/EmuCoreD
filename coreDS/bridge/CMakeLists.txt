add_library(emucored_ds SHARED
    ds_jni.cpp
    ds_emulator.cpp
    ds_presenter.cpp
    platform_android.cpp
)

target_include_directories(emucored_ds PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}
)

target_compile_definitions(emucored_ds PRIVATE
    MELONDS_GL_HEADER=<GLES3/gl32.h>
)

target_link_libraries(emucored_ds PRIVATE
    core
    android
    log
    EGL
    GLESv3
)

set_target_properties(emucored_ds PROPERTIES
    CXX_STANDARD 17
    CXX_STANDARD_REQUIRED ON
    OUTPUT_NAME "emucored_ds"
)

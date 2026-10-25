# SPDX-License-Identifier: GPL-3.0-or-later
set(BRIDGE_DIR "${CMAKE_CURRENT_LIST_DIR}")
# Own UI supplies Android input. Exclude the upstream Java device enumerator,
# USB adapter JNI and Android class cache; none of upstream's UI is packaged.
get_target_property(input_sources inputcommon SOURCES)
list(FILTER input_sources EXCLUDE REGEX "ControllerInterface/Android/Android.cpp$|GCAdapter.cpp$")
set_property(TARGET inputcommon PROPERTY SOURCES "${input_sources}")
get_target_property(input_definitions inputcommon COMPILE_DEFINITIONS)
list(FILTER input_definitions EXCLUDE REGEX "CIFACE_USE_ANDROID")
set_property(TARGET inputcommon PROPERTY COMPILE_DEFINITIONS "${input_definitions}")
target_sources(inputcommon PRIVATE "${BRIDGE_DIR}/AdapterDevices.cpp")
get_target_property(core_sources core SOURCES)
list(FILTER core_sources EXCLUDE REGEX "HW/WiimoteReal/IOAndroid.cpp$|^State.cpp$")
set_property(TARGET core PROPERTY SOURCES "${core_sources}")
target_sources(core PRIVATE "${BRIDGE_DIR}/WiiDevices.cpp")
set_property(TARGET androidcommon PROPERTY SOURCES "${BRIDGE_DIR}/AndroidServices.cpp")

# The public upstream state API queues work and has no completion result. Extend
# a build-local copy so our JNI reports completed disk I/O and actual load status.
file(READ "${CMAKE_SOURCE_DIR}/Source/Core/Core/State.cpp" state_source)
string(REPLACE "static void LoadAsFromCore(Core::System& system, std::string filename)"
  "static bool LoadAsFromCore(Core::System& system, std::string filename)" state_source "${state_source}")
string(REPLACE "if (s_on_after_load_callback)\n    s_on_after_load_callback();\n}"
  "if (s_on_after_load_callback)\n    s_on_after_load_callback();\n  return loaded_successfully;\n}" state_source "${state_source}")
file(READ "${BRIDGE_DIR}/StateCompletion.inc" state_extension)
file(WRITE "${CMAKE_CURRENT_BINARY_DIR}/EmuCoreDState.cpp" "${state_source}\n${state_extension}")
target_sources(core PRIVATE "${CMAKE_CURRENT_BINARY_DIR}/EmuCoreDState.cpp")

add_library(emucored_dolphin SHARED "${BRIDGE_DIR}/DolphinRuntime.cpp")
target_compile_features(emucored_dolphin PRIVATE cxx_std_23)
target_include_directories(emucored_dolphin PRIVATE
  "${CMAKE_SOURCE_DIR}/Source/Core" "${CMAKE_SOURCE_DIR}/Source/Android")
target_compile_definitions(emucored_dolphin PRIVATE ANDROID=1)
target_link_libraries(emucored_dolphin PRIVATE uicommon core inputcommon androidcommon android log EGL)
target_link_options(emucored_dolphin PRIVATE "-Wl,--no-undefined" "-Wl,--exclude-libs,ALL" "-Wl,-z,max-page-size=16384")
# Only our JNI entrypoints are exported; bundled engines keep their C++ symbols local.
target_link_options(emucored_dolphin PRIVATE "-Wl,--version-script=${BRIDGE_DIR}/exports.map")
set_property(TARGET emucored_dolphin APPEND PROPERTY LINK_DEPENDS "${BRIDGE_DIR}/exports.map")

include("${CMAKE_CURRENT_LIST_DIR}/Diagnostics.cmake")

# SPDX-License-Identifier: GPL-3.0-or-later
cmake_minimum_required(VERSION 3.22)
project(EmuCoreGB LANGUAGES C)

file(STRINGS "${CMAKE_CURRENT_SOURCE_DIR}/SameBoy/version.mk" sameboy_version_line REGEX "^VERSION := ")
string(REPLACE "VERSION := " "" sameboy_version "${sameboy_version_line}")
string(STRIP "${sameboy_version}" sameboy_version)
if(sameboy_version STREQUAL "")
    message(FATAL_ERROR "Cannot identify the pinned SameBoy version")
endif()

file(GLOB sameboy_sources CONFIGURE_DEPENDS "${CMAKE_CURRENT_SOURCE_DIR}/SameBoy/Core/*.c")
# Match upstream DISABLE_DEBUGGER/DISABLE_CHEAT_SEARCH source exclusions.
list(FILTER sameboy_sources EXCLUDE REGEX "/(debugger|sm83_disassembler|symbol_hash|cheat_search)\\.c$")
add_library(emucored_sameboy STATIC ${sameboy_sources})
set_target_properties(emucored_sameboy PROPERTIES C_STANDARD 11 C_STANDARD_REQUIRED ON C_EXTENSIONS ON POSITION_INDEPENDENT_CODE ON)
target_include_directories(emucored_sameboy PUBLIC "${CMAKE_CURRENT_SOURCE_DIR}/SameBoy/Core")
target_compile_definitions(emucored_sameboy PUBLIC GB_DISABLE_DEBUGGER GB_DISABLE_CHEAT_SEARCH)
target_compile_definitions(emucored_sameboy PRIVATE GB_INTERNAL _GNU_SOURCE GB_VERSION="${sameboy_version}" GB_COPYRIGHT_YEAR="2026")
target_link_libraries(emucored_sameboy PUBLIC m)

option(EMUCORED_BUILD_CORE_TESTS "Build standalone native core contract tests" OFF)
if(EMUCORED_BUILD_CORE_TESTS)
    enable_testing()
    add_executable(emucored_sameboy_contract_test tests/sameboy_contract_test.c)
    target_link_libraries(emucored_sameboy_contract_test PRIVATE emucored_sameboy)
    add_test(NAME emucored_sameboy_contract COMMAND emucored_sameboy_contract_test)
endif()

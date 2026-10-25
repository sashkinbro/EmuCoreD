# SPDX-License-Identifier: GPL-3.0-or-later
include("${CMAKE_CURRENT_LIST_DIR}/../core-common/diagnostics.cmake")
if(NOT EMUCORED_DIAGNOSTICS)
    file(READ "${CMAKE_CURRENT_LIST_DIR}/bsnes/nall/string/format.hpp" source)
    emucored_replace_checked(source [==[

template<typename... P> auto print(P&&... p) -> void {
  string s{forward<P>(p)...};
  fwrite(s.data(), 1, s.size(), stdout);
  fflush(stdout);
}

template<typename... P> auto print(FILE* fp, P&&... p) -> void {
  string s{forward<P>(p)...};
  fwrite(s.data(), 1, s.size(), fp);
  if(fp == stdout || fp == stderr) fflush(fp);
}

]==] [==[

template<typename... P> auto print(P&&... p) -> void {
#if !defined(EMUCORED_DIAGNOSTICS) || EMUCORED_DIAGNOSTICS
  string s{forward<P>(p)...};
  fwrite(s.data(), 1, s.size(), stdout);
  fflush(stdout);
#endif
}

template<typename... P> auto print(FILE* fp, P&&... p) -> void {
#if defined(EMUCORED_DIAGNOSTICS) && !EMUCORED_DIAGNOSTICS
  if(fp == stdout || fp == stderr) return;
#endif
  string s{forward<P>(p)...};
  fwrite(s.data(), 1, s.size(), fp);
  if(fp == stdout || fp == stderr) fflush(fp);
}

]==])
    file(COPY "${CMAKE_CURRENT_LIST_DIR}/bsnes/nall" DESTINATION "${CMAKE_CURRENT_BINARY_DIR}/diagnostics")
    file(WRITE "${CMAKE_CURRENT_BINARY_DIR}/diagnostics/nall/string/format.hpp" "${source}")
    target_include_directories(emucored_bsnes_core BEFORE PRIVATE "${CMAKE_CURRENT_BINARY_DIR}/diagnostics")
endif()

if(EMUCORED_BUILD_CORE_TESTS AND NOT EMUCORED_DIAGNOSTICS AND UNIX)
    add_executable(emucored_diagnostics_contract "${CMAKE_CURRENT_LIST_DIR}/../core-common/tests/diagnostics_contract.cpp")
    target_compile_definitions(emucored_diagnostics_contract PRIVATE TEST_BSNES=1)
    target_link_libraries(emucored_diagnostics_contract PRIVATE emucored_bsnes_core)
    target_include_directories(emucored_diagnostics_contract BEFORE PRIVATE "${CMAKE_CURRENT_BINARY_DIR}/diagnostics" "${CMAKE_CURRENT_LIST_DIR}/bsnes")
endif()

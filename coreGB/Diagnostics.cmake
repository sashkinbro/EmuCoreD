# SPDX-License-Identifier: GPL-3.0-or-later
include("${CMAKE_CURRENT_LIST_DIR}/../core-common/diagnostics.cmake")
if(NOT EMUCORED_DIAGNOSTICS)
    file(READ "${CMAKE_CURRENT_LIST_DIR}/SameBoy/Core/gb.c" source)
    emucored_replace_checked(source [==[
void GB_attributed_logv(GB_gameboy_t *gb, GB_log_attributes_t attributes, const char *fmt, va_list args)
{
    int errno_backup = errno;
    char *string = NULL;
]==] [==[
void GB_attributed_logv(GB_gameboy_t *gb, GB_log_attributes_t attributes, const char *fmt, va_list args)
{
#if !defined(EMUCORED_DIAGNOSTICS) || EMUCORED_DIAGNOSTICS
    int errno_backup = errno;
    char *string = NULL;
]==])
    emucored_replace_checked(source [==[
    free(string);
    errno = errno_backup;
}

]==] [==[
    free(string);
    errno = errno_backup;
#endif
}

]==])
    file(WRITE "${CMAKE_CURRENT_BINARY_DIR}/diagnostics/gb.c" "${source}")
    emucored_use_source(emucored_sameboy "${CMAKE_CURRENT_LIST_DIR}/SameBoy/Core/gb.c" "${CMAKE_CURRENT_BINARY_DIR}/diagnostics/gb.c")
endif()

if(EMUCORED_BUILD_CORE_TESTS AND NOT EMUCORED_DIAGNOSTICS AND UNIX)
    add_executable(emucored_diagnostics_contract "${CMAKE_CURRENT_LIST_DIR}/../core-common/tests/diagnostics_contract.cpp")
    target_compile_definitions(emucored_diagnostics_contract PRIVATE TEST_SAMEBOY=1)
    target_link_libraries(emucored_diagnostics_contract PRIVATE emucored_sameboy)
endif()

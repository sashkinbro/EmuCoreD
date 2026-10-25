# SPDX-License-Identifier: GPL-3.0-or-later
include("${CMAKE_CURRENT_LIST_DIR}/../core-common/diagnostics.cmake")
if(NOT EMUCORED_DIAGNOSTICS)
    file(READ "${CMAKE_CURRENT_LIST_DIR}/mgba/src/core/log.c" source)
    emucored_replace_checked(source [==[

void mLog(int category, enum mLogLevel level, const char* format, ...) {
	struct mLogger* context = mLogGetContext();
	va_list args;
]==] [==[

void mLog(int category, enum mLogLevel level, const char* format, ...) {
#if !defined(EMUCORED_DIAGNOSTICS) || EMUCORED_DIAGNOSTICS
	struct mLogger* context = mLogGetContext();
	va_list args;
]==])
    emucored_replace_checked(source [==[
	}
	va_end(args);
}

void mLogExplicit(struct mLogger* context, int category, enum mLogLevel level, const char* format, ...) {
	va_list args;
	va_start(args, format);
]==] [==[
	}
	va_end(args);
#endif
}

void mLogExplicit(struct mLogger* context, int category, enum mLogLevel level, const char* format, ...) {
#if !defined(EMUCORED_DIAGNOSTICS) || EMUCORED_DIAGNOSTICS
	va_list args;
	va_start(args, format);
]==])
    emucored_replace_checked(source [==[
	}
	va_end(args);
}

]==] [==[
	}
	va_end(args);
#endif
}

]==])
    file(WRITE "${CMAKE_CURRENT_BINARY_DIR}/diagnostics/log.c" "${source}")
    emucored_use_source(emucored_mgba "${CMAKE_CURRENT_LIST_DIR}/mgba/src/core/log.c" "${CMAKE_CURRENT_BINARY_DIR}/diagnostics/log.c")
    file(READ "${CMAKE_CURRENT_LIST_DIR}/mgba/include/mgba/core/log.h" source)
    emucored_replace_checked(source [==[#define mLOG(CATEGORY, LEVEL, ...) mLog(_mLOG_CAT_ ## CATEGORY, mLOG_ ## LEVEL, __VA_ARGS__)]==] [==[#define mLOG(CATEGORY, LEVEL, ...) ((void)0)]==])
    file(WRITE "${CMAKE_CURRENT_BINARY_DIR}/diagnostics/mgba/core/log.h" "${source}")
    target_include_directories(emucored_mgba BEFORE PUBLIC "${CMAKE_CURRENT_BINARY_DIR}/diagnostics")
endif()

if(EMUCORED_BUILD_CORE_TESTS AND NOT EMUCORED_DIAGNOSTICS AND UNIX)
    add_executable(emucored_diagnostics_contract "${CMAKE_CURRENT_LIST_DIR}/../core-common/tests/diagnostics_contract.cpp")
    target_compile_definitions(emucored_diagnostics_contract PRIVATE TEST_MGBA=1)
    target_link_libraries(emucored_diagnostics_contract PRIVATE emucored_gba_core)
endif()

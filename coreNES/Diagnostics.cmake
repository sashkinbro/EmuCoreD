# SPDX-License-Identifier: GPL-3.0-or-later
include("${CMAKE_CURRENT_LIST_DIR}/../core-common/diagnostics.cmake")
if(NOT EMUCORED_DIAGNOSTICS)
    file(READ "${CMAKE_CURRENT_LIST_DIR}/nestopia/src/NstLog.cpp" source)
    emucored_replace_checked(source [==[
	namespace Core
	{
		struct Log::Object
		{
]==] [==[
	namespace Core
	{
#if defined(EMUCORED_DIAGNOSTICS) && !EMUCORED_DIAGNOSTICS
        bool Log::enabled = false;
        Log::Log() : object(NULL) {}
        Log::~Log() {}
        bool Log::Available() { return false; }
        void Log::Append(cstring, ulong) {}
        Log& Log::operator << (long) { return *this; }
        Log& Log::operator << (ulong) { return *this; }
        Log& Log::operator << (cstring) { return *this; }
        Log& Log::operator << (char) { return *this; }
        Log& Log::operator << (const Hex&) { return *this; }
        void Log::Flush(cstring, dword) {}
#else
		struct Log::Object
		{
]==])
    emucored_replace_checked(source [==[
				Api::User::logCallback( string, length );
		}
	}
}
]==] [==[
				Api::User::logCallback( string, length );
		}
#endif
	}
}
]==])
    file(WRITE "${CMAKE_CURRENT_BINARY_DIR}/diagnostics/NstLog.cpp" "${source}")
    emucored_use_source(emucored_nestopia "${CMAKE_CURRENT_LIST_DIR}/nestopia/src/NstLog.cpp" "${CMAKE_CURRENT_BINARY_DIR}/diagnostics/NstLog.cpp")
endif()

if(EMUCORED_BUILD_CORE_TESTS AND NOT EMUCORED_DIAGNOSTICS AND UNIX)
    add_executable(emucored_diagnostics_contract "${CMAKE_CURRENT_LIST_DIR}/../core-common/tests/diagnostics_contract.cpp")
    target_compile_definitions(emucored_diagnostics_contract PRIVATE TEST_NESTOPIA=1)
    target_link_libraries(emucored_diagnostics_contract PRIVATE emucored_nestopia)
endif()

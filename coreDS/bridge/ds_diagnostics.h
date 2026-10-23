#pragma once

#include <cstdlib>

// Android build variants set this explicitly; standalone desktop tools keep diagnostics.
#ifndef EMUCORED_DIAGNOSTICS
#define EMUCORED_DIAGNOSTICS 1
#endif

#if EMUCORED_DIAGNOSTICS
#define EMUCORED_DIAGNOSTIC_LOG(...) (__VA_ARGS__)
#define EMUCORED_DIAGNOSTIC_ENV(name) std::getenv(name)
#define EMUCORED_DIAGNOSTIC_PROPERTY(name, value) __system_property_get(name, value)
#else
// Discard the entire expression, including arguments and format/property strings.
#define EMUCORED_DIAGNOSTIC_LOG(...) ((void)0)
#define EMUCORED_DIAGNOSTIC_ENV(name) static_cast<const char*>(nullptr)
#define EMUCORED_DIAGNOSTIC_PROPERTY(name, value) 0
#endif

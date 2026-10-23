// SPDX-FileCopyrightText: 2026 SBRO
// SPDX-License-Identifier: LicenseRef-EmuCoreD-Proprietary
// Compile with -DEMUCORED_DIAGNOSTICS=0 and link the real logging backend.cpp.
// Logging API calls must be safe before initialization and must not create a log file.
#include <cstdio>
#include <filesystem>
#include "common/logging/backend.h"
#include "common/logging/log.h"
#include "common/logging/log_entry.h"
#include "common/logging/text_formatter.h"

static_assert(EMUCORED_DIAGNOSTICS == 0);

constexpr bool SuppressedLogsDoNotEvaluateArguments() {
    int calls = 0;
    int classes = 0;
    int levels = 0;
    LOG_GENERIC(static_cast<Common::Log::Class>(++classes),
                static_cast<Common::Log::Level>(++levels), "discarded-release-log-format {}", ++calls);
    LOG_TRACE(Core, "discarded-release-log-format {}", ++calls);
    LOG_DEBUG(Core, "discarded-release-log-format {}", ++calls);
    LOG_INFO(Core, "discarded-release-log-format {}", ++calls);
    LOG_WARNING(Core, "discarded-release-log-format {}", ++calls);
    LOG_ERROR(Core, "discarded-release-log-format {}", ++calls);
    LOG_CRITICAL(Core, "discarded-release-log-format {}", ++calls);
    return calls == 0 && classes == 0 && levels == 0;
}
static_assert(SuppressedLogsDoNotEvaluateArguments());

int main(int argc, char** argv) {
    if (argc != 2 || std::filesystem::exists(argv[1])) return 2;
    // Configuration can update filters before native logging initialization.
    Common::Log::SetColorConsoleBackendEnabled(true);
    Common::Log::SetRegexFilter("[");
    Common::Log::Stop();
    Common::Log::Initialize(argv[1]);
    Common::Log::Start();
    Common::Log::DisableLoggingInTests();
    LOG_CRITICAL(Core, "discarded-release-log-format {}", 1);
    const Common::Log::Entry entry{.filename = "release-sentinel.cpp", .function = "main",
                                   .message = "direct-formatter-entry"};
    if (!Common::Log::FormatLogMessage(entry).empty()) return 4;
    Common::Log::PrintMessage(entry);
    Common::Log::PrintColoredMessage(entry);
    Common::Log::PrintMessageToLogcat(entry);
    Common::Log::Stop();
    if (std::filesystem::exists(argv[1])) return 3;
    std::puts("PASS release logging: no argument evaluation, initialization dependency, formatting, or log file");
    return 0;
}

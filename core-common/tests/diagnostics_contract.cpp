// SPDX-License-Identifier: GPL-3.0-or-later
// Exercise the real native logging APIs, including callbacks installed by callers.
// Explicit checks remain effective with NDEBUG in Android release builds.
#include <cstdio>
#include <cstdlib>
#include <string>
#include <unistd.h>
#if EMUCORED_DIAGNOSTICS
#error This contract must exercise the actual diagnostics-OFF build.
#endif
#if defined(TEST_MGBA)
extern "C" {
#include <mgba/core/log.h>
}
static int callbacks;
static void callback(mLogger*, int, mLogLevel, const char*, va_list) { ++callbacks; }
#elif defined(TEST_SAMEBOY)
extern "C" {
#include <gb.h>
}
static int callbacks;
static void callback(GB_gameboy_t*, const char*, GB_log_attributes_t) { ++callbacks; }
#elif defined(TEST_NESTOPIA)
#include "NstLog.hpp"
#include "api/NstApiUser.hpp"
static int callbacks;
static void NST_CALLBACK callback(void*, const char*, Nes::ulong) { ++callbacks; }
#elif defined(TEST_BSNES)
#include <nall/string.hpp>
#elif defined(TEST_DOLPHIN)
#include "Common/FileUtil.h"
#include "Common/Logging/Log.h"
#include "Common/Logging/LogManager.h"
#endif
static void require(bool good, const char* message) {
  if (!good) { std::fprintf(stderr, "FAIL: %s\n", message); std::exit(1); }
}
int main(int argc, char** argv) {
  FILE* capture = std::tmpfile();
  require(capture != nullptr, "cannot create output capture");
  const int old = dup(STDOUT_FILENO);
  require(old >= 0 && dup2(fileno(capture), STDOUT_FILENO) >= 0, "cannot redirect stdout");
#if defined(TEST_MGBA)
  int evaluated = 0;
  mLOG(STATUS, INFO, "diagnostics-off-macro-%d", ++evaluated);
  require(evaluated == 0, "mGBA diagnostic macro still evaluates arguments");
  mLogger logger{callback, nullptr};
  mLogSetThreadLogger(&logger);
  mLog(0, mLOG_INFO, "diagnostics-off-sentinel-%d", 7);
  mLogExplicit(&logger, 0, mLOG_ERROR, "diagnostics-off-explicit-%d", 8);
  mLogSetThreadLogger(nullptr);
  mLog(0, mLOG_INFO, "diagnostics-off-fallback");
  require(callbacks == 0, "mGBA still invokes a logger callback");
#elif defined(TEST_SAMEBOY)
  auto* gb = GB_init(GB_alloc(), GB_MODEL_DMG_B);
  require(gb != nullptr, "SameBoy allocation failed");
  GB_set_log_callback(gb, callback);
  GB_log(gb, "diagnostics-off-sentinel-%d", 7);
  GB_set_log_callback(gb, nullptr);
  GB_log(gb, "diagnostics-off-fallback");
  GB_dealloc(gb);
  require(callbacks == 0, "SameBoy still invokes a logger callback");
#elif defined(TEST_NESTOPIA)
  Nes::Api::User::logCallback.Set(callback, nullptr);
  { Nes::Core::Log log; log << "diagnostics-off-sentinel" << 7; }
  Nes::Core::Log::Flush("diagnostics-off-explicit");
  require(!Nes::Core::Log::Available() && callbacks == 0, "NES callback remains enabled");
  Nes::Api::User::logCallback.Set(nullptr, nullptr);
#elif defined(TEST_BSNES)
  nall::print("diagnostics-off-sentinel", 7);
  nall::print(stdout, "diagnostics-off-stream");
  FILE* data = std::tmpfile();
  require(data != nullptr, "cannot create data file");
  nall::print(data, "required-data");
  require(std::ftell(data) == 13, "regular file serialization was accidentally disabled");
  std::fclose(data);
#elif defined(TEST_DOLPHIN)
  require(argc == 2, "pass a writable empty directory");
  std::string file = std::string(argv[1]) + "/forbidden-diagnostic.log";
  require(!File::Exists(file), "test directory contains a stale log");
  File::SetUserPath(File::F_MAINLOG_IDX, file);
  Common::Log::LogManager::Init();
  auto* manager = Common::Log::LogManager::GetInstance();
  require(manager != nullptr, "renderers require an inert manager object");
  manager->SetEnable(Common::Log::LogType::CORE, true);
  manager->EnableListener(Common::Log::LogListener::CONSOLE_LISTENER, true);
  require(!manager->IsEnabled(Common::Log::LogType::CORE), "old configuration can enable logging");
  int evaluated = 0;
  ERROR_LOG_FMT(CORE, "diagnostics-off-sentinel {}", ++evaluated);
  require(evaluated == 0, "diagnostic arguments are still evaluated");
  manager->SaveSettings();
  Common::Log::LogManager::Shutdown();
  require(!File::Exists(file), "logger created a file in an OFF build");
#endif
  std::fflush(stdout);
  require(std::ftell(capture) == 0, "native diagnostics wrote to stdout");
  require(dup2(old, STDOUT_FILENO) >= 0, "cannot restore stdout");
  close(old);
  std::fclose(capture);
  std::puts("PASS native diagnostics disabled; data writes preserved");
  return 0;
}

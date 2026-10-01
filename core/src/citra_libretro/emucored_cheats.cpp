// Copyright 2026 EmuCoreD
// Licensed under GPLv2 or any later version
// Refer to the license.txt file included.

// Cheat file support for the libretro core. The Kotlin frontend manages the
// Gateway cheat files in the core's cheats directory and asks the core to
// (re)load them for the running title.

#include "common/logging/log.h"
#include "core/cheats/cheat_base.h"
#include "core/cheats/cheats.h"
#include "core/core.h"
#include "core/loader/loader.h"

extern "C" {

void emucored_cheat_reload() {
    auto& system = Core::System::GetInstance();
    if (!system.IsPoweredOn()) {
        return;
    }
    u64 title_id = 0;
    if (system.GetAppLoader().ReadProgramId(title_id) != Loader::ResultStatus::Success ||
        title_id == 0) {
        LOG_WARNING(Core_Cheats, "Unable to determine the title id for cheat loading");
        return;
    }
    system.CheatEngine().LoadCheatFile(title_id);
    LOG_INFO(Core_Cheats, "Cheats reloaded for title {:016X}", title_id);
}

void emucored_cheat_clear() {
    auto& system = Core::System::GetInstance();
    if (!system.IsPoweredOn()) {
        return;
    }
    for (const auto& cheat : system.CheatEngine().GetCheats()) {
        cheat->SetEnabled(false);
    }
    LOG_INFO(Core_Cheats, "Cheats disabled");
}

} // extern "C"

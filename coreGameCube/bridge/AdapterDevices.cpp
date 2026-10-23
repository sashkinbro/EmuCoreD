// SPDX-License-Identifier: GPL-3.0-or-later
// Hardware controllers are translated by EmuCoreD into Dolphin input overrides.
// The separate USB GameCube adapter transport is deliberately unavailable.
#include "InputCommon/GCAdapter.h"
#include "InputCommon/GCPadStatus.h"
namespace GCAdapter {
void Init() {}
void ResetRumble() {}
void Shutdown() {}
void SetAdapterCallback(std::function<void(void)>) {}
GCPadStatus Input(int) { return {}; }
void Output(int, u8) {}
bool IsDetected(const char** error) { if (error) *error = "dolphin_usb_adapter_unavailable"; return false; }
bool DeviceConnected(int) { return false; }
void ResetDeviceType(int) {}
double GetCurrentPollRate() { return 0; }
}

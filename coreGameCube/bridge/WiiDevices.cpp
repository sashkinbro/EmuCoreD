// SPDX-License-Identifier: GPL-3.0-or-later
#include "Core/HW/WiimoteReal/IOAndroid.h"
// Emulated Wii remotes use our input bridge. Upstream DolphinBar Java classes
// are neither loaded nor packaged by this frontend.
namespace WiimoteReal {
WiimoteScannerBackend::FindResults WiimoteScannerAndroid::FindAttachedWiimotes() { return {}; }
void InitAdapterClass() {}
}

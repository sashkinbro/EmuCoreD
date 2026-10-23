// SPDX-License-Identifier: GPL-3.0-or-later
#include <sfc/sfc.hpp>
extern "C" unsigned emucored_bsnes_bus_read(unsigned address) { return SuperFamicom::bus.read(address); }
extern "C" void emucored_bsnes_bus_write(unsigned address, unsigned value) { SuperFamicom::bus.write(address, value); }

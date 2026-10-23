#include "api/NstApiMemory.hpp"
#include "session.cpp"
#include <cassert>
#include <chrono>
#include <cstdio>
#include <thread>
#define CHECK(x)                                                               \
  do {                                                                         \
    if (!(x)) {                                                                \
      fprintf(stderr, "FAIL %d: %s\n", __LINE__, #x);                          \
      return 1;                                                                \
    }                                                                          \
  } while (0)
static bool envelopeContract(Session *s, const std::vector<uint8_t> &b,
                             const std::string &dir) {
  std::string p = dir + "/test.state";
  if (!saveEnvelope(p, s->rom, b) || loadEnvelope(p, s->rom) != b ||
      !loadEnvelope(p, s->rom + 1).empty())
    return false;
  auto corrupt = readBytes(p);
  corrupt.back() ^= 0x80;
  if (!writeBytes(p, corrupt.data(), corrupt.size()) ||
      !loadEnvelope(p, s->rom).empty())
    return false;
  return true;
}

int main(int argc, char **argv) {
  CHECK(argc == 2);
  {
    AndroidOutput output;
    CHECK(output.initAudio(48000, 2));
    std::vector<int16_t> pcm(2048, 100);
    output.audioWrite(pcm.data(), 1024);
    std::this_thread::sleep_for(std::chrono::milliseconds(100));
    CHECK(output.audioRead.load() > 0);
    output.pause(true);
    output.pause(false);
  }
  std::string d = argv[1];
  for (int mapper : {0, 1, 2, 4}) {
    std::string p = d + "/original-" + std::to_string(mapper) + ".nes";
    std::vector<uint8_t> rom(16 + 32768 + 8192);
    memcpy(rom.data(), "NES\x1a", 4);
    rom[4] = 2;
    rom[5] = 1;
    rom[6] = (mapper << 4) | 2;
    std::fill(rom.begin() + 16, rom.begin() + 16 + 32768, 0xea);
    const uint8_t code[] = {0x78, 0xd8, 0xa2, 0xff, 0x9a, 0xa9, 0,    0x8d,
                            0,    0x20, 0x8d, 1,    0x20, 0xad, 2,    0x20,
                            0xa9, 0x3f, 0x8d, 6,    0x20, 0xa9, 0,    0x8d,
                            6,    0x20, 0xa9, 0x21, 0x8d, 7,    0x20, 0xa9,
                            0x0a, 0x8d, 1,    0x20, 0x4c, 0x23, 0xe0};
    for (int bank = 0; bank < 4; bank++) {
      memcpy(rom.data() + 16 + bank * 8192, code, sizeof(code));
      for (int vector = 0x1ffa; vector < 8192; vector += 2) {
        rom[16 + bank * 8192 + vector] = 0;
        rom[16 + bank * 8192 + vector + 1] = 0xe0;
      }
    }
    CHECK(writeBytes(p, rom.data(), rom.size()));
    for (int attempt = 0; attempt < 2; attempt++) {
      auto *s = createSession(p, d + "/nes-" + std::to_string(mapper) + ".sav",
                              "NES", false);
      CHECK(s);
      const auto initial = s->emulator.Frame();
      for (int i = 0; i < 10; i++)
        CHECK(JNI(nativeFrame)(nullptr, nullptr, (jlong)s));
      CHECK(s->emulator.Frame() == initial + 10);
      Memory memory(s->emulator);
      bool batteryFound = false;
      for (Nes::ulong region = 0; region < memory.NumRegions(); region++) {
        auto r = memory.GetRegion(region);
        if (r.type == Memory::TYPE_WORK_RAM && r.battery && r.size > 64) {
          batteryFound = true;
          auto *data = (uint8_t *)r.data;
          if (attempt)
            CHECK(data[64] == 0x5a);
          data[64] = 0x5a;
        }
      }
      if (mapper != 2)
        CHECK(batteryFound);
      CHECK((s->pixels[128 * 240] & 0xffffff) != 0);
      JNI(nativeButton)(nullptr, nullptr, (jlong)s, 0, 0, true);
      CHECK(s->input.pad[0].buttons & 1);
      auto system = memory.GetRegion(0);
      CHECK(system.data && system.size > 64);
      auto *ram = (uint8_t *)system.data;
      ram[64] = 0x37;
      auto b = state(s);
      ram[64] = 0xa9;
      CHECK(!b.empty());
      JNI(nativeFrame)(nullptr, nullptr, (jlong)s);
      CHECK(restore(s, b));
      CHECK(((uint8_t *)memory.GetRegion(0).data)[64] == 0x37);
      CHECK(envelopeContract(s, b, d));
      delete s;
    }
  }
  puts("PASS Nestopia Android session: mappers 0/1/2/4 real "
       "frames/pixels/buttons/state restore/envelope rejection/recreation");
}

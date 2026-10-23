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
  for (bool cgb : {false, true}) {
    std::string p = d + (cgb ? "/original.gbc" : "/original.gb");
    std::vector<uint8_t> rom(0x8000);
    uint8_t entry[] = {0xc3, 0x50, 1};
    memcpy(rom.data() + 0x100, entry, 3);
    memcpy(rom.data() + 0x134, "EMUCORE TEST", 12);
    rom[0x143] = cgb ? 0x80 : 0;
    rom[0x147] = 0x10;
    rom[0x149] = 3;
    uint8_t sum = 0;
    for (int i = 0x134; i <= 0x14c; i++)
      sum = sum - rom[i] - 1;
    rom[0x14d] = sum;
    uint8_t code[] = {0x3e, 0x91, 0xe0, 0x40, 0x18, 0xfe};
    memcpy(rom.data() + 0x150, code, sizeof(code));
    CHECK(writeBytes(p, rom.data(), rom.size()));
    // A valid checksum does not make a truncated battery cartridge loadable.
    const std::string failedBattery =
        d + (cgb ? "/failed-gbc.sav" : "/failed-gb.sav");
    const std::vector<uint8_t> seed(32768, 0xa7);
    CHECK(writeBytes(failedBattery, seed.data(), seed.size()));
    auto malformed = rom;
    malformed.resize(0x150);
    const std::string malformedPath =
        d + (cgb ? "/malformed.gbc" : "/malformed.gb");
    CHECK(writeBytes(malformedPath, malformed.data(), malformed.size()));
    CHECK(!createSession(malformedPath, failedBattery, cgb ? "GBC" : "GB",
                         false));
    CHECK(readBytes(failedBattery) == seed);
    // A valid cartridge must not replace a truncated or malformed battery file.
    const std::vector<uint8_t> shortBattery(16, 0x5c);
    CHECK(writeBytes(failedBattery, shortBattery.data(), shortBattery.size()));
    CHECK(!createSession(p, failedBattery, cgb ? "GBC" : "GB", false));
    CHECK(readBytes(failedBattery) == shortBattery);
    std::vector<uint8_t> corruptRtc(32768 + 48, 0);
    corruptRtc[32768 + 1] =
        0x81; // nonzero padding in canonical RTC register format
    CHECK(writeBytes(failedBattery, corruptRtc.data(), corruptRtc.size()));
    CHECK(!createSession(p, failedBattery, cgb ? "GBC" : "GB", false));
    CHECK(readBytes(failedBattery) == corruptRtc);
    for (int attempt = 0; attempt < 2; attempt++) {
      auto *s = createSession(p, d + (cgb ? "/gbc.sav" : "/gb.sav"),
                              cgb ? "GBC" : "GB", false);
      CHECK(s);
      for (int i = 0; i < 360; i++)
        CHECK(JNI(nativeFrame)(nullptr, nullptr, (jlong)s));
      CHECK(GB_get_registers(s->gb)->pc >= 0x150);
      CHECK(GB_is_cgb_in_cgb_mode(s->gb) == cgb);
      GB_write_memory(s->gb, 0x0000, 0x0a);
      GB_write_memory(s->gb, 0x4000, 0);
      if (attempt)
        CHECK(GB_read_memory(s->gb, 0xa040) == 0x5a);
      GB_write_memory(s->gb, 0xa040, 0x5a);
      GB_write_memory(s->gb, 0x4000, 0x0c);
      GB_write_memory(s->gb, 0x6000, 0);
      GB_write_memory(s->gb, 0x6000, 1);
      if (attempt)
        CHECK((GB_read_memory(s->gb, 0xa000) & 0xc1) == 0x40);
      GB_write_memory(s->gb, 0xa000, 0x40);
      GB_write_memory(s->gb, 0x4000, 0);
      GB_write_memory(s->gb, 0xff00, 0x10);
      JNI(nativeButton)(nullptr, nullptr, (jlong)s, 0, 0, true);
      JNI(nativeFrame)(nullptr, nullptr, (jlong)s);
      CHECK(!(GB_read_memory(s->gb, 0xff00) & 1));
      GB_write_memory(s->gb, 0xc040, 0x5a);
      std::vector<uint8_t> b(GB_get_save_state_size(s->gb));
      GB_save_state_to_buffer(s->gb, b.data());
      GB_write_memory(s->gb, 0xc040, 0xa5);
      CHECK(!GB_load_state_from_buffer(s->gb, b.data(), b.size()));
      CHECK(GB_read_memory(s->gb, 0xc040) == 0x5a);
      CHECK(envelopeContract(s, b, d));
      delete s;
    }
  }
  puts("PASS SameBoy Android session: compiled open boot ROM GB/GBC entry, "
       "frames/buttons/state restore/envelope rejection/recreation");
}

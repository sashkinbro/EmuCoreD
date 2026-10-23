#define EMUCORED_SESSION_CONTRACT 1
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
  std::string d = argv[1], p = d + "/original.gba";
  std::vector<uint8_t> rom(0x200);
  uint8_t entry[] = {0x2e, 0, 0, 0xea}, loop[] = {0xfe, 0xff, 0xff, 0xea};
  memcpy(rom.data(), entry, 4);
  memcpy(rom.data() + 0xc0, loop, 4);
  rom[0xb2] = 0x96;
  CHECK(writeBytes(p, rom.data(), rom.size()));
  // Simulate a save loader that modifies its memory image, then rejects it.
  const std::string failedBattery = d + "/failed-gba.sav";
  const std::vector<uint8_t> seed(32768, 0xa7);
  CHECK(writeBytes(failedBattery, seed.data(), seed.size()));
  allowBatteryInitialization = [](Session *s) {
    const uint8_t replacement = 0x31;
    s->saveFile->seek(s->saveFile, 0, SEEK_SET);
    s->saveFile->write(s->saveFile, &replacement, 1);
    return false;
  };
  CHECK(!createSession(p, failedBattery, "GBA", false));
  allowBatteryInitialization = nullptr;
  CHECK(readBytes(failedBattery) == seed);
  for (int attempt = 0; attempt < 2; attempt++) {
    auto *s = createSession(p, d + "/gba.sav", "GBA", false);
    CHECK(s);
    auto *c = s->core;
    auto *board = (GBA *)c->board;
    GBASavedataForceType(&board->memory.savedata, GBA_SAVEDATA_SRAM);
    GBAHardwareInitRTC(&board->memory.hw);
    if (attempt) {
      GBASavedataRTCRead(&board->memory.savedata);
      CHECK(board->memory.hw.rtc.control == 0x40);
    }
    board->memory.hw.rtc.control = 0x40;
    const uint8_t rtcTime[] = {0x26, 0x10, 0x23, 0x05, 0x12, 0x30, 0x15};
    memcpy(board->memory.hw.rtc.time, rtcTime, 7);
    board->memory.hw.rtc.lastLatch = time(nullptr);
    if (attempt)
      CHECK(c->busRead8(c, 0x0e000040) == 0x5a);
    c->busWrite8(c, 0x0e000040, 0x5a);
    c->busWrite16(c, 0x04000000, 0x0403);
    c->busWrite16(c, 0x06000000, 31);
    c->busWrite16(c, 0x06000002, 0x7c00);
    for (int i = 0; i < 4; i++)
      CHECK(JNI(nativeFrame)(nullptr, nullptr, (jlong)s));
    CHECK(s->pixels[0] != s->pixels[1]);
    JNI(nativeButton)(nullptr, nullptr, (jlong)s, 0, 0, true);
    CHECK(c->getKeys(c) & 1);
    c->busWrite32(c, 0x02000040, 0x12345678);
    std::vector<uint8_t> b(c->stateSize(c));
    CHECK(c->saveState(c, b.data()));
    c->busWrite32(c, 0x02000040, 0);
    CHECK(c->loadState(c, b.data()));
    CHECK(c->busRead32(c, 0x02000040) == 0x12345678);
    CHECK(envelopeContract(s, b, d));
    delete s;
  }
  puts("PASS mGBA Android session: real frames/pixels/buttons/state "
       "restore/checksummed corrupt and wrong-ROM rejection/recreation");
}

#include "common/input_latch.h"
#include "common/append_read_position.h"

#include <cassert>
#include <cstdio>
#include <chrono>
#include <filesystem>
#include <string>
#include <tuple>

int main() {
    Common::ButtonPressLatch button;
    assert(!button.GetStatus());
    button.SetPressed(true);
    button.SetPressed(false);
    assert(button.GetStatus()); // A pulse entirely between guest polls is visible once.
    assert(!button.GetStatus());
    button.SetPressed(true);
    assert(button.GetStatus());
    assert(button.GetStatus()); // Holding does not consume the live state.
    button.SetPressed(false);
    assert(!button.GetStatus()); // A sampled hold must not create a second press on release.

    Common::TouchPressLatch touch;
    std::uint64_t first_reader = 0, second_reader = 0;
    touch.OnPress(false);
    touch.OnRelease(true, 0.25f, 0.75f);
    assert(touch.Sample(first_reader, 0.f, 0.f, false) == std::make_tuple(.25f, .75f, true));
    assert(touch.Sample(first_reader, 0.f, 0.f, false) == std::make_tuple(0.f, 0.f, false));
    assert(touch.Sample(second_reader, 0.f, 0.f, false) == std::make_tuple(.25f, .75f, true));
    auto late_reader = touch.CurrentPressCount();
    assert(!std::get<2>(touch.Sample(late_reader, 0.f, 0.f, false)));
    touch.OnPress(false);
    assert(touch.Sample(first_reader, .5f, .6f, true) == std::make_tuple(.5f, .6f, true));
    touch.OnPress(true); // Movement must not create another pending press.
    touch.OnRelease(true, .6f, .7f);
    assert(!std::get<2>(touch.Sample(first_reader, 0.f, 0.f, false)));
    assert(touch.Sample(late_reader, 0.f, 0.f, false) == std::make_tuple(.6f, .7f, true));
    assert(!std::get<2>(touch.Sample(late_reader, 0.f, 0.f, false)));

    const auto path = std::filesystem::temp_directory_path() /
        ("emucored-append-" + std::to_string(
            std::chrono::steady_clock::now().time_since_epoch().count()) + ".tmp");
    std::FILE* file = std::fopen(path.string().c_str(), "w+b");
    assert(file);
    assert(std::fwrite("ABC", 1, 3, file) == 3);
    std::fclose(file);
    file = std::fopen(path.string().c_str(), "a+b");
    assert(file);
    // Reproduce bionic's initial EOF position even on hosts whose libc starts at zero.
    assert(std::fseek(file, 0, SEEK_END) == 0);
    bool sought = false;
    Common::NormalizeAppendReadPosition(true, "a+b", [&] {
        sought = true;
        assert(std::fseek(file, 0, SEEK_SET) == 0);
    });
    assert(sought && std::fgetc(file) == 'A');
    assert(std::fseek(file, 0, SEEK_CUR) == 0);
    assert(std::fwrite("D", 1, 1, file) == 1);
    assert(std::fflush(file) == 0);
    assert(std::fseek(file, 0, SEEK_SET) == 0);
    char contents[4]{};
    assert(std::fread(contents, 1, sizeof(contents), file) == sizeof(contents));
    assert(std::string(contents, sizeof(contents)) == "ABCD"); // Writes still append.
    sought = false;
    Common::NormalizeAppendReadPosition(true, "ab", [&] { sought = true; });
    Common::NormalizeAppendReadPosition(true, "r+b", [&] { sought = true; });
    Common::NormalizeAppendReadPosition(false, "a+", [&] { sought = true; });
    assert(!sought);
    std::fclose(file);
    std::filesystem::remove(path);
}

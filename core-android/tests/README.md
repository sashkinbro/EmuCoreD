# Release native logging regression

This standalone regression links the production Azahar logging backend and text formatter with `EMUCORED_DIAGNOSTICS=0`. It does not use a replacement logger and does not build or install the app.

From the repository root in PowerShell:

```powershell
./core-android/tests/run_release_logging_test.ps1
./core-android/tests/run_release_logging_test.ps1 -DeviceSerial '<adb serial>'
```

The default compiler is the project's Android NDK `29.0.14206865` under `%LOCALAPPDATA%/Android/Sdk`. Override `-NdkPath` or `-AdbPath` for another SDK location. Initialize the repository's `core/externals/fmt` submodule first. Results go into `logs/release-logging-regression`; `-OutputDirectory` changes that location.

The compiler's constexpr assertion proves every `LOG_*` macro discards its arguments, including class and level expressions. Binary inspection checks that the format sentinel and logger backend implementation are absent. Optional arm64 device execution verifies public lifecycle/filter APIs are safe before initialization, formatter and direct print APIs produce no output, and requesting a log file does not create it. The runner uses a unique executable and sentinel path under `/data/local/tmp`, then removes only those paths. It does not install a package or interact with an active game.

This verifies the logging boundary. The final APK still needs the separate release artifact scan and app/device regressions; the standalone test does not cover texture/shader dump entrypoints.

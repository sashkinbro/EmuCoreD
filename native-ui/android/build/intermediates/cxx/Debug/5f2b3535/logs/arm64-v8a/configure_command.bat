@echo off
"C:\\Users\\SBRO\\AppData\\Local\\Android\\Sdk\\cmake\\3.30.5\\bin\\cmake.exe" ^
  "-HC:\\Users\\SBRO\\AndroidStudioProjects\\EmuCoreD\\native-ui\\android\\src\\main\\cpp" ^
  "-DCMAKE_SYSTEM_NAME=Android" ^
  "-DCMAKE_EXPORT_COMPILE_COMMANDS=ON" ^
  "-DCMAKE_SYSTEM_VERSION=26" ^
  "-DANDROID_PLATFORM=android-26" ^
  "-DANDROID_ABI=arm64-v8a" ^
  "-DCMAKE_ANDROID_ARCH_ABI=arm64-v8a" ^
  "-DANDROID_NDK=C:\\Users\\SBRO\\AppData\\Local\\Android\\Sdk\\ndk\\29.0.14206865" ^
  "-DCMAKE_ANDROID_NDK=C:\\Users\\SBRO\\AppData\\Local\\Android\\Sdk\\ndk\\29.0.14206865" ^
  "-DCMAKE_TOOLCHAIN_FILE=C:\\Users\\SBRO\\AppData\\Local\\Android\\Sdk\\ndk\\29.0.14206865\\build\\cmake\\android.toolchain.cmake" ^
  "-DCMAKE_MAKE_PROGRAM=C:\\Users\\SBRO\\AppData\\Local\\Android\\Sdk\\cmake\\3.30.5\\bin\\ninja.exe" ^
  "-DCMAKE_CXX_FLAGS=-std=c++20 -fexceptions -fno-rtti" ^
  "-DCMAKE_LIBRARY_OUTPUT_DIRECTORY=C:\\Users\\SBRO\\AndroidStudioProjects\\EmuCoreD\\native-ui\\android\\build\\intermediates\\cxx\\Debug\\5f2b3535\\obj\\arm64-v8a" ^
  "-DCMAKE_RUNTIME_OUTPUT_DIRECTORY=C:\\Users\\SBRO\\AndroidStudioProjects\\EmuCoreD\\native-ui\\android\\build\\intermediates\\cxx\\Debug\\5f2b3535\\obj\\arm64-v8a" ^
  "-DCMAKE_BUILD_TYPE=Debug" ^
  "-BC:\\Users\\SBRO\\AndroidStudioProjects\\EmuCoreD\\native-ui\\android\\.cxx\\Debug\\5f2b3535\\arm64-v8a" ^
  -GNinja ^
  "-DANDROID_STL=c++_shared" ^
  "-DEMUCORED_DIAGNOSTICS=ON" ^
  "-DCMAKE_C_FLAGS_DEBUG=-O3 -g" ^
  "-DCMAKE_CXX_FLAGS_DEBUG=-O3 -g"

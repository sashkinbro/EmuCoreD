// Android wrapper around the vendored Azahar core in ../core.
//
// This module does not contain any Java/Kotlin code or Azahar's own frontend:
// it only compiles the upstream CMake project with the core's own Android
// bridge (citra-android), producing libemucore_core.so and shipping it as a
// jniLib of this library module. EmuCoreD loads the produced library through
// com.sbro.emucored.core.EmuCoreNative.
plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.sbro.emucored.core.azahar"
    compileSdk {
        version = release(37)
    }
    ndkVersion = "29.0.14206865"

    defaultConfig {
        minSdk = 26
        ndk {
            abiFilters += "arm64-v8a"
        }
        externalNativeBuild {
            cmake {
                arguments += listOf(
                    "-DANDROID_STL=c++_static",
                    // Azahar's desktop frontends and services are not part of
                    // the Android bridge build.
                    "-DENABLE_QT=OFF",
                    "-DENABLE_SDL2=OFF",
                    "-DENABLE_TESTS=OFF",
                    "-DENABLE_ROOM=OFF",
                    "-DENABLE_ROOM_STANDALONE=OFF",
                    "-DENABLE_WEB_SERVICE=ON",
                    "-DENABLE_SCRIPTING=OFF",
                    "-DENABLE_GDBSTUB=OFF",
                    // cubeb's Android backend (AAudio) is how the core plays
                    // audio itself, so the frontend no longer pumps PCM.
                    "-DENABLE_CUBEB=ON",
                    "-DENABLE_OPENAL=OFF",
                    "-DENABLE_CUBEB=OFF",
                    "-DENABLE_LIBUSB=OFF",
                    "-DENABLE_DISCORD_RPC=OFF",
                    "-DENABLE_NATIVE_OPTIMIZATION=OFF",
                    // The Android build does not need upstream's LTO pass; the
                    // frontend already compiles the core at -O3.
                    "-DENABLE_LTO=OFF",
                    // NDK clang is newer than the compiler matrix upstream's
                    // warnings-as-errors mode was tuned for.
                    "-DCITRA_WARNINGS_AS_ERRORS=OFF"
                )
                targets += "citra-android"
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("../core/CMakeLists.txt")
            version = "3.30.5"
        }
    }

    buildTypes {
        debug {
            // This module compiles the emulator itself. Keep the debug symbols,
            // but optimize the core so test APKs measure real emulation speed.
            externalNativeBuild {
                cmake {
                    arguments += "-DEMUCORED_DIAGNOSTICS=ON"
                    arguments += listOf(
                        "-DCMAKE_C_FLAGS_DEBUG=-O3 -g",
                        "-DCMAKE_CXX_FLAGS_DEBUG=-O3 -g"
                    )
                }
            }
        }
        release {
            externalNativeBuild {
                cmake {
                    arguments += "-DEMUCORED_DIAGNOSTICS=OFF"
                    arguments += listOf(
                        "-DCMAKE_C_FLAGS_RELWITHDEBINFO=-O3 -g -DNDEBUG",
                        "-DCMAKE_CXX_FLAGS_RELWITHDEBINFO=-O3 -g -DNDEBUG"
                    )
                }
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

// SPDX-License-Identifier: GPL-3.0-or-later
plugins { alias(libs.plugins.android.library) }
android {
    namespace = "com.sbro.emucored.core.dolphin.nativecore"
    compileSdk { version = release(37) }
    ndkVersion = "29.0.14206865"
    useLibrary("android.test.runner")
    useLibrary("android.test.base")
    defaultConfig {
        minSdk = 26
        testInstrumentationRunner = "android.test.InstrumentationTestRunner"
        ndk { abiFilters += "arm64-v8a" }
        externalNativeBuild { cmake {
            arguments += listOf("-DANDROID_STL=c++_static", "-DCMAKE_BUILD_TYPE=Release",
                "-DCMAKE_PROJECT_dolphin-emu_INCLUDE=${file("../coreGameCube/bridge/InjectRuntime.cmake").absolutePath.replace('\\', '/')}")
            arguments += listOf("-DCMAKE_JOB_POOLS=compile=4;link=1", "-DCMAKE_JOB_POOL_COMPILE=compile", "-DCMAKE_JOB_POOL_LINK=link")
            targets += "emucored_dolphin"
        } }
    }
    buildTypes {
        debug { externalNativeBuild { cmake { arguments += "-DEMUCORED_DIAGNOSTICS=ON" } } }
        release { externalNativeBuild { cmake { arguments += "-DEMUCORED_DIAGNOSTICS=OFF" } } }
    }
    externalNativeBuild { cmake { path = file("../coreGameCube/dolphin/CMakeLists.txt"); version = "3.30.5" } }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
}
androidComponents {
    onVariants(selector().all()) { variant ->
        variant.sources.assets?.addStaticSourceDirectory("../coreGameCube/dolphin/Data")
    }
}

// Android wrapper for the vendored melonDS core in ../coreDS.
plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.sbro.emucored.core.ds"
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
                    "-DANDROID_ARM_NEON=true",
                    "-DCMAKE_BUILD_TYPE=Release"
                )
                targets += "emucored_ds"
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("../coreDS/CMakeLists.txt")
            version = "3.30.5"
        }
    }

    buildTypes {
        debug {
            externalNativeBuild {
                cmake {
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

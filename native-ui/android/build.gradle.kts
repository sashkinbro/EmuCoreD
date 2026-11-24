plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.sbro.emucored.nintendoui"
    compileSdk {
        version = release(37)
    }
    ndkVersion = "29.0.14206865"

    defaultConfig {
        minSdk = 26
        consumerProguardFiles("consumer-rules.pro")
        ndk {
            abiFilters += "arm64-v8a"
        }
        externalNativeBuild {
            cmake {
                cppFlags += "-std=c++20 -fexceptions -fno-rtti"
                arguments += "-DANDROID_STL=c++_shared"
                targets += "emucored_nintendoui"
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.30.5"
        }
    }

    buildTypes {
        debug {
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
                        "-DCMAKE_C_FLAGS_RELWITHDEBINFO=-O3 -g -DNDEBUG -flto=thin",
                        "-DCMAKE_CXX_FLAGS_RELWITHDEBINFO=-O3 -g -DNDEBUG -flto=thin",
                        "-DCMAKE_SHARED_LINKER_FLAGS_RELWITHDEBINFO=-flto=thin"
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

dependencies {
    implementation(libs.androidx.core.ktx)
}

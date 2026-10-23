plugins { alias(libs.plugins.android.library) }
android {
 namespace = "com.sbro.emucored.core.gb"
 compileSdk { version = release(37) }
 ndkVersion = "29.0.14206865"
 defaultConfig {
  minSdk = 26
  ndk { abiFilters += "arm64-v8a" }
  externalNativeBuild { cmake { arguments += listOf("-DANDROID_STL=c++_static", "-DEMUCORED_BUILD_ANDROID_RUNTIME=ON"); targets += "emucored_gb" } }
 }
 externalNativeBuild { cmake { path = file("../coreGB/CMakeLists.txt"); version = "3.30.5" } }
 compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
}

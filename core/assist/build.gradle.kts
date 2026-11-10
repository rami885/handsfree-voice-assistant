plugins { alias(libs.plugins.android.library) }
android {
    namespace = "com.handsfree.assist"
    compileSdk = 36
    buildToolsVersion = "36.0.0"
    defaultConfig { minSdk = 31 }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    lint { abortOnError = true }
}
dependencies { implementation(libs.coroutines.core) }

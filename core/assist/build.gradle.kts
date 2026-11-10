plugins { alias(libs.plugins.android.library) }
android {
    namespace = "com.handsfree.assist"
    compileSdk = 36
    buildToolsVersion = "36.0.0"
    defaultConfig { minSdk = 31 }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    testOptions.unitTests.isIncludeAndroidResources = true
    lint { abortOnError = true }
}
dependencies {
    implementation(libs.coroutines.core)
    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
}

tasks.withType<Test>().configureEach {
    val testTask = this
    doFirst {
        val argument = testTask.allJvmArgs.first { it.startsWith("-Djava.io.tmpdir=") }
        val directory = rootProject.file(argument.substringAfter("="))
        check(directory.mkdirs() || directory.isDirectory) { "Cannot create unit-test temporary directory" }
    }
    systemProperty("robolectric.dependency.repo.url", "https://repo.maven.apache.org/maven2")
    systemProperty("maven.repo.local", rootProject.file(".cache/m2").absolutePath)
    systemProperty("java.io.tmpdir", rootProject.file(".cache/tmp").absolutePath)
}

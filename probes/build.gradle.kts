plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}
android {
    namespace = "com.handsfree.probes"
    compileSdk = 36
    buildToolsVersion = "36.0.0"
    defaultConfig {
        applicationId = providers.gradleProperty("probesApplicationId").get()
        minSdk = 31
        targetSdk = 36
        versionCode = 1
        versionName = "0.1"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    testOptions.unitTests.isIncludeAndroidResources = true
    lint { abortOnError = true }
}
dependencies {
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.coroutines.android)
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)
    implementation(project(":core:guard"))
    implementation(project(":core:intent"))
    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.coroutines.test)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.junit)

}

ksp { arg("room.schemaLocation", "$projectDir/schemas") }

tasks.withType<Test>().configureEach {
    val testTask = this
    doFirst {
        // Gradle stores java.io.tmpdir among immutable JVM args, outside systemProperties.
        val argument = testTask.allJvmArgs.first { it.startsWith("-Djava.io.tmpdir=") }
        val directory = rootProject.file(argument.substringAfter("="))
        check(directory.mkdirs() || directory.isDirectory) { "Cannot create unit-test temporary directory" }
    }
    systemProperty("robolectric.dependency.repo.url", "https://repo.maven.apache.org/maven2")
    systemProperty("maven.repo.local", rootProject.file(".cache/m2").absolutePath)
    systemProperty("java.io.tmpdir", rootProject.file(".cache/tmp").absolutePath)
}

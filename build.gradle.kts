plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.hilt) apply false
    alias(libs.plugins.kover) apply false
}
tasks.register<Exec>("safetyCheck") {
    commandLine("python3", "scripts/check-safety.py")
}
subprojects {
    tasks.matching { it.name in setOf("check", "lint", "preBuild") }.configureEach { dependsOn(rootProject.tasks.named("safetyCheck")) }
    tasks.matching { it.name.startsWith("process") && it.name.endsWith("Manifest") }.configureEach {
        doLast {
            providers.exec {
                workingDir(rootProject.projectDir)
                commandLine("python3", "scripts/check-safety.py")
            }.result.get().assertNormalExitValue()
        }
    }
}

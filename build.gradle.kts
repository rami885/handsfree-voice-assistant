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

// Reject opaque local archives, including empty file trees, before resolution.
val verifyLocalDependencies = tasks.register("verifyLocalDependencies") {
    doLast {
        subprojects.filter { it.path in setOf(":core:guard", ":core:intent", ":probes") }.forEach { module ->
            module.configurations.filter { config ->
                val name = config.name.lowercase(java.util.Locale.ROOT)
                listOf("implementation", "api", "compileonly", "runtimeonly", "compileclasspath", "runtimeclasspath").any { name.endsWith(it) }
            }.forEach { config ->
                check(config.allDependencies.none { it is FileCollectionDependency }) {
                    "Local file dependency forbidden in ${module.path}: ${config.name}"
                }
            }
        }
    }
}
tasks.named("safetyCheck") { dependsOn(verifyLocalDependencies) }
val dependencyPolicyTest = tasks.register<Exec>("dependencyPolicyTest") {
    commandLine("python3", "scripts/test-dependency-policy.py")
}
subprojects {
    tasks.matching { it.name == "check" }.configureEach { dependsOn(dependencyPolicyTest) }
}
// Aggregate the real coverage gates of whichever modules this milestone has.
val coverageGate = tasks.register("koverVerify")
subprojects {
    tasks.matching { it.name == "koverVerify" }.configureEach { coverageGate.configure { dependsOn(this@configureEach) } }
}

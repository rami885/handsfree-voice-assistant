import org.gradle.api.artifacts.component.ModuleComponentIdentifier

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

val verifyNoNetwork = tasks.register("verifyNoNetwork") {
    doLast {
        val protectedPaths = setOf(":core:guard", ":core:intent", ":probes")
        val forbidden = listOf("okhttp", "retrofit", "ktor", "httpclient", "httpcore", "volley", "cronet", "netty", "grpc", "async-http", "rxhttp", "fuel", "vertx", "socket.io")
        val pureGroups = setOf("org.jetbrains", "org.jetbrains.kotlin", "org.jetbrains.kotlinx", "junit", "org.junit", "org.hamcrest")
        fun verify(path: String, group: String, name: String) {
            val coordinate = "$group:$name".lowercase(java.util.Locale.ROOT)
            check(forbidden.none { it in coordinate }) { "Network dependency forbidden in $path: $coordinate" }
            if (path.startsWith(":core:")) {
                check(group in pureGroups || group.startsWith("org.junit.")) { "Unreviewed dependency forbidden in $path: $coordinate" }
            }
        }
        subprojects.filter { it.path in protectedPaths }.forEach { module ->
            module.configurations.filter { config ->
                val name = config.name.lowercase(java.util.Locale.ROOT)
                config.isCanBeResolved && (name.endsWith("compileclasspath") || name.endsWith("runtimeclasspath"))
            }.forEach { config ->
                config.allDependencies.filterIsInstance<ExternalModuleDependency>().forEach { dependency ->
                    verify(module.path, dependency.group.orEmpty(), dependency.name)
                }
                config.incoming.resolutionResult.allComponents.forEach { component ->
                    (component.id as? ModuleComponentIdentifier)?.let { verify(module.path, it.group, it.module) }
                }
            }
        }
    }
}
subprojects {
    tasks.matching { it.name in setOf("check", "lint", "preBuild") }.configureEach { dependsOn(verifyNoNetwork) }
}

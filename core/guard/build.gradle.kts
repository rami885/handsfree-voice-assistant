plugins { alias(libs.plugins.kotlin.jvm); alias(libs.plugins.kover) }
kotlin { jvmToolchain(17) }
dependencies { testImplementation(libs.junit); implementation(libs.coroutines.core) }
kover { reports { total { verify { rule { minBound(90) } } } } }
tasks.named("check") { dependsOn("koverVerify") }

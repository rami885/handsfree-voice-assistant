pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }
dependencyResolutionManagement { repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS); repositories { google(); mavenCentral() } }
rootProject.name = "handsfree-voice-assistant"
include(":app", ":core:assist", ":core:audio", ":core:guard", ":core:intent", ":probes")

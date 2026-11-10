pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven { url = java.net.URI("https://jitpack.io") }
        maven { url = rootDir.resolve("local-repo").toURI() }
    }
}

rootProject.name = "TrekoAssistant"
include(":app")
include(":core-common")
include(":core-audio")
include(":core-kws")
include(":core-network")
include(":core-mcp")

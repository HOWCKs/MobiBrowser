// MobiBrowser — navegador mobile com suporte a extensões (GeckoView + WebExtensions)
pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
    repositories {
        google()
        mavenCentral()
        // GeckoView é publicado no Maven da Mozilla, não no Maven Central.
        maven("https://maven.mozilla.org/maven2/") {
            name = "MozillaMaven"
            content {
                includeGroupByRegex("org\\.mozilla.*")
            }
        }
    }
}

rootProject.name = "MobiBrowser"

include(":app")

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
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "gentle-connection"
include(":app")
// The watch app. Same applicationId as `:app` on purpose — that is what makes Play offer it as
// this app's companion and install it on her watch by itself, and what puts both ends of the
// Data Layer in the same package so they can talk at all.
include(":watch")

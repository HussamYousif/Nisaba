pluginManagement {
    includeBuild("build-logic")
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
        maven("https://repo.boox.com/repository/maven-public/") {
            content {
                includeGroupByRegex("com\\.onyx.*")
                // transitive deps of the Onyx SDK that only this repo serves
                includeGroup("pub.devrel")
                includeGroup("com.tencent")
                includeGroup("com.jakewharton.hugo.fix")
            }
        }
        maven("https://jitpack.io") {
            content { includeGroupByRegex("com\\.github.*") }
        }
    }
}

rootProject.name = "nisaba"
include(":domain", ":scribe", ":infra", ":app", ":tools")

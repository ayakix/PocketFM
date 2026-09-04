pluginManagement {
    repositories {
        google {
            content {
                // Android 関連グループに絞ると依存解決が速い。
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

plugins {
    // 指定した JDK ツールチェーンがローカルに無ければ自動ダウンロードする。
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.8.0"
}

rootProject.name = "PocketFM"

include(":fm-radio", ":app")

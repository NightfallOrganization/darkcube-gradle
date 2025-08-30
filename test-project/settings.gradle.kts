pluginManagement {
    includeBuild("..")
    repositories {
        gradlePluginPortal()
        maven("https://nexus.darkcube.eu/repository/darkcube/") {
            name = "DarkCube"
            credentials(PasswordCredentials::class)
        }
    }
}

plugins {
    id("eu.darkcube.darkcube.settings")
}

include("subproject")

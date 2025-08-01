pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenLocal()
        maven("https://nexus.darkcube.eu/repository/darkcube/") {
            name = "DarkCube"
            credentials(PasswordCredentials::class)
        }
    }
}

plugins {
    id("eu.darkcube.darkcube.settings") version "1.8.0"
}

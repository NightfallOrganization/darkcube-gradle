plugins {
    `kotlin-dsl`
    `maven-publish`
    `java-gradle-plugin`
    checkstyle
}

group = "eu.darkcube.build"

repositories {
    gradlePluginPortal()
    mavenCentral()
}

dependencies {
    implementation("org.gradle.toolchains:foojay-resolver:0.8.0")
}

kotlin.jvmToolchain(8)

gradlePlugin {
    plugins {
        register("darkcube") {
            tags.add("darkcube")
            id = "eu.darkcube.darkcube"
            displayName = "DarkCube configuration"
            implementationClass = "eu.darkcube.build.DarkCubePluginKt"
        }
        register("darkcube-settings") {
            tags.add("darkcube")
            id = "eu.darkcube.darkcube.settings"
            displayName = "DarkCube settings configuration"
            implementationClass = "eu.darkcube.build.DarkCubeSettingsKt"
        }
    }
}

checkstyle {
    config = resources.text.fromFile("src/main/resources/assets/darkcube/checkstyle.xml")
    toolVersion = resources.text.fromFile("src/main/resources/assets/darkcube/checkstyle.version").asString()
}

publishing {
    repositories {
        maven("https://nexus.darkcube.eu/repository/darkcube/") {
            name = "DarkCube"
            credentials(PasswordCredentials::class)
        }
    }
}

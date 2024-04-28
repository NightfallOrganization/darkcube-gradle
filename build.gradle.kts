plugins {
    `kotlin-dsl`
    `maven-publish`
    `java-gradle-plugin`
    checkstyle
    id("io.github.goooler.shadow") version "8.1.7"
}

group = "eu.darkcube.build"

repositories {
    gradlePluginPortal()
    mavenCentral()
}

val embed = configurations.register("embed")
configurations.api.configure { extendsFrom(embed.get()) }

dependencies {
    implementation("org.gradle.toolchains:foojay-resolver:0.8.0")
    embed("org.ow2.asm:asm-commons:9.7")
}

tasks {
    shadowJar.configure {
        configurations = listOf(embed.get())
        relocationPrefix = "eu.darkcube.build.libs"
        isEnableRelocation = true
    }
}

kotlin.jvmToolchain(8)

gradlePlugin {
    plugins {
        register("darkcube") {
            tags.add("darkcube")
            id = "eu.darkcube.darkcube"
            displayName = "DarkCube configuration"
            implementationClass = "eu.darkcube.build.DarkCubePlugin"
        }
        register("darkcube-settings") {
            tags.add("darkcube")
            id = "eu.darkcube.darkcube.settings"
            displayName = "DarkCube settings configuration"
            implementationClass = "eu.darkcube.build.DarkCubeSettings"
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

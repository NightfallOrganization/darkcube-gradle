plugins {
    `kotlin-dsl`
    `maven-publish`
    `java-gradle-plugin`
    checkstyle
    alias(libs.plugins.shadow)
}

group = "eu.darkcube.build"

repositories {
    gradlePluginPortal()
    mavenCentral()
}

val embed = configurations.register("embed")
configurations.api.configure { extendsFrom(embed.get()) }

dependencies {
    implementation(libs.foojay.resolver)
    embed(libs.bcprov.jdk18on)
    embed(libs.jna)
    embed(libs.jna.platform)
    embed(libs.jsch)
    embed(libs.asm.commons)
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

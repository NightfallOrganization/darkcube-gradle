plugins {
    id("eu.darkcube.darkcube")
    java
    `maven-publish`
}

group = "eu.darkcube.system.test"

gradle.taskGraph.whenReady {
    allTasks.filterIsInstance<JavaExec>().forEach {
        it.setExecutable(it.javaLauncher.get().executablePath.asFile.absolutePath)
    }
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}
tasks.withType<JavaExec>().configureEach {
    javaLauncher = javaToolchains.launcherFor(java.toolchain)
}

val remap = configurations.create("remap")

repositories {
    mavenLocal()
    mavenCentral()
    maven("https://libraries.minecraft.net")
}

dependencies {
    remap("com.google.code.gson:gson:2.10.1")
    remap(libs.bundles.adventure)
    remap("org.jetbrains:annotations:24.1.0")
    remap("com.mojang:brigadier:1.0.18")

    implementation("eu.darkcube.system:libs:1.0-SNAPSHOT")
}

val conf = sourceRemapper.remap(remap, "eu.darkcube.system.test.libs", configurations.named("implementation"))

publishing {
    repositories{
        maven("https://nexus.darkcube.eu/repository/darkcube/") {
            name = "DarkCube"
            credentials(PasswordCredentials::class)
        }
    }
    publications {
        register<MavenPublication>("test") {
            from(conf.component.configureJava(sourceSets.main, tasks.jar).component)
        }
    }
}

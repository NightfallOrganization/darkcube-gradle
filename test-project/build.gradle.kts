plugins {
    id("eu.darkcube.darkcube")
    java
}

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
    remap("net.kyori:adventure-api:4.16.0")
    remap("net.kyori:adventure-platform-bukkit:4.3.2")
    remap("org.jetbrains:annotations:24.1.0")
    remap("com.mojang:brigadier:1.0.18")
}

sourceRemapper.remap(remap, "eu.darkcube.system.libs", configurations.named("implementation"))

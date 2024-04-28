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


dependencies {
    remap("com.google.code.gson:gson:2.10.1")
    remap("net.kyori:adventure-api:4.16.0")
}

sourceRemapper.remap(remap, "eu.darkcube.system.libs", configurations.named("implementation"))

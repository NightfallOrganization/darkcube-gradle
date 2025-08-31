plugins {
    `java-library`
    `maven-publish`
    id("eu.darkcube.darkcube")
}

gradle.taskGraph.whenReady {
    allTasks.filterIsInstance<JavaExec>().forEach {
        it.setExecutable(it.javaLauncher.get().executablePath.asFile.absolutePath)
    }
}

tasks.withType<JavaExec>().configureEach {
    javaLauncher = javaToolchains.launcherFor(java.toolchain)
}

val embedded = configurations.dependencyScope("embedded")
val remapped = remapper.remap("eu.darkcube.system.libs.test", embedded)

dependencies {
    embedded(libs.brigadier)
    embedded(libs.gson)
    embedded(libs.annotations)
    embedded(libs.caffeine)
    embedded(libs.fastutil)
    embedded(libs.bundles.adventure) {
        exclude(group = "com.google.code.gson")
    }
}

configurations.api { extendsFrom(remapped.remappedRuntimeConfiguration.get()) }
configurations.sourcesElements { extendsFrom(remapped.remappedSourceConfiguration.get()) }

remapped.createPublications()

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

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}
tasks.withType<JavaExec>().configureEach {
    javaLauncher = javaToolchains.launcherFor(java.toolchain)
}

configurations.dependencyScope("embedded")
val embedded = configurations.named("embedded") {}
val remapped = remapper.remap("eu.darkcube.system.libs.test", embedded)

tasks.register<Sync>("testTransformer") {
    from(remapped.sourceConfiguration) {
        into("sources")
    }
    from(remapped.runtimeConfiguration) {
        into("runtime")
    }

    from(remapped.remappedSourceConfiguration) {
        into("remappedSources")
    }
    from(remapped.remappedRuntimeConfiguration) {
        into("remappedRuntime")
    }
    into(layout.buildDirectory.dir("testTransformer"))
}

configurations {
    sourcesElements {
        extendsFrom(remapped.remappedSourceConfiguration.get())
    }
    runtimeElements {
        extendsFrom(remapped.remappedRuntimeConfiguration.get())
    }
}
configurations.api {
    extendsFrom(remapped.remappedRuntimeConfiguration.get())
}

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

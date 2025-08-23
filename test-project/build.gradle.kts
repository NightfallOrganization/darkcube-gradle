import eu.darkcube.build.remapper.Remapped
import eu.darkcube.build.remapper.RemapperTransform
import eu.darkcube.build.remapper.InputType

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
val embedded = configurations.named("embedded") {
}

val embeddedRuntime = configurations.resolvable("embeddedRuntime") {
    extendsFrom(embedded.get())
    attributes {
        attribute(Remapped.REMAPPED_ATTRIBUTE, true)
    }
}
val embeddedSources = configurations.resolvable("embeddedSources") {
    extendsFrom(embedded.get())

    attributes {
        attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage.JAVA_RUNTIME));
    }
    attributes {
        attribute(Remapped.REMAPPED_ATTRIBUTE, true)
        attribute(Category.CATEGORY_ATTRIBUTE, objects.named(Category.DOCUMENTATION));
        attribute(Bundling.BUNDLING_ATTRIBUTE, objects.named(Bundling.EXTERNAL));
        attribute(DocsType.DOCS_TYPE_ATTRIBUTE, objects.named(DocsType.SOURCES));
    }
}

tasks.register<Sync>("testTransformer") {
    from(embeddedSources.map {
        it.incoming.artifactView {
            withVariantReselection()
        }
    }.map { it.files }) {
        into("sources")
    }
    from(embeddedRuntime.map { it.files }) {
        into("runtime")
    }
    into(layout.buildDirectory.dir("testTransformer"))
}

configurations.api {
    extendsFrom(embedded.get())
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

    attributesSchema {
        attribute(Remapped.REMAPPED_ATTRIBUTE)
    }

    artifactTypes.getByName("jar") {
        attributes.attribute(Remapped.REMAPPED_ATTRIBUTE, false)
    }
}

dependencies {
    registerTransform(RemapperTransform::class) {
        parameters {
            namespace = "eu.darkcube.system.test.libs"
            type = InputType.BINARY
        }

        from.attribute(Category.CATEGORY_ATTRIBUTE, objects.named(Category.LIBRARY))
            .attribute(Remapped.REMAPPED_ATTRIBUTE, false)
        to.attribute(Category.CATEGORY_ATTRIBUTE, objects.named(Category.LIBRARY))
            .attribute(Remapped.REMAPPED_ATTRIBUTE, true)
    }
    registerTransform(RemapperTransform::class) {
        parameters {
            namespace = "eu.darkcube.system.test.libs"
            type = InputType.SOURCES
        }

        from.attribute(Category.CATEGORY_ATTRIBUTE, objects.named(Category.DOCUMENTATION))
        from.attribute(DocsType.DOCS_TYPE_ATTRIBUTE, objects.named(DocsType.SOURCES))
        from.attribute(Remapped.REMAPPED_ATTRIBUTE, false)

        to.attribute(Category.CATEGORY_ATTRIBUTE, objects.named(Category.DOCUMENTATION))
        to.attribute(DocsType.DOCS_TYPE_ATTRIBUTE, objects.named(DocsType.SOURCES))
        to.attribute(Remapped.REMAPPED_ATTRIBUTE, true)
    }
}

val rescp = configurations.resolvable("ccp") { extendsFrom(configurations.compileClasspath.get()) }

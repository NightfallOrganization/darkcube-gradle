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

repositories {
    this.ivy {
        this.content {
        }
        this.patternLayout {
        }
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
    attributes.attribute(Remapped.REMAPPED_ATTRIBUTE, true)
}

val trs = configurations.resolvable("trs") {
    extendsFrom(embedded.get())
}

tasks.register<Sync>("testTransformer") {
    from(trs)
    into(layout.buildDirectory.dir("testTransformer"))
}

dependencies {
    embedded(libs.brigadier) {
        attributes {
            attribute(Remapped.REMAPPED_ATTRIBUTE, true)
        }
    }
    embedded(libs.gson)
    embedded(libs.annotations)
    embedded(libs.caffeine)
    embedded(libs.bundles.adventure) {
        exclude(group = "com.google.code.gson")
    }

    attributesSchema {
        attribute(Remapped.REMAPPED_ATTRIBUTE)
    }

    api(embedded.map { it.incoming.files })

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
            .attribute(DocsType.DOCS_TYPE_ATTRIBUTE, objects.named(DocsType.SOURCES))
            .attribute(Remapped.REMAPPED_ATTRIBUTE, false)

        to.attribute(Category.CATEGORY_ATTRIBUTE, objects.named(Category.DOCUMENTATION))
            .attribute(DocsType.DOCS_TYPE_ATTRIBUTE, objects.named(DocsType.SOURCES))
            .attribute(Remapped.REMAPPED_ATTRIBUTE, true)
    }
}

val rescp = configurations.resolvable("ccp") { extendsFrom(configurations.compileClasspath.get()) }

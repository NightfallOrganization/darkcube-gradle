package eu.darkcube.build

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.repositories.PasswordCredentials
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.plugins.catalog.VersionCatalogPlugin
import org.gradle.api.plugins.quality.Checkstyle
import org.gradle.api.plugins.quality.CheckstyleExtension
import org.gradle.api.plugins.quality.CheckstylePlugin
import org.gradle.api.publish.PublishingExtension
import org.gradle.api.tasks.JavaExec
import org.gradle.api.tasks.bundling.AbstractArchiveTask
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.api.tasks.javadoc.Javadoc
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.jvm.toolchain.JavaToolchainService
import org.gradle.jvm.toolchain.JvmVendorSpec
import org.gradle.kotlin.dsl.*
import java.nio.charset.StandardCharsets

class DarkCubePlugin : Plugin<Project> {
    override fun apply(project: Project) {
        project.plugins.apply(CheckstylePlugin::class)
        project.plugins.apply(VersionCatalogPlugin::class)
        project.extensions.getByType<CheckstyleExtension>().run {
            val cl = this@DarkCubePlugin.javaClass.classLoader
            val url = cl.getResource("assets/darkcube/checkstyle.xml")!!
            val resource = project.resources.text.fromUri(url)
            config = resource

            toolVersion = cl.getResourceAsStream("assets/darkcube/checkstyle.version")!!.readBytes().decodeToString()

            if (project.rootProject == project) {
                val checkstyleConfig = url.readText()
                project.tasks.register<GenerateCheckstyle>("generateCheckstyle", checkstyleConfig).configure {
                    this.group = "darkcube"
                }
            }
        }
        project.extensions.findByName("buildScan")?.withGroovyBuilder {
            setProperty("termsOfServiceUrl", "https://gradle.com/terms-of-service")
            setProperty("termsOfServiceAgree", "yes")
        }
        project.extensions.findByType<PublishingExtension>()?.run {
            repositories {
                maven("https://nexus.darkcube.eu/repository/darkcube/") {
                    name = "DarkCube"
                    credentials(PasswordCredentials::class)
                }
            }
        }

        project.repositories {
            maven("https://nexus.darkcube.eu/repository/darkcube-group/") {
                name = "DarkCube"
                credentials(PasswordCredentials::class)
            }
        }

        project.pluginManager.withPlugin("java-base") {
            val javaPluginExtension = project.extensions.getByType<JavaPluginExtension>()
            val toolchainService = project.extensions.getByType<JavaToolchainService>()

            javaPluginExtension.toolchain {
                languageVersion = JavaLanguageVersion.of(22)
                vendor = JvmVendorSpec.ADOPTIUM
            }

            project.tasks.withType<Checkstyle>().configureEach {
                javaLauncher = toolchainService.launcherFor { javaPluginExtension.toolchain }
            }
            project.tasks.withType<JavaExec>().configureEach {
                javaLauncher = toolchainService.launcherFor { javaPluginExtension.toolchain }
            }
        }
        project.pluginManager.withPlugin("java-library") {
            project.pluginManager.withPlugin("maven-publish") {
                val javaPluginExtension = project.extensions.getByType<JavaPluginExtension>()

                javaPluginExtension.withSourcesJar()
                javaPluginExtension.withJavadocJar()
            }
        }

        project.tasks.withType<JavaCompile>().configureEach {
            options.encoding = StandardCharsets.UTF_8.name()
            options.compilerArgs.add("--enable-preview")
            options.compilerArgs.add("-Xlint:-preview")
            options.isIncremental = true
            options.isDeprecation = false
            options.isWarnings = false
        }
        project.tasks.withType<Javadoc>().configureEach {
            options.encoding = StandardCharsets.UTF_8.name()
            options.quiet()
            isFailOnError = false
            isVerbose = false
        }
        project.tasks.withType<AbstractArchiveTask>().configureEach {
            archiveVersion.convention("")
        }
        val remapperExtension = project.extensions.create<SourceRemapperExtension>("sourceRemapper")
        remapperExtension.setupIvyRepository()
    }
}
@file:Suppress("UnstableApiUsage")

package eu.darkcube.build.remapper

import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import org.gradle.api.component.SoftwareComponentFactory
import org.gradle.api.internal.tasks.TaskDependencyFactory
import org.gradle.api.provider.Provider
import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.kotlin.dsl.findByType
import org.gradle.kotlin.dsl.register
import javax.inject.Inject
import kotlin.io.path.exists

class RemappedConfiguration(
    private val extension: RemapperExtension,
    private val project: Project,
    private val componentFactory: SoftwareComponentFactory,
    private val namespace: String,
    private val repository: RemappedRepository,
    private val remappedSourceModules: Provider<out Iterable<ResolvedDependencyTree>>,
    private val remappedRuntimeModules: Provider<out Iterable<ResolvedDependencyTree>>,
    val modules: Provider<out Iterable<ResolvedDependencyTree>>,
    val runtimeConfiguration: Provider<out Configuration>,
    val sourceConfiguration: Provider<out Configuration>,
    val remappedRuntimeConfiguration: Provider<out Configuration>,
    val remappedSourceConfiguration: Provider<out Configuration>
) {

    fun createPublications() {
        project.afterEvaluate {
            val publishing = project.extensions.findByType<PublishingExtension>() ?: return@afterEvaluate
            val projectVersion = project.version.toString()
            val projectGroup = project.group.toString()
            val projectName = project.name
            publishing.publications {
                modules.get().forEach { dependencyTree ->
                    val originalGroup = dependencyTree.group
                    val originalName = dependencyTree.name
                    val remappedModule = dependencyTree.module.remapped(projectGroup, projectName, projectVersion)
                    val publicationName = "${remappedModule.group}-${remappedModule.name}-${remappedModule.version}"
                    register<MavenPublication>(publicationName) {
                        this.groupId = remappedModule.group
                        this.artifactId = remappedModule.name
                        this.version = remappedModule.version
                        val artifactPath = repository.artifactPath(
                            projectGroup, projectName, projectVersion, originalGroup, originalName, remappedModule
                        )
                        val sourcesPath = repository.artifactPath(
                            projectGroup, projectName, projectVersion, originalGroup, originalName, remappedModule
                        )
                        this.artifact(artifactPath)
                        if (sourcesPath.exists()) {
                            this.artifact(sourcesPath) {
                                this.classifier = "sources"
                            }
                        }

                        pom.withXml {
                            val element = this.asElement()
                            val dependenciesElement = element.ownerDocument.createElement("dependencies")

                            val dependencies = dependencyTree.dependencies.dependencies.map { tree ->
                                tree.module.remapped(projectGroup, projectName, projectVersion)
                            }

                            dependencies.forEach {
                                val dependencyElement = element.ownerDocument.createElement("dependency")
                                dependencyElement.appendChild(
                                    element.ownerDocument.createElement("groupId").apply { textContent = it.group })
                                dependencyElement.appendChild(
                                    element.ownerDocument.createElement("artifactId").apply { textContent = it.name })
                                dependencyElement.appendChild(
                                    element.ownerDocument.createElement("version")
                                        .apply { textContent = projectVersion })
                                dependencyElement.appendChild(
                                    element.ownerDocument.createElement("scope").apply { textContent = "compile" })
                                dependenciesElement.appendChild(dependencyElement)
                            }

                            element.appendChild(dependenciesElement)
                        }
                    }
                }
            }
        }
    }

    interface TaskDepFac {
        @get:Inject
        val fac: TaskDependencyFactory
    }
}
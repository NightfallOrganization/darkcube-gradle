package eu.darkcube.build.remapper

import eu.darkcube.build.DarkCubePlugin
import eu.darkcube.build.Module
import eu.darkcube.build.remapper.ResolvedDependencies.Companion.resolveDependencies
import eu.darkcube.build.sha256asHex
import org.gradle.api.NamedDomainObjectProvider
import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.ExternalModuleDependency
import org.gradle.api.artifacts.dsl.DependencyHandler
import org.gradle.api.artifacts.repositories.IvyArtifactRepository
import org.gradle.api.artifacts.repositories.RepositoryContentDescriptor
import org.gradle.api.component.SoftwareComponentFactory
import org.gradle.api.provider.Provider
import org.gradle.kotlin.dsl.create
import org.gradle.kotlin.dsl.mapProperty
import org.gradle.kotlin.dsl.repositories
import java.nio.file.Path
import java.nio.file.Paths
import javax.inject.Inject

private val pluginHash: String by lazy { hashDarkCubeJar() }

private fun hashDarkCubeJar(): String {
    return Paths.get(DarkCubePlugin::class.java.protectionDomain.codeSource.location.toURI()).sha256asHex()
}

open class RemapperExtension @Inject constructor(
    private val project: Project, private val componentFactory: SoftwareComponentFactory
) {
    private val cachesPathRoot: Path =
        project.gradle.gradleUserHomeDir.toPath().resolve("caches").resolve("darkcube-source-remapper")
    private val cachesPath: Path = cachesPathRoot.resolve(pluginHash)
    private val repository = RemappedRepository(cachesPath)
    private val content: RepositoryContentDescriptor

    init {
        var c: RepositoryContentDescriptor? = null
        project.repositories {
            ivy {
                url = repository.uri
                patternLayout {
                    artifact(IvyArtifactRepository.MAVEN_ARTIFACT_PATTERN)
                    ivy(IvyArtifactRepository.MAVEN_IVY_PATTERN)
                    setM2compatible(true)
                }
                content {
                    c = this
                }
            }
        }
        content = c!!
    }

    fun remap(namespace: String, configuration: NamedDomainObjectProvider<out Configuration>): RemappedConfiguration {
        val resolvableConfiguration = project.configurations.resolvable("${configuration.name}Resolvable") {
            extendsFrom(configuration.get())
            isVisible = false
        }
        val dependencyHandler = project.dependencies
        fun NamedDomainObjectProvider<out Configuration>.resolved() =
            project.objects.mapProperty<Module, ResolvedDependencyTree>().also { property ->
                property.set(this.resolveDependencies())
                property.finalizeValueOnRead()
            }

        val resolvedDependencies = resolvableConfiguration.resolved()
        val runtimeConfiguration = project.configurations.resolvable("${configuration.name}Runtime") {
            isVisible = false
            addDependencies(resolvedDependencies, dependencyHandler)
        }
        val sourceConfiguration = project.configurations.resolvable("${configuration.name}Source") {
            isVisible = false
            addDependencies(resolvedDependencies, dependencyHandler, "sources")
        }

        fun ResolvedDependencyTree.asResolvedModule() = ResolvedModule(module, file!!)
        val remappedRuntimeConfiguration = project.configurations.resolvable("${configuration.name}RemappedRuntime") {
            isVisible = false
            val projectVersion = project.version.toString()
            val resolved = runtimeConfiguration.resolved()
            addDependencies(namespace, projectVersion, resolved, dependencyHandler)

            project.afterEvaluate {
                println("Pre resolve remapped runtime")
                val moduleFiles = resolved.get()
                val deps = resolvedDependencies.get()

                moduleFiles.values.stream().map { tree ->
                    tree.asResolvedModule() to tree
                }.parallel().forEach { pair ->
                    repository.ensureIntegrity(namespace, projectVersion, pair.first, InputType.BINARY) {
                        deps[pair.second.module]!!.dependencies.transitiveList.map { moduleFiles[it.module]!! }
                            .map { it.asResolvedModule() }
                    }
                }
            }
        }
        val remappedSourceConfiguration = project.configurations.resolvable("${configuration.name}RemappedSource") {
            isVisible = false
            val projectVersion = project.version.toString()
            val resolved = sourceConfiguration.resolved()
            addDependencies(namespace, projectVersion, resolved, dependencyHandler, "sources")

            project.afterEvaluate {
                println("Pre resolve remapped sources")
                val moduleFiles = resolved.get()
                val deps = resolvedDependencies.get()

                moduleFiles.values.stream().map { tree ->
                    tree.asResolvedModule() to tree
                }.parallel().forEach { pair ->
                    repository.ensureIntegrity(namespace, projectVersion, pair.first, InputType.SOURCES) {
                        deps[pair.second.module]!!.dependencies.transitiveList.map { moduleFiles[it.module]!! }
                            .map { it.asResolvedModule() }
                    }
                }
            }
        }
        content.onlyForConfigurations(remappedRuntimeConfiguration.name, remappedSourceConfiguration.name)
        println(repository.uri)

        return RemappedConfiguration(
            namespace,
            runtimeConfiguration,
            sourceConfiguration,
            remappedRuntimeConfiguration,
            remappedSourceConfiguration
        )
    }

    private fun Configuration.addDependencies(
        namespace: String,
        version: String,
        resolvedDependencies: Provider<out Map<Module, ResolvedDependencyTree>>,
        dependencyHandler: DependencyHandler,
        classifier: String? = null
    ) = addDependencies(resolvedDependencies.map { it.keys }) {
        dependencyHandler.create(
            "$namespace.${it.group}", it.name, version, classifier = classifier
        )
    }

    private fun Configuration.addDependencies(
        resolvedDependencies: Provider<out Map<Module, ResolvedDependencyTree>>,
        dependencyHandler: DependencyHandler,
        classifier: String? = null
    ) = addDependencies(resolvedDependencies.map { it.keys }) {
        dependencyHandler.create(
            it.group, it.name, it.version, classifier = classifier
        )
    }

    private fun Configuration.addDependencies(
        resolvedDependencies: Provider<out Iterable<Module>>, dependencyCreator: (Module) -> ExternalModuleDependency
    ) {
        dependencies.addAllLater(resolvedDependencies.map { iterable ->
            iterable.map {
                dependencyCreator(it).apply { isTransitive = false }
            }
        })
    }
}

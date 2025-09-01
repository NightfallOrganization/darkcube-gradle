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
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject

private val pluginHash: String by lazy { hashDarkCubeJar() }

private fun hashDarkCubeJar(): String {
    return Paths.get(DarkCubePlugin::class.java.protectionDomain.codeSource.location.toURI()).sha256asHex()
}

open class RemapperExtension @Inject constructor(
    private val project: Project, private val componentFactory: SoftwareComponentFactory
) {
    internal val idName = AtomicInteger()
    internal val idApi = AtomicInteger()
    internal val idRuntime = AtomicInteger()
    internal val idSources = AtomicInteger()
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

        val resolvedDependencies = resolvableConfiguration.resolved()
        val runtimeConfiguration = project.configurations.resolvable("${configuration.name}Runtime") {
            isVisible = false
            addDependencies(resolvedDependencies, dependencyHandler)
        }
        val sourceConfiguration = project.configurations.resolvable("${configuration.name}Source") {
            isVisible = false
            addDependencies(resolvedDependencies, dependencyHandler, "sources")
        }

        val remappedRuntimeConfiguration = project.configurations.resolvable("${configuration.name}RemappedRuntime") {
            isVisible = false
            val projectVersion = project.version.toString()
            val resolved = runtimeConfiguration.resolved()
            addDependencies(
                namespace,
                project.group.toString(),
                project.name,
                projectVersion,
                resolved,
                resolvedDependencies,
                dependencyHandler,
                InputType.BINARY
            )
        }
        val remappedSourceConfiguration = project.configurations.resolvable("${configuration.name}RemappedSource") {
            isVisible = false
            val projectVersion = project.version.toString()
            val resolved = sourceConfiguration.resolved()
            addDependencies(
                namespace,
                project.group.toString(),
                project.name,
                projectVersion,
                resolved,
                resolvedDependencies,
                dependencyHandler,
                InputType.SOURCES,
                "sources"
            )
        }

        val modules = resolvedDependencies.map { it.values }
        val remappedSourceModules = remappedSourceConfiguration.resolved().map { it.values }
        val remappedRuntimeModules = remappedRuntimeConfiguration.resolved().map { it.values }

        project.afterEvaluate {
            remappedSourceModules.get()
            remappedRuntimeModules.get()
        }

        return RemappedConfiguration(
            this,
            project,
            componentFactory,
            namespace,
            repository,
            remappedSourceModules,
            remappedRuntimeModules,
            modules,
            runtimeConfiguration,
            sourceConfiguration,
            remappedRuntimeConfiguration,
            remappedSourceConfiguration
        )
    }

    fun NamedDomainObjectProvider<out Configuration>.resolved() =
        project.objects.mapProperty<Module, ResolvedDependencyTree>().also { property ->
            property.set(this.resolveDependencies())
            property.finalizeValueOnRead()
        }

    private fun ResolvedDependencyTree.asResolvedModule() = ResolvedModule(module, file!!)

    private fun ensureFilesAreInRepository(
        namespace: String,
        projectGroup: String,
        projectName: String,
        projectVersion: String,
        resolved: Provider<out Map<Module, ResolvedDependencyTree>>,
        resolvedDependencies: Provider<out Map<Module, ResolvedDependencyTree>>,
        inputType: InputType
    ) {
        val moduleFiles = resolved.get()
        val deps = resolvedDependencies.get()

        val start = System.nanoTime()
        val count = AtomicInteger()
        moduleFiles.values.stream().map { tree ->
            tree.asResolvedModule() to tree
        }.parallel().forEach { pair ->
            if (repository.ensureIntegrity(
                    projectGroup, projectName, namespace, projectVersion, pair.first, inputType
                ) {
                    deps[pair.second.module]!!.dependencies.transitiveList.map { moduleFiles[it.module]!! }
                        .map { it.asResolvedModule() }
                }
            ) {
                count.incrementAndGet()
            }
        }
        if (count.get() > 0) {
            val took = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)
            project.logger.lifecycle("Remapped {} files ({}) in {}ms", count.get(), inputType, took)
        }
    }

    private fun Configuration.addDependencies(
        namespace: String,
        projectGroup: String,
        projectName: String,
        version: String,
        resolved: Provider<out Map<Module, ResolvedDependencyTree>>,
        resolvedDependencies: Provider<out Map<Module, ResolvedDependencyTree>>,
        dependencyHandler: DependencyHandler,
        inputType: InputType,
        classifier: String? = null
    ) = addDependencies(resolved.map { it.keys }.map {
        it.apply {
            ensureFilesAreInRepository(
                namespace, projectGroup, projectName, version, resolved, resolvedDependencies, inputType
            )
        }
    }) {
        dependencyHandler.create(
            dependencyGroup(projectGroup, projectName, it.group), it.name, version, classifier = classifier
        )
    }

    private fun Configuration.addDependencies(
        resolvedDependencies: Provider<out Map<Module, ResolvedDependencyTree>>,
        dependencyHandler: DependencyHandler,
        classifier: String? = null
    ) = addDependencies(resolvedDependencies.map { it.keys }) {
        dependencyHandler.create(
            it.group, it.name, it.version, classifier = classifier
        ).apply { isTransitive = false }
    }

    private fun Configuration.addDependencies(
        resolvedDependencies: Provider<out Iterable<Module>>, dependencyCreator: (Module) -> ExternalModuleDependency
    ) {
        dependencies.addAllLater(resolvedDependencies.map { iterable ->
            iterable.map {
                dependencyCreator(it)
            }
        })
    }
}

internal fun dependencyGroup(projectGroup: String, projectName: String, originalGroup: String): String {
    return "${projectGroup}.${projectName}.${originalGroup}"
}

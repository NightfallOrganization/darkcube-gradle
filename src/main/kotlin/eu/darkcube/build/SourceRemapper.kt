package eu.darkcube.build

import org.gradle.api.NamedDomainObjectProvider
import org.gradle.api.Project
import org.gradle.api.artifacts.*
import org.gradle.api.artifacts.dsl.DependencyHandler
import org.gradle.api.artifacts.repositories.IvyArtifactRepository
import org.gradle.api.artifacts.type.ArtifactTypeDefinition
import org.gradle.api.attributes.Bundling
import org.gradle.api.attributes.Category
import org.gradle.api.attributes.LibraryElements
import org.gradle.api.attributes.Usage
import org.gradle.api.attributes.java.TargetJvmVersion
import org.gradle.api.component.AdhocComponentWithVariants
import org.gradle.api.component.SoftwareComponentFactory
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.provider.Provider
import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.api.tasks.SourceSet
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.kotlin.dsl.*
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.commons.ClassRemapper
import org.objectweb.asm.commons.Remapper
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger
import java.util.regex.Matcher
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.inject.Inject
import kotlin.collections.set
import kotlin.io.path.*

@Suppress("unused")
open class SourceRemapperExtension @Inject constructor(
    private val project: Project, private val componentFactory: SoftwareComponentFactory
) {

    init {
        id.set(0)
    }

    private val cachesPathRoot: Path =
        project.gradle.gradleUserHomeDir.toPath().resolve("caches").resolve("darkcube-source-remapper")
    private val cachesPath = cachesPathRoot.resolve(darkcubeHash)
    internal val repositoryPath = cachesPath.resolve("repository")

    /**
     * Remaps all files in the configuration to the given namespace
     */
    fun remap(
        configuration: Configuration, namespace: String, target: NamedDomainObjectProvider<Configuration>
    ): RemapConfiguration {
        val task = RemapTask(this, project, configuration, namespace, target)
        task.remap()
        createPublications(project, namespace, task.artifacts, this)
        val artifactMap = HashMap<ModuleVersionIdentifier, RemappedModule>()
        task.artifacts.forEach {
            collectArtifacts(task, artifactMap, it.key)
        }
        val artifacts = ArrayList<RemappedModule>(artifactMap.values)
        return RemapConfiguration(namespace, project.version.toString(), artifacts, createComponent(task))
    }

    private fun createComponent(task: RemapTask): RemapComponent {
        val component = componentFactory.adhoc("remap-${id.incrementAndGet()}")
        project.components.add(component)
        val mainSourceSet = project.extensions.getByType<JavaPluginExtension>().sourceSets["main"]
        val apiConfigurationName = mainSourceSet.apiElementsConfigurationName
        val apiConfiguration = project.configurations[apiConfigurationName]
        val artifacts = ArrayList(task.artifacts.values.map { it.remapped(task.namespace) })
        val projectGroup = project.group.toString()
        val projectName = project.name
        val namespace = task.namespace
//        val outgoing = project.configurations.create("remap-configuration-${id.get()}") {
//            isVisible = false
//        }
        val deps = apiConfiguration.allDependencies.mapNotNull {
            if (it is ExternalModuleDependency) {
                val foundModule =
                    artifacts.singleOrNull { module -> module.group == it.group && module.name == it.name && module.version == it.version }
                if (foundModule != null) {
                    return@mapNotNull project.dependencies.create(
                        group = publishedGroup(projectGroup, projectName, foundModule.undoRemap(namespace).group),
                        name = it.name,
                        version = project.version.toString(),
                        configuration = it.targetConfiguration
                    )
                }
            }
            it
        }
//        addAll(deps)
        val outgoing = project.configurations.detachedConfiguration(*deps.toTypedArray())
        outgoing.attributes.attribute(Usage.USAGE_ATTRIBUTE, project.objects.named(Usage.JAVA_API))
        outgoing.attributes.attribute(Category.CATEGORY_ATTRIBUTE, project.objects.named(Category.LIBRARY))
        outgoing.attributes.attribute(
            LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE, project.objects.named(LibraryElements.JAR)
        )
        outgoing.attributes.attribute(Bundling.BUNDLING_ATTRIBUTE, project.objects.named(Bundling.EXTERNAL))
        component.addVariantsFromConfiguration(outgoing) {
            mapToMavenScope("compile")
        }
        return RemapComponent(component, outgoing, project)
    }

    private fun collectArtifacts(
        task: RemapTask, artifacts: MutableMap<ModuleVersionIdentifier, RemappedModule>, id: ModuleVersionIdentifier
    ): RemappedModule {
        artifacts[id]?.let { return it }
        val artifact: PreparedModule = task.artifacts[id]!!
        val dependencies = ArrayList(artifact.dependencies.map {
            collectArtifacts(
                task, artifacts, it.resolvedArtifact.moduleVersion.id
            )
        })
        val remapped = artifact.remapped(task.namespace)
        val module = RemappedModule(
            id.group, id.name, id.version, remapped.group, remapped.name, remapped.version, dependencies
        )
        artifacts[id] = module
        return module
    }

    internal fun setupIvyRepository() {
        project.repositories {
            ivy {
                url = repositoryPath.toUri()
                patternLayout {
                    artifact(IvyArtifactRepository.MAVEN_ARTIFACT_PATTERN)
                    ivy(IvyArtifactRepository.MAVEN_IVY_PATTERN)
                    setM2compatible(true)
                }
            }
        }
    }

    companion object {
        internal val id = AtomicInteger()
    }
}

@Suppress("unused")
class RemapComponent internal constructor(
    val component: AdhocComponentWithVariants, private val outgoing: Configuration, private val project: Project
) {
    fun configureJava(sourceSet: Provider<SourceSet>, jarTask: Any): RemapComponent {
        outgoing.attributes {
            attributeProvider(TargetJvmVersion.TARGET_JVM_VERSION_ATTRIBUTE, project.providers.provider {
                val compileJava = project.tasks.named<JavaCompile>(sourceSet.get().compileJavaTaskName)
                compileJava.get().javaCompiler.get().metadata.languageVersion.asInt()
            })
        }
        outgoing.outgoing {
            this.artifact(jarTask)
            attributes.attribute(ArtifactTypeDefinition.ARTIFACT_TYPE_ATTRIBUTE, ArtifactTypeDefinition.JAR_TYPE)
        }
        return this
    }
}

@Suppress("unused")
class RemapConfiguration internal constructor(
    val namespace: String,
    private val projectVersion: String,
    val artifacts: List<RemappedModule>,
    val component: RemapComponent
)


data class RemappedModule(
    val group: String,
    val artifact: String,
    val version: String,
    val remappedGroup: String,
    val remappedArtifact: String,
    val remappedVersion: String,
    val dependencies: List<RemappedModule>
)

private fun Path.verifyIntegrity(): Boolean {
    if (!exists()) return false
    val sha256 = hashSha256Path()
    if (!sha256.exists()) return false
    val computedSha = sha256asHex()
    val expectedSha = sha256.readText()
    return computedSha == expectedSha
}

private fun Path.saveIntegrity() {
    val sha256 = hashSha256Path()
    sha256.writeText(sha256asHex())
}

private fun Path.hashSha256Path(): Path {
    return parent.resolve("$name.sha256")
}

private fun DependencyHandler.createDependency(module: Module): Dependency {
    return create(module.group, module.name, module.version)
}

internal class RemapTask(
    private val extension: SourceRemapperExtension,
    private val project: Project,
    private val configuration: Configuration,
    internal val namespace: String,
    private val targetConfiguration: NamedDomainObjectProvider<Configuration>
) {
    internal val artifacts = HashMap<ModuleVersionIdentifier, PreparedModule>()
    private val sourceArtifacts = HashMap<ModuleVersionIdentifier, PreparedModule>()
    private val remapped = HashSet<ModuleVersionIdentifier>()
    private val names = HashMap<String, String>()
    private val sourceNames = HashMap<String, String>()
    private val sourceClassNames = HashMap<String, String>()

    fun remap() {
        collectArtifacts()
        remapAll()
    }

    private fun collectAllNames(): Boolean {
        val hasWork = artifacts.keys.filter {
            val artifact = artifacts[it]!!
            val sourceArtifact = sourceArtifacts[it]
            val module = artifact.remapped(namespace)
            val directory = artifactDirectory(module)
            if (directory.artifact(module).verifyIntegrity()) {
                if (sourceArtifact != null) {
                    if (directory.sourceArtifact(module).verifyIntegrity()) {
                        return@filter false
                    }
                }
            }
            true
        }.isNotEmpty()
        if (!hasWork) return false
        artifacts.keys.forEach { collectNames(it) }
        sourceNames.forEach {
            if (it.key.endsWith(".java")) {
                sourceClassNames[it.key.dropLast(5).replace('/', '.')] = it.value.dropLast(5).replace('/', '.')
            }
        }
        return true
    }

    private fun collectNames(id: ModuleVersionIdentifier) {
        val artifact = artifacts[id]!!
        val sourceArtifact = sourceArtifacts[id]
        collectNames(artifact.path, names)
        if (sourceArtifact != null) {
            collectNames(sourceArtifact.path, sourceNames)
        }
    }

    private fun remapAll() {
        val hasWork = collectAllNames()
        artifacts.keys.forEach { remap(hasWork, it) }
    }

    private fun remap(hasWork: Boolean, id: ModuleVersionIdentifier) {
        if (!remapped.add(id)) throw IllegalStateException()

        val artifact = artifacts[id]!!
        val sourceArtifact = sourceArtifacts[id]
        val module = artifact.remapped(namespace)
        val directory = artifactDirectory(module)
        val dependency = project.dependencies.createDependency(module)

        targetConfiguration.configure { dependencies.add(dependency) }

        writeIvyXml(
            module,
            artifact.dependencies.mapTo(ArrayList()) { it.remapped(namespace) },
            directory.resolve("ivy-${module.version}.xml")
        )

        if (hasWork) {
            remap(artifact.path, directory.artifact(module))
            if (sourceArtifact != null) {
                remapSource(sourceArtifact.path, directory.sourceArtifact(module))
            }
        }
    }

    private fun artifactDirectory(module: Module): Path {
        return artifactDirectory(extension, module)
    }

    private fun writeIvyXml(module: Module, dependencies: List<Module>, destination: Path) {
        if (destination.verifyIntegrity()) return
        destination.parent.createDirectories()
        destination.writeText(writeIvyModule(module, dependencies))
        destination.saveIntegrity()
    }

    private fun remapSource(file: Path, destination: Path) {
        if (destination.verifyIntegrity()) return

        ZipInputStream(file.inputStream()).use { input ->
            destination.parent.createDirectories()
            ZipOutputStream(destination.outputStream()).use { output ->
                while (true) {
                    val entry = input.nextEntry ?: break

                    val name = entry.name
                    val newName = sourceNames[name] ?: name

                    output.putNextEntry(ZipEntry(newName))

                    val data = input.readBytes()

                    output.write(transformSource(data, entry))

                    output.closeEntry()
                    input.closeEntry()
                }
            }
        }

        destination.saveIntegrity()
    }

    private fun remap(file: Path, destination: Path) {
        if (destination.verifyIntegrity()) return

        val remapper = RelocationRemapper(names)

        ZipInputStream(file.inputStream()).use { input ->
            destination.parent.createDirectories()
            ZipOutputStream(destination.outputStream()).use { output ->
                while (true) {
                    val entry = input.nextEntry ?: break

                    val name = entry.name
                    val newName = names[name] ?: name

                    output.putNextEntry(ZipEntry(newName))

                    val data = input.readBytes()

                    output.write(transform(remapper, data, entry))

                    output.closeEntry()
                    input.closeEntry()
                }
            }
        }

        destination.saveIntegrity()
    }

    private fun collectNames(file: Path, names: MutableMap<String, String>) {
        ZipInputStream(file.inputStream()).use { input ->
            while (true) {
                val entry = input.nextEntry ?: break

                val name = entry.name
                val isClass = name.endsWith(".class") || name.endsWith(".java")
                val isMeta = name.startsWith("META-INF/")
                val remap = !isMeta && (isClass || entry.isDirectory)

                val newName = if (remap) "${namespace.replace('.', '/')}/$name" else name
                if (!name.equals(newName)) {
                    names[name] = newName
                }

                input.closeEntry()
            }
        }
    }

    private fun transform(
        remapper: RelocationRemapper, bytes: ByteArray, entry: ZipEntry
    ): ByteArray {
        if (entry.isDirectory) {
            return bytes
        }

        if (entry.name.endsWith(".class")) {
            return remapClass(
                remapper, bytes
            )
        }
        // unknown remapping
        return bytes
    }

    private fun transformSource(
        bytes: ByteArray, entry: ZipEntry
    ): ByteArray {
        if (entry.isDirectory) {
            return bytes
        }

        if (entry.name.endsWith(".java")) {
            return remapJava(bytes, entry.name.dropLast(5).replace('/', '.'))
        }

        return bytes
    }

    private fun remapJava(
        bytes: ByteArray, className: String
    ): ByteArray {
        var sourceText = bytes.decodeToString()
        sourceText = replacePackageName(sourceText, className)

        sourceClassNames.forEach { entry ->
            val key = entry.key
            val value = entry.value

            val regex = "(?<!${Regex.escape("$namespace.")})" + Regex.escape(key)
            sourceText = sourceText.replace(Regex(regex), Matcher.quoteReplacement(value))
        }

        return sourceText.encodeToByteArray()
    }

    private fun replacePackageName(sourceText: String, className: String): String {
        val lastIdx = className.lastIndexOf('.')
        if (lastIdx == -1) return sourceText
        val packageName = className.substring(0, lastIdx)
        val toReplace = "package $packageName"
        val replaceWith = "package $namespace.$packageName"
        return sourceText.replace(toReplace, replaceWith)
    }

    private fun remapClass(
        remapper: RelocationRemapper, bytes: ByteArray
    ): ByteArray {
        val reader = ClassReader(bytes)
        val writer = ClassWriter(0)

        val visitor = ClassRemapper(writer, remapper)

        reader.accept(visitor, ClassReader.EXPAND_FRAMES)

        return writer.toByteArray()
    }

    private fun collectArtifacts() {
        configuration.resolvedConfiguration.firstLevelModuleDependencies.forEach { collect(it, artifacts) }
        createSourceConfiguration().resolvedConfiguration.firstLevelModuleDependencies.forEach {
            collect(it, sourceArtifacts)
        }
    }

    private fun collect(
        resolvedDependency: ResolvedDependency, artifacts: MutableMap<ModuleVersionIdentifier, PreparedModule>
    ): PreparedModule? {
        val id = resolvedDependency.module.id
        artifacts[id]?.let { return it }
        if (resolvedDependency.moduleArtifacts.isEmpty()) return null

        val dependencies = HashSet(resolvedDependency.children.mapNotNull { collect(it, artifacts) })

        if (artifacts.containsKey(id)) throw IllegalArgumentException("Circular dependencies")

        val moduleArtifact = resolvedDependency.moduleArtifacts.single()

        val module = PreparedModule(moduleArtifact, dependencies)
        artifacts[id] = module
        return module
    }

    private fun createSourceConfiguration(): Configuration {
        val sourceDependencies = ArrayList<Dependency>()
        artifacts.forEach {
            val dependency =
                project.dependencies.create(it.key.group, it.key.name, it.key.version, classifier = "sources")
            sourceDependencies.add(dependency)
        }
        val sourceDownloadConfiguration =
            project.configurations.detachedConfiguration(*sourceDependencies.toTypedArray())
        sourceDownloadConfiguration.isTransitive = false
        return sourceDownloadConfiguration
    }
}

private fun Path.artifact(module: Module): Path {
    return this.resolve("${module.name}-${module.version}.jar")
}

private fun Path.sourceArtifact(module: Module): Path {
    return this.resolve("${module.name}-${module.version}-sources.jar")
}

private fun artifactDirectory(extension: SourceRemapperExtension, module: Module): Path {
    val group = module.group
    val name = module.name
    val version = module.version
    val directory = extension.repositoryPath.resolve(group.replace('.', '/')).resolve(name).resolve(version)
    return directory
}

internal fun publishedGroup(projectGroup: String, projectName: String, artifactGroup: String): String {
    return "${projectGroup}.${projectName}.${artifactGroup}"
}

internal fun createPublications(
    project: Project,
    namespace: String,
    artifacts: Map<ModuleVersionIdentifier, PreparedModule>,
    extension: SourceRemapperExtension
) {
    val publishing = project.extensions.findByType<PublishingExtension>() ?: return
    val projectVersion = project.version.toString()
    val projectGroup = project.group.toString()
    val projectName = project.name
    artifacts.forEach { entry ->
        val artifact = entry.value
        val artifactGroup = artifact.resolvedArtifact.moduleVersion.id.group
        val module = artifact.remapped(namespace)
        val directory = artifactDirectory(extension, module)
        val dependencies = artifact.dependencies.map {
            val itGroup = it.resolvedArtifact.moduleVersion.id.group
            it.remapped(namespace).copy(group = itGroup)
        }
        publishing.publications {
            val publicationName = "${module.group}-${module.name}-${module.version}"
            register<MavenPublication>(publicationName) {
                this.groupId = publishedGroup(projectGroup, projectName, artifactGroup)
                this.artifactId = module.name
                this.version = projectVersion
                this.artifact(directory.artifact(module))
                val sourcePath = directory.sourceArtifact(module)
                if (sourcePath.exists()) {
                    this.artifact(sourcePath) {
                        this.classifier = "sources"
                    }
                }

                pom.withXml {
                    val element = this.asElement()
                    val dependenciesElement = element.ownerDocument.createElement("dependencies")

                    dependencies.forEach {
                        val dependencyElement = element.ownerDocument.createElement("dependency")
                        dependencyElement.appendChild(element.ownerDocument.createElement("groupId")
                            .apply { textContent = publishedGroup(projectGroup, projectName, it.group) })
                        dependencyElement.appendChild(element.ownerDocument.createElement("artifactId")
                            .apply { textContent = it.name })
                        dependencyElement.appendChild(element.ownerDocument.createElement("version")
                            .apply { textContent = projectVersion })
                        dependencyElement.appendChild(element.ownerDocument.createElement("scope")
                            .apply { textContent = "compile" })
                        dependenciesElement.appendChild(dependencyElement)
                    }

                    element.appendChild(dependenciesElement)
                }
            }
        }
    }
}

internal class RelocationRemapper(private val names: Map<String, String>) : Remapper() {
    override fun map(internalName: String): String {
        return names["$internalName.class"]?.dropLast(".class".length) ?: internalName
    }
}

internal data class PreparedModule(val resolvedArtifact: ResolvedArtifact, val dependencies: Set<PreparedModule>) {
    fun remapped(namespace: String): Module {
        val id = resolvedArtifact.moduleVersion.id
        val group = id.group
        val module = id.name
        val version = id.version
        return Module("$namespace.$group", module, version)
    }

    val path: Path
        get() = resolvedArtifact.file.toPath()
}

internal fun Module.undoRemap(namespace: String): Module {
    return Module(group.drop(namespace.length + 1), name, version)
}
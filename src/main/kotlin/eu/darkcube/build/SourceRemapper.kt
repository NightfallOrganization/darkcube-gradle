package eu.darkcube.build

import org.gradle.api.NamedDomainObjectProvider
import org.gradle.api.Project
import org.gradle.api.artifacts.*
import org.gradle.api.artifacts.dsl.DependencyHandler
import org.gradle.api.artifacts.repositories.IvyArtifactRepository
import org.gradle.kotlin.dsl.DependencyHandlerScope
import org.gradle.kotlin.dsl.create
import org.gradle.kotlin.dsl.repositories
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.commons.ClassRemapper
import org.objectweb.asm.commons.Remapper
import sun.security.util.Resources_es
import java.nio.file.Path
import java.util.regex.Matcher
import java.util.stream.Collectors
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlin.io.path.*

class SourceRemapperExtension(private val project: Project) {

    private val cachesPathRoot: Path =
        project.gradle.gradleUserHomeDir.toPath().resolve("caches").resolve("darkcube-source-remapper")
    private val cachesPath = cachesPathRoot.resolve(darkcubeHash)
    internal val repositoryPath = cachesPath.resolve("repository")

    /**
     * Remaps all files in the configuration to the given namespace
     */
    fun remap(configuration: Configuration, namespace: String, target: NamedDomainObjectProvider<Configuration>) {
        RemapTask(this, project, configuration, namespace, target).remap()
    }

}

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

class RemapTask(
    private val extension: SourceRemapperExtension,
    private val project: Project,
    private val configuration: Configuration,
    private val namespace: String,
    private val targetConfiguration: NamedDomainObjectProvider<Configuration>
) {
    private val artifacts = HashMap<ModuleVersionIdentifier, PreparedModule>()
    private val sourceArtifacts = HashMap<ModuleVersionIdentifier, PreparedModule>()
    private val remapped = HashSet<ModuleVersionIdentifier>()
    private val names = HashMap<String, String>()
    private val sourceNames = HashMap<String, String>()
    private val sourceClassNames = HashMap<String, String>()

    fun remap() {
        collectArtifacts()
        remapAll()
        setupIvyRepository()
    }

    private fun remapAll() {
        artifacts.keys.forEach { remap(it) }
    }

    private fun remap(id: ModuleVersionIdentifier) {
        if (!remapped.add(id)) return

        val artifact = artifacts[id]!!
        artifact.dependencies.forEach {
            remap(it.resolvedArtifact.moduleVersion.id)
        }

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

        remap(artifact.path, directory.resolve("${module.name}-${module.version}.jar"))
        if (sourceArtifact != null) {
            remapSource(sourceArtifact.path, directory.resolve("${module.name}-${module.version}-sources.jar"))
        }
    }

    private fun artifactDirectory(module: Module): Path {
        val group = module.group
        val name = module.name
        val version = module.version
        val directory = extension.repositoryPath.resolve(group.replace('.', '/')).resolve(name).resolve(version)
        return directory
    }

    private fun writeIvyXml(module: Module, dependencies: List<Module>, destination: Path) {
        if (destination.verifyIntegrity()) return
        destination.parent.createDirectories()
        destination.writeText(writeIvyModule(module, dependencies))
        destination.saveIntegrity()
    }

    private fun remapSource(file: Path, destination: Path) {
        if (destination.verifyIntegrity()) return

        collectNames(file, sourceNames)
        sourceNames.forEach {
            if (it.key.endsWith(".java")) {
                sourceClassNames[it.key.dropLast(5).replace('/', '.')] = it.value.dropLast(5).replace('/', '.')
            }
        }

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

        collectNames(file, names)

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

    private fun setupIvyRepository() {
        project.repositories {
            ivy {
                url = extension.repositoryPath.toUri()
                patternLayout {
                    artifact(IvyArtifactRepository.MAVEN_ARTIFACT_PATTERN)
                    ivy(IvyArtifactRepository.MAVEN_IVY_PATTERN)
                    setM2compatible(true)
                }
                content {
                    artifacts.forEach {
                        val module = it.value.remapped(namespace)
                        includeVersion(module.group, module.name, module.version)
                    }
                }
            }
        }
    }
}

class RelocationRemapper(private val names: Map<String, String>) : Remapper() {
    override fun map(internalName: String): String {
        return names["$internalName.class"]?.dropLast(".class".length) ?: internalName
    }
}

data class PreparedModule(val resolvedArtifact: ResolvedArtifact, val dependencies: Set<PreparedModule>) {
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
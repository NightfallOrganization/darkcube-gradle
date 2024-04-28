package eu.darkcube.build

import org.gradle.api.NamedDomainObjectProvider
import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.Dependency
import org.gradle.api.artifacts.ModuleVersionIdentifier
import org.gradle.api.artifacts.ResolvedArtifact
import org.gradle.api.artifacts.ResolvedDependency
import org.gradle.api.artifacts.repositories.IvyArtifactRepository
import org.gradle.kotlin.dsl.create
import org.gradle.kotlin.dsl.repositories
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.commons.ClassRemapper
import org.objectweb.asm.commons.Remapper
import java.nio.file.Path
import java.util.HashMap
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
    private val repositoryPath = cachesPath.resolve("repository")

    /**
     * Remaps all files in the configuration to the given namespace
     */
    fun remap(configuration: Configuration, namespace: String, target: NamedDomainObjectProvider<Configuration>) {
        val artifacts = HashMap<ModuleVersionIdentifier, PreparedModule>()

        configuration.resolvedConfiguration.firstLevelModuleDependencies.forEach {
            collect(artifacts, it)
        }

        val sourceDependencies = ArrayList<Dependency>()
        artifacts.forEach {
            val dependency =
                project.dependencies.create(it.key.group, it.key.name, it.key.version, classifier = "sources")
            sourceDependencies.add(dependency)
        }
        val sourceDownloadConfiguration =
            project.configurations.detachedConfiguration(*sourceDependencies.toTypedArray())
        sourceDownloadConfiguration.isTransitive = false
        val sourceArtifacts = HashMap<ModuleVersionIdentifier, PreparedModule>()
        sourceDownloadConfiguration.resolvedConfiguration.firstLevelModuleDependencies.forEach {
            collect(sourceArtifacts, it)
        }

        artifacts.forEach { entry ->
            val module = entry.value.remapped(namespace)
            val group = module.group
            val name = module.name
            val version = module.version
            val directory = repositoryPath.resolve(group.replace('.', '/')).resolve(name).resolve(version)
            val dependency = project.dependencies.create("$group:$name:$version")
            target.configure {
                dependencies.add(dependency)
            }

            val file = entry.value.resolvedArtifact.file.toPath()

            val ivyXml = directory.resolve("ivy-$version.xml")
            val dependencies =
                entry.value.dependencies.stream().map { it.remapped(namespace) }.collect(Collectors.toList())

            writeIvyXml(module, dependencies, ivyXml)

            val jarPath = directory.resolve("$name-$version.jar")

            remap(file, namespace, jarPath)

            val sourceJarPath = directory.resolve("$name-$version-sources.jar")

            val sourceFile = sourceArtifacts[entry.key]?.resolvedArtifact?.file?.toPath()
            if (sourceFile != null) {
                remapSource(sourceFile, namespace, sourceJarPath)
            }
        }

        project.repositories {
            ivy {
                url = repositoryPath.toUri()
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

    private fun writeIvyXml(module: Module, dependencies: List<Module>, destination: Path) {
        if (destination.verifyIntegrity()) return
        destination.parent.createDirectories()
        destination.writeText(writeIvyModule(module, dependencies))
        destination.saveIntegrity()
    }

    private fun remapSource(file: Path, namespace: String, destination: Path) {
        if (destination.verifyIntegrity()) return

        val names = collectNames(file, namespace)

        ZipInputStream(file.inputStream()).use { input ->
            destination.parent.createDirectories()
            ZipOutputStream(destination.outputStream()).use { output ->
                while (true) {
                    val entry = input.nextEntry ?: break

                    val name = entry.name
                    val newName = names[name] ?: name

                    output.putNextEntry(ZipEntry(newName))

                    val data = input.readBytes()

                    output.write(transformSource(names, data, entry, namespace))

                    output.closeEntry()
                    input.closeEntry()
                }
            }
        }

        destination.saveIntegrity()
    }

    private fun remap(file: Path, namespace: String, destination: Path) {
        if (destination.verifyIntegrity()) return

        val names = collectNames(file, namespace)

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

    private fun collectNames(file: Path, namespace: String): Map<String, String> {
        val names = HashMap<String, String>()
        ZipInputStream(file.inputStream()).use { input ->
            while (true) {
                val entry = input.nextEntry ?: break

                val name = entry.name
                val isClass = name.endsWith(".class") || name.endsWith(".java")
                val isMeta = name.startsWith("META-INF/")
                val remap = !isMeta && (isClass || entry.isDirectory)

                val newName = if (remap) "${namespace.replace('.', '/')}/$name" else name
                if (!name.equals(newName)) names[name] = newName

                input.closeEntry()
            }
        }
        return names
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
        names: Map<String, String>, bytes: ByteArray, entry: ZipEntry, namespace: String
    ): ByteArray {
        if (entry.isDirectory) {
            return bytes
        }

        if (entry.name.endsWith(".java")) {
            return remapJava(names, bytes, namespace)
        }

        return bytes
    }

    private fun remapJava(
        names: Map<String, String>, bytes: ByteArray, namespace: String
    ): ByteArray {
        var sourceText = bytes.decodeToString()

        names.forEach { entry ->
            var key = entry.key.replace('/', '.')
            key = if (key.endsWith(".java")) key.dropLast(5) else key
            var value = entry.value.replace('/', '.')
            value = if (value.endsWith(".java")) value.dropLast(5) else value
            val regex = "(?<!${Regex.escape("$namespace.")})" + Regex.escape(key)
            val prev = sourceText
            sourceText = sourceText.replace(Regex(regex), Matcher.quoteReplacement(value))
            val next = sourceText
            if (prev != next) {
                println("Changed by $regex")
            }
        }

        return sourceText.encodeToByteArray()
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

    private fun collect(
        artifacts: MutableMap<ModuleVersionIdentifier, PreparedModule>, resolvedDependency: ResolvedDependency
    ): PreparedModule? {
        val id = resolvedDependency.module.id
        val module = artifacts[id]
        if (module != null) return module
        if (resolvedDependency.moduleArtifacts.isEmpty()) return null
        val dependencies = resolvedDependency.children.mapNotNull { collect(artifacts, it) }
        return artifacts.computeIfAbsent(resolvedDependency.module.id) {
            println(resolvedDependency.moduleArtifacts)
            PreparedModule(resolvedDependency.moduleArtifacts.single(), HashSet(dependencies))
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
}
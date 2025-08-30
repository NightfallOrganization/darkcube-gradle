package eu.darkcube.build.remapper

import eu.darkcube.build.Module
import eu.darkcube.build.sha512asHex
import eu.darkcube.build.writeIvyModule
import org.slf4j.LoggerFactory
import java.net.URI
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import java.util.function.Supplier
import kotlin.io.path.*

class RemappedRepository(cachesPath: Path) {
    private val repositoryPath: Path = cachesPath.resolve("repository")
    val uri: URI
        get() = repositoryPath.toUri()

    fun ensureIntegrity(
        namespace: String,
        version: String,
        resolvedModule: ResolvedModule,
        inputType: InputType,
        dependencies: Supplier<out Iterable<ResolvedModule>>
    ) {
        val remappedDependency = resolvedModule.module.remapped(namespace, version)
        val path = path(remappedDependency, inputType)
        if (path.verifyIntegrity()) return

        val start = System.nanoTime()
        val dependencies = dependencies.get()
        val instance = RemapperInstance(namespace, inputType, resolvedModule.file, dependencies.map { it.file })
        instance.remapTo(path)
        path.saveIntegrity()
        writeIvyXml(
            remappedDependency,
            dependencies.map { it.module.remapped(namespace, version) },
            path.resolveSibling("ivy-${remappedDependency.version}.xml")
        )
        logger.info("Remapping ${resolvedModule.file.fileName}($inputType) took ${TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)}ms")
    }

    private fun writeIvyXml(module: Module, dependencies: List<Module>, destination: Path) {
        if (destination.verifyIntegrity()) return
        destination.parent.createDirectories()
        destination.writeText(writeIvyModule(module, dependencies))
        destination.saveIntegrity()
    }

    private fun path(remappedDependency: Module, inputType: InputType): Path {
        return when (inputType) {
            InputType.BINARY -> artifactPath(remappedDependency)
            InputType.SOURCES -> sourcesPath(remappedDependency)
        }
    }

    private fun sourcesPath(remappedDependency: Module): Path {
        return artifactDirectory(remappedDependency).resolve("${remappedDependency.name}-${remappedDependency.version}-sources.jar")
    }

    private fun artifactPath(remappedDependency: Module): Path {
        return artifactDirectory(remappedDependency).resolve("${remappedDependency.name}-${remappedDependency.version}.jar")
    }

    private fun Path.verifyIntegrity(): Boolean {
        if (!exists()) return false
        val sha512 = sha512()
        if (!sha512.exists()) return false
        val computed = sha512asHex()
        val expected = sha512.readText()
        return computed == expected
    }

    private fun Path.saveIntegrity() {
        sha512().writeText(sha512asHex())
    }

    private fun Path.sha512(): Path {
        return resolveSibling("$name.sha512")
    }

    private fun artifactDirectory(remappedDependency: Module): Path =
        repositoryPath.resolve(remappedDependency.group.replace('.', '/')).resolve(
            remappedDependency.name
        ).resolve(remappedDependency.version)

    companion object {
        private val logger = LoggerFactory.getLogger(RemappedRepository::class.java)
    }
}

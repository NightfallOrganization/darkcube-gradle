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

    /**
     * @return true if done work
     */
    fun ensureIntegrity(
        projectGroup: String,
        projectName: String,
        namespace: String,
        version: String,
        resolvedModule: ResolvedModule,
        inputType: InputType,
        dependencies: Supplier<out Iterable<ResolvedModule>>
    ): Boolean {
        val remappedDependency = resolvedModule.module.remapped(projectGroup, projectName, version)
        val path = path(
            projectGroup,
            projectName,
            version,
            resolvedModule.module.group,
            resolvedModule.module.name,
            remappedDependency,
            inputType
        )
        if (path.verifyIntegrity()) return false

        val start = System.nanoTime()
        val dependencies = dependencies.get()
        val instance = RemapperInstance(namespace, inputType, resolvedModule.file, dependencies.map { it.file })
        instance.remapTo(path)
        path.saveIntegrity()
        writeIvyXml(
            remappedDependency,
            dependencies.map { it.module.remapped(projectGroup, projectName, version) },
            path.resolveSibling("ivy-${remappedDependency.version}.xml")
        )
        logger.info("Remapping ${resolvedModule.file.fileName}($inputType) took ${TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)}ms")
        return true
    }

    private fun writeIvyXml(module: Module, dependencies: List<Module>, destination: Path) {
        if (destination.verifyIntegrity()) return
        destination.parent.createDirectories()
        destination.writeText(writeIvyModule(module, dependencies))
        destination.saveIntegrity()
    }

    private fun path(
        projectGroup: String,
        projectName: String,
        projectVersion: String,
        originalGroup: String,
        originalName: String,
        remappedDependency: Module,
        inputType: InputType
    ): Path {
        return when (inputType) {
            InputType.BINARY -> artifactPath(
                projectGroup, projectName, projectVersion, originalGroup, originalName, remappedDependency
            )

            InputType.SOURCES -> sourcesPath(
                projectGroup, projectName, projectVersion, originalGroup, originalName, remappedDependency
            )
        }
    }

    fun sourcesPath(
        projectGroup: String,
        projectName: String,
        projectVersion: String,
        originalGroup: String,
        originalName: String,
        remappedDependency: Module
    ): Path {
        return artifactDirectory(
            projectGroup, projectName, projectVersion, originalGroup, originalName
        ).resolve("${remappedDependency.name}-${remappedDependency.version}-sources.jar")
    }

    fun artifactPath(
        projectGroup: String,
        projectName: String,
        projectVersion: String,
        originalGroup: String,
        originalName: String,
        remappedDependency: Module
    ): Path {
        return artifactDirectory(
            projectGroup, projectName, projectVersion, originalGroup, originalName
        ).resolve("${remappedDependency.name}-${remappedDependency.version}.jar")
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

    private fun artifactDirectory(
        projectGroup: String, projectName: String, projectVersion: String, originalGroup: String, originalName: String
    ): Path = repositoryPath.resolve(projectGroup.replace('.', '/')).resolve(projectName)
        .resolve(originalGroup.replace('.', '/')).resolve(originalName).resolve(projectVersion)

    companion object {
        private val logger = LoggerFactory.getLogger(RemappedRepository::class.java)
    }
}

package eu.darkcube.build.remapper

import org.gradle.api.artifacts.transform.*
import org.gradle.api.file.FileCollection
import org.gradle.api.file.FileSystemLocation
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.CompileClasspath
import org.gradle.api.tasks.Input
import org.slf4j.LoggerFactory
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock

@CacheableTransform
abstract class RemapperTransform : TransformAction<RemapperTransform.Parameters> {

    @get:Classpath
    @get:InputArtifact
    abstract val primaryInput: Provider<FileSystemLocation>

    @get:CompileClasspath
    @get:InputArtifactDependencies
    abstract val dependencies: FileCollection

    companion object {
        private val lock = ReentrantLock()
        private val logger = LoggerFactory.getLogger(RemapperTransform::class.java)
    }

    private val namespace: String
        get() = this.parameters.namespace

    override fun transform(outputs: TransformOutputs) {
        val start = System.nanoTime()
        val params = this.parameters
        val inputFile = primaryInput.get().asFile.toPath()
        val dst = outputs.file(inputFile.fileName.toString()).toPath()
        val remapper = RemapperInstance(namespace, params.type, inputFile, dependencies.files.map { it.toPath() })

        remapper.remapTo(dst)
        val took = System.nanoTime() - start
        logger.info("Remapping ${inputFile.fileName}(${params.type}) took ${TimeUnit.NANOSECONDS.toMillis(took)}ms")
    }

    interface Parameters : TransformParameters {
        @get:Input
        var namespace: String

        @get:Input
        var type: InputType
    }
}
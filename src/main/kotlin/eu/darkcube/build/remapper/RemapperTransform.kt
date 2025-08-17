package eu.darkcube.build.remapper

import org.gradle.api.artifacts.transform.CacheableTransform
import org.gradle.api.artifacts.transform.InputArtifact
import org.gradle.api.artifacts.transform.InputArtifactDependencies
import org.gradle.api.artifacts.transform.TransformAction
import org.gradle.api.artifacts.transform.TransformOutputs
import org.gradle.api.artifacts.transform.TransformParameters
import org.gradle.api.file.FileCollection
import org.gradle.api.file.FileSystemLocation
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.CompileClasspath
import org.gradle.api.tasks.Input
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

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
    }

    private val namespace: String
        get() = this.parameters.namespace

    override fun transform(outputs: TransformOutputs) {
        val params = this.parameters
        val inputFile = primaryInput.get().asFile.toPath()
        val dst = outputs.file(inputFile.fileName.toString()).toPath()
        val remapper = RemapperInstance(namespace, params.type, inputFile, dependencies.files.map { it.toPath() })

        remapper.remap(primaryInput.get().asFile.toPath(), dst)

//        lock.withLock {
//            println(primaryInput.get().asFile)
//            println("To: " + dst)
//            println("Dependencies:")
//            dependencies.files.forEach {
//                println(it)
//            }
//            println("---")
//        }
    }

    interface Parameters : TransformParameters {
        @get:Input
        var namespace: String

        @get:Input
        var type: InputType

    }
}
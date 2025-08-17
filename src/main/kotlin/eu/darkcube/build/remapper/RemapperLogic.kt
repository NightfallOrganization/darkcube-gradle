package eu.darkcube.build.remapper

import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.commons.ClassRemapper
import org.objectweb.asm.commons.Remapper
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Path
import java.util.regex.Matcher
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlin.io.path.createDirectories
import kotlin.io.path.inputStream
import kotlin.io.path.outputStream

internal class RemapperInstance(namespace: String, type: InputType, file: Path, dependencies: Iterable<Path>) {

    private val dashedNamespace = namespace.replace('.', '/')
    private val worker: TypedWorker

    /**
     * The remapped paths with '/' as separators by old paths
     */
    private val zipPaths: Map<String, String>

    init {
        val names = HashMap<String, String>()
        collectPaths(names, file)
        dependencies.forEach {
            collectPaths(names, it)
        }
        zipPaths = names

        worker = when (type) {
            InputType.BINARY -> {
                TypedWorker.Binary(zipPaths)
            }

            InputType.SOURCES -> {
                TypedWorker.Source(namespace, zipPaths)
            }
        }
    }

    private fun collectPaths(names: MutableMap<String, String>, file: Path) {
        ZipInputStream(file.inputStream()).use { input ->
            while (true) {
                val entry = input.nextEntry ?: break

                val name = entry.name
                val isClass = name.endsWith(".class") || name.endsWith(".java")
                val isMeta = name.startsWith("META-INF/")
                val remap = !isMeta && (isClass || entry.isDirectory)

                val newName = if (remap) "$dashedNamespace/$name" else name
                if (!name.equals(newName)) {
                    names[name] = newName
                }

                input.closeEntry()
            }
        }
    }

    fun remap(file: Path, destination: Path) {
        ZipInputStream(file.inputStream()).use { input ->
            destination.parent.createDirectories()
            ZipOutputStream(destination.outputStream()).use { output ->
                while (true) {
                    val entry = input.nextEntry ?: break

                    val name = entry.name
                    val newName = zipPaths[name] ?: name

                    output.putNextEntry(ZipEntry(newName))

                    worker.transformFile(name, input, output)

                    output.closeEntry()
                    input.closeEntry()
                }
            }
        }
    }

    internal sealed interface TypedWorker {
        fun transformFile(filePath: String, input: InputStream, output: OutputStream)

        class Binary(names: Map<String, String>) : TypedWorker {
            private val remapper = RelocationRemapper(names)
            override fun transformFile(filePath: String, input: InputStream, output: OutputStream) {
                if (filePath.endsWith(".class")) {
                    return remapClass(input, output)
                }
                input.copyTo(output)
            }

            private fun remapClass(input: InputStream, output: OutputStream) {
                val reader = ClassReader(input)
                val writer = ClassWriter(0)

                val visitor = ClassRemapper(writer, remapper)

                reader.accept(visitor, ClassReader.EXPAND_FRAMES)

                val bytes = writer.toByteArray()
                output.write(bytes)
            }

            private class RelocationRemapper(private val names: Map<String, String>) : Remapper() {
                override fun map(internalName: String): String {
                    return names["$internalName.class"]?.dropLast(".class".length) ?: internalName
                }
            }
        }

        class Source(private val namespace: String, names: Map<String, String>) : TypedWorker {
            private val sourceClassNames = HashMap<String, String>()

            init {
                names.forEach {
                    if (it.key.endsWith(".java")) {
                        sourceClassNames[it.key.dropLast(5).replace('/', '.')] = it.value.dropLast(5).replace('/', '.')
                    }
                }
            }

            override fun transformFile(filePath: String, input: InputStream, output: OutputStream) {
                if (filePath.endsWith(".java")) {
                    return remapJava(input, output, filePath.dropLast(5).replace('/', '.'))
                }
                input.copyTo(output)
            }

            private fun remapJava(input: InputStream, output: OutputStream, className: String) {
                val sourceText = input.readBytes().decodeToString()
                var newText = replacePackageName(sourceText, className)

                sourceClassNames.forEach { entry ->
                    val key = entry.key
                    val value = entry.value

                    val regex = "(?<!${Regex.escape("$namespace.")})" + Regex.escape(key)
                    newText = newText.replace(Regex(regex), Matcher.quoteReplacement(value))
                }

                val bytes = newText.encodeToByteArray()
                output.write(bytes)
            }

            private fun replacePackageName(sourceText: String, className: String): String {
                val lastIdx = className.lastIndexOf('.')
                if (lastIdx == -1) return sourceText
                val packageName = className.substring(0, lastIdx)
                val toReplace = "package $packageName"
                val replaceWith = "package $namespace.$packageName"
                return sourceText.replace(toReplace, replaceWith)
            }
        }
    }
}

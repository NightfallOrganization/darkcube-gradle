package eu.darkcube.build.remapper

import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.commons.ClassRemapper
import org.objectweb.asm.commons.Remapper
import org.slf4j.LoggerFactory
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlin.io.path.*

internal class RemapperInstance(
    namespace: String, private val type: InputType, private val file: Path, dependencies: Iterable<Path>
) {

    companion object {
        private val logger = LoggerFactory.getLogger(RemapperInstance::class.java)
    }

    private val dashedNamespace = namespace.replace('.', '/')
    private val worker: TypedWorker

    /**
     * The remapped paths with '/' as separators by old paths
     */
    private val zipPaths: Map<String, String>

    init {
        val names = HashMap<String, String>()
        val start = System.nanoTime()
        zipPaths = names
        collectPaths(names, file)
        if (System.nanoTime() - start > TimeUnit.MILLISECONDS.toNanos(20)) logger.warn(
            "Collecting paths for {} took {}ms", file.name, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)
        )
        dependencies.forEach {
            collectPaths(names, it)
        }

        worker = when (type) {
            InputType.BINARY -> {
                TypedWorker.Binary(zipPaths)
            }

            InputType.SOURCES -> {
                TypedWorker.Source(namespace, zipPaths)
            }
        }
        if (System.nanoTime() - start > TimeUnit.MILLISECONDS.toNanos(20)) logger.warn(
            "Constructing for {} took {}ms", file.name, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)
        )
    }

    private fun collectPaths(names: MutableMap<String, String>, file: Path) {
        val zipFs = try {
            FileSystems.getFileSystem(file.toUri())
        } catch (_: Throwable) {
            FileSystems.newFileSystem(file)
        }
        zipFs.rootDirectories.forEach { rootDir ->
            Files.walk(rootDir).forEach { f ->
                val relative = rootDir.relativize(f)

                val path = relative.pathString
                val include = type.filter(path) || Files.isDirectory(f)
                if (include) {
                    val isMeta = relative.startsWith("META-INF")
                    if (!isMeta) {
                        names[path] = "$dashedNamespace/$path"
                    }
                }
            }
        }
    }

    fun remapTo(destination: Path) {
        ZipInputStream(file.inputStream().buffered()).use { input ->
            destination.parent.createDirectories()
            ZipOutputStream(destination.outputStream().buffered()).use { output ->
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
            companion object {
                fun isClassChar(ch: Char): Boolean = ch.isLetterOrDigit() || ch == '.' || ch == '_' || ch == '$'
            }

            private class TrieNode {
                val child = HashMap<Char, TrieNode>(4)
                var replacement: String? = null
            }

            private class ClassTrie(mappings: Map<String, String>) {
                private val root = TrieNode()

                init {
                    // Alles einmalig in den Trie laden
                    for ((from, to) in mappings) {
                        insert(from, to)
                    }
                }

                private fun insert(key: String, rep: String) {
                    var n = root
                    for (c in key) n = n.child.computeIfAbsent(c) { TrieNode() }
                    n.replacement = rep
                }

                /**
                 * Longest-prefix-match ab Position [start] im charArray.
                 * Gibt (matchLen, replacement) zurück oder null, wenn kein Mapping greift.
                 */
                fun longestPrefixAt(chars: CharArray, start: Int, end: Int): Match? {
                    var n = root
                    var i = start
                    var lastRep: String? = null
                    var lastEnd = start - 1

                    while (i < end) {
                        val ch = chars[i]
                        if (!isClassChar(ch)) break
                        val nxt = n.child[ch] ?: break
                        n = nxt
                        if (n.replacement != null) {
                            lastRep = n.replacement
                            lastEnd = i
                        }
                        i++
                    }
                    return if (lastRep != null) Match(lastEnd - start + 1, lastRep) else null
                }

                data class Match(val len: Int, val replacement: String)
            }

            private val sourceClassNames = HashMap<String, String>()
            private val trie: ClassTrie

            init {
                names.forEach {
                    if (it.key.endsWith(".java")) {
                        sourceClassNames[it.key.dropLast(5).replace('/', '.')] = it.value.dropLast(5).replace('/', '.')
                    }
                }
                trie = ClassTrie(sourceClassNames)
            }

            override fun transformFile(filePath: String, input: InputStream, output: OutputStream) {
                if (filePath.endsWith(".java")) {
                    return remapJava(input, output, filePath.dropLast(5).replace('/', '.'))
                }
                input.copyTo(output)
            }

            private fun endsWith(sb: StringBuilder, s: String): Boolean {
                if (sb.length < s.length) return false
                val off = sb.length - s.length
                for (i in s.indices) if (sb[off + i] != s[i]) return false
                return true
            }

            private fun remapJava(input: InputStream, output: OutputStream, className: String) {
                val inputFile = input.readAllBytes().decodeToString()
                val chars = inputFile.toCharArray()
                val n = chars.size
                val out = StringBuilder((inputFile.length * 1.1).toInt())

                var i = 0
                while (i < n) {
                    val ch = chars[i]

                    // Nicht-Klassenzeichen: direkt kopieren
                    if (!isClassChar(ch)) {
                        out.append(ch)
                        i++
                        continue
                    }

                    // Nur am Token-Anfang versuchen (linke "Boundary"):
                    if (i > 0 && isClassChar(chars[i - 1])) {
                        out.append(ch)
                        i++
                        continue
                    }

                    // Wenn direkt davor schon das Prefix steht -> NICHT remappen (doppelt vermeiden).
                    if (endsWith(out, namespace)) {
                        // komplettes Token kopieren (bis zum nächsten Nicht-Klassenzeichen)
                        var j = i
                        while (j < n && isClassChar(chars[j])) j++
                        out.append(chars, i, j - i)
                        i = j
                        continue
                    }

                    // Trie: längstes Präfix ab i suchen
                    val m = trie.longestPrefixAt(chars, i, n)
                    if (m != null) {
                        // Präfix ersetzen
                        out.append(m.replacement)
                        i += m.len
                        // Rest des Tokens wird in folgenden Iterationen normal weiter verarbeitet.
                        continue
                    } else {
                        // Kein Mapping: komplettes Token kopieren (schneller als Zeichen für Zeichen)
                        var j = i
                        while (j < n && isClassChar(chars[j])) j++
                        out.append(chars, i, j - i)
                        i = j
                    }
                }
                output.write(replacePackageName(out.toString(), className).encodeToByteArray())

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

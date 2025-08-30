package eu.darkcube.build

import eu.darkcube.build.remapper.RemappedDependency
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.file.Path
import java.nio.file.Paths
import java.security.MessageDigest
import javax.xml.XMLConstants
import javax.xml.stream.XMLOutputFactory
import kotlin.io.path.inputStream

internal val darkcubeHash: String by lazy { hashDarkCubeJar() }

private fun hashDarkCubeJar(): String {
    return Paths.get(DarkCubePlugin::class.java.protectionDomain.codeSource.location.toURI()).sha256asHex()
}

private val OUTPUT_FACTORY = XMLOutputFactory.newInstance()
private const val XSI = XMLConstants.W3C_XML_SCHEMA_INSTANCE_NS_URI
private const val IVY = "http://ant.apache.org/ivy/schemas/ivy.xsd"

internal fun writeIvyModule(
    module: Module, dependencies: List<Module>
): String = ByteArrayOutputStream().use { outputStream ->
    val writer = OUTPUT_FACTORY.createXMLStreamWriter(outputStream, Charsets.UTF_8.name())

    writer.writeStartDocument("UTF-8", "1.0")
    writer.writeStartElement("ivy-module")
    writer.writeNamespace("xsi", XSI)
    writer.writeAttribute(XSI, "noNamespaceSchemaLocation", IVY)
    writer.writeAttribute("version", "2.0")

    writer.writeEmptyElement("info")
    writer.writeAttribute("organisation", module.group)
    writer.writeAttribute("module", module.name)
    writer.writeAttribute("revision", module.version)
    writer.writeAttribute("status", "release")

    writer.writeStartElement("dependencies")
    for (dep in dependencies) {
        writer.writeEmptyElement("dependency")
        writer.writeAttribute("org", dep.group)
        writer.writeAttribute("name", dep.name)
        writer.writeAttribute("rev", dep.version)
    }
    writer.writeEndElement()

    writer.writeEndElement()
    writer.writeEndDocument()

    String(outputStream.toByteArray(), Charsets.UTF_8)
}

internal fun Path.sha256asHex(): String = inputStream().use { input -> input.sha256() }.asHexString()
internal fun Path.sha512asHex(): String = inputStream().use { input -> input.sha512() }.asHexString()

internal fun InputStream.sha256(): ByteArray {
    return hash(MessageDigest.getInstance("SHA-256"))
}

internal fun InputStream.sha512(): ByteArray {
    return hash(MessageDigest.getInstance("SHA-512"))
}

internal fun InputStream.hash(digest: MessageDigest): ByteArray {
    val buffer = ByteArray(8192)
    while (true) {
        val count = read(buffer)
        if (count == -1) {
            break
        }
        digest.update(buffer, 0, count)
    }
    return digest.digest()
}

private val hexChars = "0123456789abcdef".toCharArray()

internal fun ByteArray.asHexString(): String {
    val chars = CharArray(2 * size)
    forEachIndexed { i, byte ->
        val unsigned = byte.toInt() and 0xFF
        chars[2 * i] = hexChars[unsigned / 16]
        chars[2 * i + 1] = hexChars[unsigned % 16]
    }
    return String(chars)
}

data class Module(
    val group: String,
    val name: String,
    val version: String,
) {
    fun remapped(namespace: String, version: String = this.version) =
        Module("$namespace.${group}", name, version)
}
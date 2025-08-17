package eu.darkcube.build.remapper

import org.gradle.api.attributes.Attribute

interface Remapped {
    companion object {
        val REMAPPED_ATTRIBUTE: Attribute<Boolean> = Attribute.of("eu.darkcube.remapper.remapped", Boolean::class.javaObjectType)
    }
}

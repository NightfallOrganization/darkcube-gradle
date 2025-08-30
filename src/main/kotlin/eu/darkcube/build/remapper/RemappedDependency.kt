package eu.darkcube.build.remapper

import eu.darkcube.build.Module

data class RemappedDependency(
    val group: String,
    val name: String,
    val version: String
) {
    val asModule: Module
        get() = Module(group, name, version)
}

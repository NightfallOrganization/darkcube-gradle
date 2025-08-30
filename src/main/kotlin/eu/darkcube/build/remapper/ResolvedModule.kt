package eu.darkcube.build.remapper

import eu.darkcube.build.Module
import java.nio.file.Path

data class ResolvedModule(val module: Module, val file: Path) {
}

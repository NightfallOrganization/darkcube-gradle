package eu.darkcube.build.remapper

import eu.darkcube.build.Module
import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.result.ResolvedComponentResult
import org.gradle.api.artifacts.result.ResolvedDependencyResult
import org.gradle.api.attributes.Category
import org.gradle.api.provider.Provider
import java.nio.file.Path

class ResolvedDependencies(val dependencies: List<ResolvedDependencyTree>) {
    val transitiveList: List<ResolvedDependencyTree>
        get() = dependencies.flatMap { dep -> listOf(dep) + dep.dependencies.transitiveList }.distinctBy { it.module }

    companion object {
        fun Provider<out Configuration>.resolveDependencies(): Provider<out Map<Module, ResolvedDependencyTree>> {
            return flatMap { configuration ->
                val artifacts = configuration.incoming.artifacts.resolvedArtifacts.map { set ->
                    set.groupBy({
                        it.id.componentIdentifier
                    }).mapValues { it.value.single() }
                }
                configuration.incoming.resolutionResult.rootComponent.zip(artifacts) { a, b -> a to b }
            }.map { pair ->
                val root = pair.first
                val artifacts = pair.second
                val sb = StringBuilder()
                val resolvedTrees = HashMap<Module, ResolvedDependencyTree>()
                fun render(
                    comp: ResolvedComponentResult, indent: String = "", root: Boolean = false
                ): ResolvedDependencyTree? {
                    val mv = comp.moduleVersion ?: error("No module version for $comp")
                    val module = Module(mv.group, mv.name, mv.version)
                    val id = comp.id
                    val result = artifacts[id]
                    if (!root && result == null) {
                        val isPlatform = comp.variants.any {
                            it.attributes.getAttribute(Category.CATEGORY_ATTRIBUTE)?.name == Category.REGULAR_PLATFORM
                        }

                        if (!isPlatform) {
                            error("No file found for $id")
                        } else {
                            return null
                        }
                    }

                    resolvedTrees[module]?.let { return@render it }

                    val dependencyTrees = comp.dependencies.filterIsInstance<ResolvedDependencyResult>().mapNotNull {
                        render(it.selected, "$indent  ")
                    }
                    sb.append(indent).append(comp.moduleVersion?.toString() ?: comp.id).append("\n")
                    if (resolvedTrees.containsKey(module)) {
                        error("Cyclic dependencies")
                    }

                    val tree =
                        ResolvedDependencyTree(module, result?.file?.toPath(), ResolvedDependencies(dependencyTrees))
                    resolvedTrees[module] = tree
                    return tree
                }
                render(root, root = true)!! to resolvedTrees
            }.map { pair ->
                val root = pair.first
                val rootModule = root.module
                val map = pair.second.filterKeys { key -> key !== rootModule }
                map
            }
        }
    }
}

class ResolvedDependencyTree(
    val module: Module, val file: Path?, val dependencies: ResolvedDependencies
) {
    val name: String
        get() = module.name
    val group: String
        get() = module.group
    val version: String
        get() = module.version
}

@file:Suppress("UnstableApiUsage")

package eu.darkcube.build.remapper

import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import org.gradle.api.attributes.Bundling
import org.gradle.api.attributes.Category
import org.gradle.api.attributes.LibraryElements
import org.gradle.api.attributes.Usage
import org.gradle.api.component.AdhocComponentWithVariants
import org.gradle.api.component.SoftwareComponentFactory
import org.gradle.api.provider.Provider
import org.gradle.kotlin.dsl.named
import java.util.concurrent.atomic.AtomicInteger

class RemappedConfiguration(
    private val project: Project,
    private val componentFactory: SoftwareComponentFactory,
    private val namespace: String,
    val runtimeConfiguration: Provider<out Configuration>,
    val sourceConfiguration: Provider<out Configuration>,
    val remappedRuntimeConfiguration: Provider<out Configuration>,
    val remappedSourceConfiguration: Provider<out Configuration>
) {
    fun createComponent(
        name: String = "remap-${idName.incrementAndGet()}",
        apiName: String = "remapApiElements${idApi.incrementAndGet()}",
        runtimeName: String = "remapRuntimeElements${idRuntime.incrementAndGet()}"
    ): AdhocComponentWithVariants {
        val component = componentFactory.adhoc(name)
        project.components.add(component)
        val compileConfiguration = project.configurations.create(apiName).apply {
            isVisible = false
            isCanBeResolved = false
            isCanBeConsumed = false
            extendsFrom(remappedRuntimeConfiguration.get())
            attributes {
                attribute(Usage.USAGE_ATTRIBUTE, project.objects.named(Usage.JAVA_API))
                attribute(Category.CATEGORY_ATTRIBUTE, project.objects.named(Category.LIBRARY))
                attribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE, project.objects.named(LibraryElements.JAR))
                attribute(Bundling.BUNDLING_ATTRIBUTE, project.objects.named(Bundling.EXTERNAL))
            }
        }
        component.addVariantsFromConfiguration(compileConfiguration) {
            mapToMavenScope("compile")
        }

        val runtimeConfiguration = project.configurations.create(runtimeName).apply {
            isVisible = false
            isCanBeResolved = false
            isCanBeConsumed = false
            extendsFrom(remappedRuntimeConfiguration.get())
            attributes {
                attribute(Usage.USAGE_ATTRIBUTE, project.objects.named(Usage.JAVA_RUNTIME))
                attribute(Category.CATEGORY_ATTRIBUTE, project.objects.named(Category.LIBRARY))
                attribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE, project.objects.named(LibraryElements.JAR))
                attribute(Bundling.BUNDLING_ATTRIBUTE, project.objects.named(Bundling.EXTERNAL))
            }
        }
        component.addVariantsFromConfiguration(runtimeConfiguration) {
            mapToMavenScope("runtime")
        }

        return component
    }

    companion object {
        private val idName = AtomicInteger()
        private val idApi = AtomicInteger()
        private val idRuntime = AtomicInteger()
    }
}
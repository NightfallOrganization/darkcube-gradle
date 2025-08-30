@file:Suppress("UnstableApiUsage")

package eu.darkcube.build.remapper

import org.gradle.api.artifacts.Configuration
import org.gradle.api.provider.Provider

class RemappedConfiguration(
    private val namespace: String,
    val runtimeConfiguration: Provider<out Configuration>,
    val sourceConfiguration: Provider<out Configuration>,
    val remappedRuntimeConfiguration: Provider<out Configuration>,
    val remappedSourceConfiguration: Provider<out Configuration>
)
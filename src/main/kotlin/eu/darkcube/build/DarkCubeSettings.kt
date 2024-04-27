package eu.darkcube.build

import org.gradle.api.Plugin
import org.gradle.api.initialization.Settings
import org.gradle.kotlin.dsl.apply
import org.gradle.toolchains.foojay.FoojayToolchainsConventionPlugin

class DarkCubeSettings : Plugin<Settings> {
    override fun apply(settings: Settings) {
        settings.plugins.apply(FoojayToolchainsConventionPlugin::class)
    }
}
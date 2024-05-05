package eu.darkcube.build

import org.gradle.api.DefaultTask
import org.gradle.api.model.ObjectFactory
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import javax.inject.Inject

abstract class GenerateCheckstyle @Inject constructor(
    objects: ObjectFactory, @Input val checkstyleConfig: String
) : DefaultTask() {
    @OutputFile
    val checkstyleXmlFile = objects.fileProperty().convention { temporaryDir.resolve("checkstyle.xml") }

    @TaskAction
    fun run() {
        checkstyleXmlFile.asFile.get().writeText(checkstyleConfig)
    }
}
plugins {
    `java-library`
    id("eu.darkcube.darkcube")
}

//repositories {
//    mavenCentral()
//}

dependencies {
    api(project(":"))
}

tasks.register("testabc") {
    doFirst {
        configurations.compileClasspath.get().resolve().forEach {
            println(it)
        }
    }
}

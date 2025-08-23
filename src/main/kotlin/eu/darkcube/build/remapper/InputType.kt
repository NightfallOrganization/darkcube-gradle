package eu.darkcube.build.remapper

enum class InputType {
    BINARY {
        override fun filter(path: String): Boolean = path.endsWith(".class")
    },
    SOURCES {
        override fun filter(path: String): Boolean = path.endsWith(".java")
    };

    abstract fun filter(path: String): Boolean
}

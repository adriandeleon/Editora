package com.editora.run;

/**
 * A runnable Java main class in a project: its fully-qualified name, the owning module/project name (for
 * multi-module builds; may be blank), and the source file it lives in (used to key remembered program args).
 * A build-tool-neutral shape — populated from jdtls today, and from a source scan / build tool later.
 */
public record JavaMainClass(String fqn, String projectName, String filePath) {

    /**
     * The plain fully-qualified class name. jdtls names a class of a named module {@code <module>/<class>}
     * in {@link #fqn}; a saved configuration, the gutter and a classpath launch all use the class part.
     */
    public String className() {
        return classPart(fqn);
    }

    /** {@code mainClass} without a leading {@code <module>/}. */
    public static String classPart(String mainClass) {
        return mainClass == null ? null : mainClass.substring(mainClass.indexOf('/') + 1);
    }
}

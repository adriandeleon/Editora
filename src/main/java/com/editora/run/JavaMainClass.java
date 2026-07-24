package com.editora.run;

/**
 * A runnable Java main class in a project: its fully-qualified name, the owning module/project name (for
 * multi-module builds; may be blank), and the source file it lives in (used to key remembered program args).
 * A build-tool-neutral shape — populated from jdtls today, and from a source scan / build tool later.
 */
public record JavaMainClass(String fqn, String projectName, String filePath) {}

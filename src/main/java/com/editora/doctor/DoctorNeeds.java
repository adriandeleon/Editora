package com.editora.doctor;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Whether a tool is <em>needed</em> in a window, which decides red or neutral when it is missing: a language
 * server is needed when a file it serves is open, the project root has that language's marker file, or the user
 * set its command. Without this, turning LSP on painted every one of the two dozen servers red, though nothing
 * of Kotlin or Lua was ever open (review A1). Pure: the coordinator supplies the open languages and the root's
 * file names.
 */
public final class DoctorNeeds {

    private DoctorNeeds() {}

    /** The language-server ids a project root's file names call for (its build and manifest files). */
    public static Set<String> serversForRootFiles(Collection<String> fileNames) {
        Set<String> ids = new LinkedHashSet<>();
        if (fileNames == null) {
            return ids;
        }
        for (String raw : fileNames) {
            if (raw == null) {
                continue;
            }
            String name = raw.toLowerCase(Locale.ROOT);
            switch (name) {
                case "pom.xml" -> {
                    ids.add("java");
                    ids.add("maven-pom");
                    ids.add("xml");
                }
                // A Groovy-DSL build script alone does not call for the Groovy server: most such projects are Java,
                // and gvy reads build.gradle as plain Groovy. An open .groovy file, or a Jenkinsfile, does.
                case "build.gradle", "settings.gradle" -> ids.add("java");
                case "jenkinsfile" -> ids.add("groovy");
                // sbt, Mill (1.x and 0.x) and Scala CLI builds are Scala's own: Metals is what reads them.
                case "build.sbt", "build.mill", "build.sc", "project.scala" -> ids.add("scala");
                case "build.gradle.kts", "settings.gradle.kts" -> {
                    ids.add("java");
                    ids.add("kotlin");
                }
                case "package.json", "tsconfig.json", "jsconfig.json" -> {
                    ids.add("typescript");
                    ids.add("json");
                }
                case "pyproject.toml", "setup.py", "setup.cfg", "requirements.txt", "pipfile" -> ids.add("python");
                case "go.mod", "go.work" -> ids.add("go");
                case "cargo.toml" -> {
                    ids.add("rust");
                    ids.add("toml");
                }
                case "gemfile" -> ids.add("ruby");
                case "composer.json" -> ids.add("php");
                case "cmakelists.txt", "compile_commands.json", "meson.build" -> ids.add("clangd");
                case "dockerfile", "containerfile" -> ids.add("dockerfile");
                default -> {
                    if (name.endsWith(".csproj") || name.endsWith(".sln")) {
                        ids.add("csharp");
                    } else if (name.endsWith(".tf")) {
                        ids.add("terraform");
                    } else if (name.startsWith("astro.config.")) {
                        ids.add("astro");
                    } else if (name.endsWith(".typ")) {
                        ids.add("typst");
                    }
                }
            }
        }
        return ids;
    }

    /** Whether the project root's file names mark it as a Python project (for the formatter row). */
    public static boolean pythonProject(Collection<String> fileNames) {
        return serversForRootFiles(fileNames).contains("python");
    }

    /**
     * Whether a missing server should count as a problem: a file it serves is open ({@code openServers}), the
     * project calls for it ({@code rootServers}), or the user configured its command.
     */
    public static boolean serverNeeded(
            String serverId, Set<String> openServers, Set<String> rootServers, boolean commandConfigured) {
        return commandConfigured || openServers.contains(serverId) || rootServers.contains(serverId);
    }
}

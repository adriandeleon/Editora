package com.editora.maven;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Builds the Maven command that dumps a project's runtime classpath, and assembles the full classpath for
 * launching a main class without the Java language server. Used by the Run feature's build-tool fallback
 * (jdtls unavailable): {@code mvn dependency:build-classpath} writes the <em>dependency</em> classpath to a
 * file; the module's own compiled output ({@code target/classes}) is prepended. Pure and unit-tested; the
 * subprocess itself runs elsewhere (off the FX thread).
 *
 * <p>The command's executable is the caller's: the same argv prefix the Maven tasks tree launches
 * ({@code BuildTool.MAVEN.executable} — the project's {@code ./mvnw} once the folder is trusted, else the
 * Settings command override, else {@code mvn} on PATH), so Run resolves with the Maven version the project
 * pins and with the command the user configured (review D3).
 */
public final class MavenClasspath {

    private MavenClasspath() {}

    /** Which classpath a launch needs: a class under {@code src/main} runs on the runtime one, a test class on the test one. */
    public enum Scope {
        /** {@code compile} + the runtime dependencies (no test scope on a program's classpath, review D6). */
        RUNTIME("compile", "runtime"),
        /** {@code test-compile} + every dependency, for a main class under {@code src/test}. */
        TEST("test-compile", "test");

        private final String phase;
        private final String includeScope;

        Scope(String phase, String includeScope) {
            this.phase = phase;
            this.includeScope = includeScope;
        }

        /** The lifecycle phase that compiles what the launch runs. */
        public String phase() {
            return phase;
        }

        /** {@code dependency:build-classpath}'s {@code includeScope}. */
        public String includeScope() {
            return includeScope;
        }

        /**
         * The scope of a launch of a class declared in {@code source} of the module {@code root}: {@link #TEST}
         * when the file sits under the module's {@code src/test} tree, else {@link #RUNTIME}. Pure.
         */
        public static Scope forSource(Path source, Path root) {
            if (source == null || root == null) {
                return RUNTIME;
            }
            Path relative;
            try {
                relative = root.toAbsolutePath()
                        .normalize()
                        .relativize(source.toAbsolutePath().normalize());
            } catch (IllegalArgumentException differentRoots) {
                return RUNTIME;
            }
            return relative.getNameCount() > 2
                            && "src".equals(relative.getName(0).toString())
                            && "test".equals(relative.getName(1).toString())
                    ? TEST
                    : RUNTIME;
        }
    }

    /**
     * The Maven argv that compiles the module and writes its dependency classpath (pathSeparator-joined) to
     * {@code outputFile}. The compile phase runs first so {@code target/classes} is fresh before the run.
     *
     * @param executable the argv prefix that starts Maven ({@code ./mvnw}, the override, or {@code mvn})
     */
    public static List<String> argv(List<String> executable, Path outputFile, Scope scope) {
        return argv(executable, outputFile.toString(), File.pathSeparator, scope);
    }

    /** As {@link #argv(List, Path, Scope)}, naming the file and the separator as the machine Maven runs on does. */
    public static List<String> argv(List<String> executable, String outputFile, String pathSeparator, Scope scope) {
        List<String> argv = new ArrayList<>(executable);
        argv.add("-q");
        argv.add(scope.phase());
        argv.add("dependency:build-classpath");
        argv.add("-Dmdep.includeScope=" + scope.includeScope());
        argv.add("-Dmdep.outputFile=" + outputFile);
        argv.add("-Dmdep.pathSeparator=" + pathSeparator);
        return List.copyOf(argv);
    }

    /**
     * Like {@link #argv} but for a submodule in a multi-module build, run from the reactor root:
     * {@code -pl <moduleSelector> -am} ("also make") builds the sibling modules the target depends on, so
     * their compiled output lands in {@code outputFile}'s classpath. {@code outputFile} must be an absolute
     * path (the working directory is the reactor root, not the module).
     */
    public static List<String> reactorArgv(
            List<String> executable, Path outputFile, String moduleSelector, Scope scope) {
        return reactorArgv(executable, outputFile.toString(), moduleSelector, File.pathSeparator, scope);
    }

    /** As {@link #reactorArgv(List, Path, String, Scope)}, naming the file and the separator as Maven's machine does. */
    public static List<String> reactorArgv(
            List<String> executable, String outputFile, String moduleSelector, String pathSeparator, Scope scope) {
        List<String> argv = new ArrayList<>(executable);
        argv.add("-q");
        argv.add("-pl");
        argv.add(moduleSelector);
        argv.add("-am");
        argv.add(scope.phase());
        argv.add("dependency:build-classpath");
        argv.add("-Dmdep.includeScope=" + scope.includeScope());
        argv.add("-Dmdep.outputFile=" + outputFile);
        argv.add("-Dmdep.pathSeparator=" + pathSeparator);
        return List.copyOf(argv);
    }

    /**
     * The tasks tree's active toggle args as a launch's Maven takes them (review D13): the profiles
     * ({@code -Pdev}) and flags ({@code -o}, {@code -U}, {@code -s …}) are kept; the reactor toggle's
     * {@code -f <pom> -pl <module> -am} is dropped, because a launch chooses its own reactor (the module of
     * the class it runs, found through the {@code <modules>} walk) and a second {@code -pl} would select the
     * tree's module instead.
     */
    public static List<String> withoutReactorArgs(List<String> toggleArgs) {
        if (toggleArgs == null || toggleArgs.isEmpty()) {
            return List.of();
        }
        List<String> out = new ArrayList<>(toggleArgs.size());
        for (int i = 0; i < toggleArgs.size(); i++) {
            String arg = toggleArgs.get(i);
            if ("-am".equals(arg) || "--also-make".equals(arg)) {
                continue;
            }
            if ("-f".equals(arg) || "--file".equals(arg) || "-pl".equals(arg) || "--projects".equals(arg)) {
                i++; // its value
                continue;
            }
            out.add(arg);
        }
        return List.copyOf(out);
    }

    /** {@code argv} followed by {@link #withoutReactorArgs the launch-safe part} of {@code toggleArgs}. */
    public static List<String> withToggles(List<String> argv, List<String> toggleArgs) {
        List<String> extra = withoutReactorArgs(toggleArgs);
        if (extra.isEmpty()) {
            return argv;
        }
        List<String> out = new ArrayList<>(argv);
        out.addAll(extra);
        return List.copyOf(out);
    }

    /**
     * Full launch classpath = the module's compiled output (when present) followed by the dependency
     * classpath from {@code dependencyClasspath} (a {@code File.pathSeparator}-joined string). Blank/empty
     * entries are dropped. {@code root} is the Maven module root ({@code root/target/classes}; a
     * {@link Scope#TEST} launch adds {@code target/test-classes} first).
     */
    public static List<String> assemble(String dependencyClasspath, Path root) {
        return assemble(dependencyClasspath, root, Scope.RUNTIME);
    }

    /** As {@link #assemble(String, Path)} for {@code scope}'s output folders. */
    public static List<String> assemble(String dependencyClasspath, Path root, Scope scope) {
        if (root == null) {
            return assemble(dependencyClasspath, (String) null, File.pathSeparator);
        }
        Path target = root.resolve("target");
        String classes = target.resolve("classes").toString();
        List<String> cp = assemble(dependencyClasspath, classes, File.pathSeparator);
        if (scope == Scope.TEST) {
            List<String> withTests = new ArrayList<>(cp.size() + 1);
            withTests.add(target.resolve("test-classes").toString());
            withTests.addAll(cp);
            return withTests;
        }
        return cp;
    }

    /**
     * As {@link #assemble(String, Path)}, with the module's output folder and the separator named as the
     * machine the program runs on names them (a remote host's paths, {@code :}).
     */
    public static List<String> assemble(String dependencyClasspath, String classesFolder, String pathSeparator) {
        List<String> cp = new ArrayList<>();
        if (classesFolder != null) {
            cp.add(classesFolder);
        }
        if (dependencyClasspath != null) {
            for (String e : dependencyClasspath.split(java.util.regex.Pattern.quote(pathSeparator))) {
                String t = e.strip();
                if (!t.isEmpty()) {
                    cp.add(t);
                }
            }
        }
        return cp;
    }
}

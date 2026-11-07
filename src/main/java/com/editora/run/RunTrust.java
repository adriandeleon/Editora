package com.editora.run;

import java.nio.file.Path;
import java.util.function.Predicate;

import com.editora.build.BuildTool;
import org.jspecify.annotations.Nullable;

/**
 * Which build tool's workspace-trust gate a Run or Debug launch has to pass when it starts that tool on a
 * project rather than on one file: Leiningen evaluates {@code project.clj} (its {@code :plugins}, its
 * {@code :injections}), the Clojure CLI reads {@code deps.edn} (an alias's JVM options), Babashka reads
 * {@code bb.edn}, Scala CLI given a project folder compiles every source there with its macros and
 * {@code //> using plugin} compiler plugins, and an npm script runs the package's {@code pre}/{@code post} hooks
 * under the project's own package manager. None of that is the file the user pressed Run on, so it asks first,
 * exactly as the tool's tasks window would ({@code BuildTrust}, {@code ui.BuildCoordinator}).
 *
 * <p>A file run on its own — a Clojure script in no project, a Scala file Scala CLI is given alone, a Groovy or
 * Kotlin script — passes no gate: the code that runs is the code the user asked to run.
 *
 * <p>Pure: the disk is reached only through the predicate a caller passes.
 */
public final class RunTrust {

    private RunTrust() {}

    /**
     * A gate to pass: {@code tool}'s workspace trust for a launch in {@code root}.
     *
     * @param tool the build tool whose project is loaded
     * @param root the folder it runs in, where it reads that project
     */
    public record Gate(BuildTool tool, Path root) {}

    /**
     * The gate Run File or Debug on {@code file} of {@code language} (its text {@code text}) has to pass, or null
     * when it runs the file alone. {@code top} bounds the walk up for a Scala file's build (the open project, or
     * null for none); {@code isFile} answers whether a file exists.
     */
    public static @Nullable Gate forFile(
            @Nullable String language, Path file, String text, @Nullable Path top, Predicate<Path> isFile) {
        if ("clojure".equals(language)) {
            return clojure(file, text, null, isFile);
        }
        if ("scala".equals(language)) {
            return scalaCli(ScalaRun.ownerOf(file, top, isFile));
        }
        return null;
    }

    /**
     * The gate a Clojure run of {@code file} has to pass: the tool {@link ClojureRun#plan} picks, when the folder
     * it runs in holds that tool's project file ({@code deps.edn}, {@code project.clj}, {@code bb.edn}) — the tool
     * reads it from there. {@code directory} is a run configuration's own working directory, or null for the
     * plan's (the project's folder, else the file's); {@code file} is null when the configuration names no file.
     */
    public static @Nullable Gate clojure(
            @Nullable Path file, String text, @Nullable Path directory, Predicate<Path> isFile) {
        ClojureRun.Plan plan = ClojureRun.plan(file, text == null ? "" : text, isFile);
        Path where = directory != null ? directory : plan.directory();
        if (where == null || !isFile.test(where.resolve(ClojureRun.projectFile(plan.tool())))) {
            return null;
        }
        BuildTool tool = switch (plan.tool()) {
            case CLI -> BuildTool.CLOJURE;
            case LEIN -> BuildTool.LEIN;
            case BB -> BuildTool.BABASHKA;
        };
        return new Gate(tool, where);
    }

    /**
     * The gate Scala CLI has to pass for a file {@code owner} says it belongs to: Scala CLI's, on the project
     * folder it is given ({@link ScalaRun#cliInputs}) for a file of a Scala CLI project; null for a file run alone.
     * A file of an sbt or Mill build runs through that tool's own tasks window, which asks itself.
     */
    public static @Nullable Gate scalaCli(ScalaRun.@Nullable Owner owner) {
        if (owner == null || owner.build() != ScalaRun.Build.SCALA_CLI || owner.root() == null) {
            return null;
        }
        return new Gate(BuildTool.SCALA_CLI, owner.root());
    }

    /** The gate an npm configuration's package script has to pass, run from {@code directory}. */
    public static Gate npm(Path directory) {
        return new Gate(BuildTool.NPM, directory);
    }
}

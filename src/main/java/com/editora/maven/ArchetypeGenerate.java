package com.editora.maven;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Builds the {@code mvn archetype:generate} command line.
 *
 * <p><b>The batch flags are load-bearing, not cosmetic.</b> {@code archetype:generate} is <em>interactive by
 * default</em>: it prompts on stdin for every coordinate and then for a Y/N confirmation. The runner it goes
 * through ({@code build/BuildService}) deliberately gives the child <b>no stdin and no timeout</b> — so an
 * argv missing {@code -B} or {@code -DinteractiveMode=false} does not fail, it <b>hangs forever</b> with a
 * silent Output tab and a process the user must stop by hand. {@link ArchetypeGenerateTest} asserts the whole
 * list for exactly this reason; do not "simplify" the flags away.
 *
 * <p>Everything is passed as {@code -D} properties rather than typed at a prompt, which is also what makes
 * the generation reproducible and scriptable.
 */
public final class ArchetypeGenerate {

    private ArchetypeGenerate() {}

    /**
     * @param mvnExecutable the resolved Maven launcher, e.g. {@code ["mvn"]} — from
     *     {@code BuildTool.MAVEN.executable(...)}, so a user's Settings override is honoured
     */
    /**
     * Whether generation must be detached from a Maven project already sitting in the target directory.
     *
     * <p>{@code archetype:generate} run inside an existing project tries to register the new project as a
     * {@code <module>} of it, and fails outright when that project is not {@code packaging=pom}: <em>"Unable
     * to add module to the current project as it is not of packaging type 'pom'"</em>. Generating a project
     * next to an unrelated jar project is an ordinary thing to want, so the run is detached instead — see
     * {@link #argv} and the {@code outputDirectory} it then passes.
     *
     * <p>An aggregator ({@code packaging=pom}) is deliberately left attached: adding the module there is
     * what the user almost certainly wants, and it is what Maven on the command line would do.
     *
     * <p>Note the check is on the pom in the <em>output</em> directory, not the working one — verified by
     * disassembling archetype-common 3.4.1, after a first attempt that merely moved the working directory
     * and failed identically. That is why the caller generates wholly inside a scratch directory and moves
     * the result, rather than pointing {@code outputDirectory} at a folder that already holds a project.
     *
     * @param packaging the target directory's own pom packaging, or null when it has no pom at all
     */
    public static boolean detachFromExistingProject(String packaging) {
        if (packaging == null) {
            return false; // no project there — nothing to be a module of
        }
        // A pom with no <packaging> is a jar, so blank detaches too.
        return !"pom".equals(packaging.strip());
    }

    /**
     * As {@link #argv}, but generating into {@code outputDirectory} — which must be a directory holding no
     * {@code pom.xml}, or the module registration described above fires against it.
     */
    public static List<String> detachedArgv(List<String> mvnExecutable, MavenProjectSpec spec, Path outputDirectory) {
        List<String> argv = new ArrayList<>(argv(mvnExecutable, spec));
        argv.add("-DoutputDirectory=" + outputDirectory);
        return List.copyOf(argv);
    }

    public static List<String> argv(List<String> mvnExecutable, MavenProjectSpec spec) {
        if (mvnExecutable == null || mvnExecutable.isEmpty() || spec == null || !spec.isValid()) {
            throw new IllegalArgumentException("archetype:generate needs a maven executable and a valid spec");
        }
        MavenArchetype a = spec.archetype();
        List<String> argv = new ArrayList<>(mvnExecutable);
        argv.add("archetype:generate");
        // Both, deliberately: -B is batch mode, but older archetype plugin versions still consult
        // interactiveMode, and a prompt here is unrecoverable (no stdin, no timeout).
        argv.add("-B");
        argv.add("-DinteractiveMode=false");
        argv.add("-DarchetypeGroupId=" + a.groupId());
        argv.add("-DarchetypeArtifactId=" + a.artifactId());
        argv.add("-DarchetypeVersion=" + a.version());
        if (a.hasRepository()) {
            argv.add("-DarchetypeRepository=" + a.repository());
        }
        argv.add("-DgroupId=" + spec.groupId());
        argv.add("-DartifactId=" + spec.artifactId());
        argv.add("-Dversion=" + spec.version());
        argv.add("-Dpackage=" + spec.packageName());
        return List.copyOf(argv);
    }

    /** The command line as shown in the Output tab header. */
    public static String displayCommand(List<String> argv) {
        return String.join(" ", argv);
    }
}

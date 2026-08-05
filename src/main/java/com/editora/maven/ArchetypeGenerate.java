package com.editora.maven;

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

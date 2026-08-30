package com.editora.run;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Decides whether the toolbar's run-configuration group (selector + Run/Debug/Stop) is worth showing.
 *
 * <p>It used to be unconditional, so a folder of Markdown notes carried a "Run config" dropdown that could
 * never hold anything — five toolbar slots spent on a feature that did not apply. Every IDE gates this on the
 * project being one you can actually launch, and so does this.
 *
 * <p>Pure and toolkit-free so the rule is unit-testable; the caller supplies the facts (see
 * {@code MainController.refreshRunConfigToolbar}).
 */
public final class RunConfigToolbar {

    /** Make files, in GNU make's own search order. Any one of them makes a folder launchable. */
    private static final String[] MAKEFILES = {"GNUmakefile", "makefile", "Makefile"};

    private RunConfigToolbar() {}

    /**
     * Whether to show the run-configuration group.
     *
     * <p>Hidden in Simple UI mode, which strips the toolbar to essentials regardless of the context.
     *
     * <p><b>Saved configurations always win.</b> Anything already saved must stay reachable, or the gate
     * strands it: a plain Java folder with no build file at all can hold a run configuration (saving one only
     * needs a {@code main} method), and a build file can be deleted or renamed after the fact. Hiding the only
     * UI that reaches a saved configuration would be a worse bug than the empty dropdown this fixes.
     *
     * @param simpleMode Simple UI mode is active
     * @param launchable see {@link #launchable}
     * @param savedConfigs how many run configurations this window has saved
     */
    public static boolean visible(boolean simpleMode, boolean launchable, int savedConfigs) {
        if (simpleMode) {
            return false;
        }
        return launchable || savedConfigs > 0;
    }

    /**
     * Whether the window's context is something you could launch.
     *
     * <p>With a project open, its build has to be <em>inside</em> it — a Maven/Gradle/npm/Cargo/Go marker
     * under the project root, or a makefile at the root itself. A folder of notes is not launchable just
     * because a sibling checkout above it has a {@code pom.xml}.
     *
     * <p>With no project, any detected marker counts — the build tool's own notion of context is the best
     * available when there is nothing to anchor to, and it is already what the build tool windows gate on.
     * (This was originally justified by the Projects feature being off by default; it now ships on, but the
     * branch still carries the no-project window, {@code --single-window}/{@code --no-session} launches and
     * anyone who opens a Maven checkout as loose files rather than as a project, where the group is exactly
     * what they want. It is the one path by which the group can appear for a folder that is not itself a
     * project: a build file <em>above</em> the open file is enough.)
     *
     * @param projectRoot the window's project root, or null when it has none
     * @param markerRoot where a build tool's marker file was detected, or null when none was
     * @param makefileAtProjectRoot the project root holds a makefile (false when there is no project)
     */
    public static boolean launchable(Path projectRoot, Path markerRoot, boolean makefileAtProjectRoot) {
        if (projectRoot == null) {
            return markerRoot != null;
        }
        return makefileAtProjectRoot || withinProject(projectRoot, markerRoot);
    }

    /**
     * Whether a detected build marker at {@code markerRoot} belongs to the project at {@code projectRoot}.
     *
     * <p>Build-marker detection walks <em>up</em> from whatever file is open, so it will happily root a tool
     * in an ancestor of the project: a folder of notes under {@code ~/src/work} would inherit a sibling
     * checkout's {@code pom.xml} and claim to be launchable. A build file above the project is not this
     * project's build.
     *
     * <p>Compared on path components, not strings, so {@code ~/src/app2} never counts as inside
     * {@code ~/src/app} — the same trap {@code PathKeys} guards for trusted folders.
     */
    public static boolean withinProject(Path projectRoot, Path markerRoot) {
        if (projectRoot == null || markerRoot == null) {
            return false;
        }
        return markerRoot
                .toAbsolutePath()
                .normalize()
                .startsWith(projectRoot.toAbsolutePath().normalize());
    }

    /**
     * Whether {@code root} holds a makefile.
     *
     * <p>Make is the one launchable build system with no {@code BuildTool} constant behind it (its targets are
     * run straight from the gutter), so it needs its own probe. Only the root is checked — unlike the build
     * tools, there is no walk up the tree: a makefile several directories above the project is not this
     * project's build.
     *
     * <p>False for null or on any I/O failure; an unreadable root should hide the group, not raise.
     */
    public static boolean hasMakefile(Path root) {
        if (root == null) {
            return false;
        }
        try {
            for (String name : MAKEFILES) {
                if (Files.isRegularFile(root.resolve(name))) {
                    return true;
                }
            }
        } catch (Exception e) {
            return false;
        }
        return false;
    }
}

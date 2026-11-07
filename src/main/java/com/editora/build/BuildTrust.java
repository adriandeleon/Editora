package com.editora.build;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import com.editora.maven.PomModel;
import com.editora.maven.PomParser;
import org.jspecify.annotations.Nullable;

import static com.editora.i18n.Messages.tr;

/**
 * What the workspace-trust prompt for a build says will run. A build of every tool but Go executes code the
 * repository supplies beyond the task the user picked — not only a shipped {@code ./mvnw}/{@code ./gradlew}/
 * {@code ./mill} (the one case the gate used to cover) but the pom's plugins and {@code <extensions>}, the files
 * under {@code .mvn/} ({@code extensions.xml} loads core extensions before the build, {@code maven.config} can add
 * {@code -s evil-settings.xml} or {@code -Dmaven.ext.class.path=…}), every Gradle build script, sbt's
 * {@code build.sbt} and {@code project/} plugins, Mill's {@code build.mill}/{@code build.sc} (Scala that Mill
 * compiles and runs) and its {@code mill-build/} meta-build, Leiningen's {@code project.clj} (Clojure that lein
 * evaluates, with its {@code :plugins}), Babashka's {@code bb.edn} tasks, Cargo's {@code build.rs} scripts and
 * procedural macros, the macros and compiler plugins a Scala CLI compile runs, the JVM options a {@code deps.edn}
 * alias adds, npm's pre/post and install hooks and a repository's own package manager ({@code .yarnrc.yml}'s
 * {@code yarnPath}), the JVM options files the launchers read ({@code .jvmopts}, {@code .sbtopts},
 * {@code .mill-jvm-opts}, {@code .lein-classpath}, which can add a {@code -javaagent}), and a
 * {@code .editora/project.json} that replaces the build command. The prompt lists what it found so the user
 * consents to something concrete; the gate itself (ask once per folder, remembered, inherited by subfolders,
 * revocable) lives in {@code ui.BuildCoordinator}. Pure except for the file probes, so it is unit-tested on
 * a temp folder.
 */
public final class BuildTrust {

    /** How many pom plugins are named before "and n more". */
    static final int MAX_LISTED_PLUGINS = 8;

    private BuildTrust() {}

    /**
     * The prompt's content for a build of {@code tool} from {@code root}.
     *
     * @param root the marker directory the build runs in
     * @param wrapper the repo-shipped wrapper the launch would execute, or {@code null}
     * @param projectOverride a {@code project.json} build-command override, or {@code ""}
     * @param items what will run, one line each, localized — never empty
     */
    public record Summary(Path root, @Nullable Path wrapper, String projectOverride, List<String> items) {}

    /**
     * Whether {@code tool}'s builds run repository code beyond the task the user picked, and so ask for trust first.
     * Every tool but Go does: the build definition is code (Maven's plugins, Gradle's scripts, sbt's
     * {@code build.sbt}, Mill's {@code build.mill}, Leiningen's {@code project.clj}, Babashka's {@code bb.edn}
     * tasks with their {@code :init} and {@code :depends}), compiling runs code (Cargo's {@code build.rs} and
     * procedural macros, Scala CLI's macros and {@code //> using plugin} compiler plugins), or the task runs more
     * than its name says (npm's {@code pre}/{@code post} scripts and install hooks, a repository's own
     * {@code yarnPath} yarn or {@code .pnpmfile.cjs}, a {@code deps.edn} alias's {@code :jvm-opts}) — on a
     * repository launcher or the user's own tool alike.
     *
     * <p>Go stays ungated by Go's own design: {@code go build}, {@code vet}, {@code fmt}, {@code mod},
     * {@code clean} and {@code install} never execute the module's code (no build scripts, {@code #cgo} flags
     * allow-listed, a {@code go.mod} {@code toolchain} line only downloads a checksum-verified Go release), and
     * {@code run}, {@code test} and {@code generate} run it because that is the task the user picked by name.
     * gopls is not withheld on an untrusted folder either ({@code lsp.UntrustedBuilds}), for the same reason.
     *
     * <p>An exhaustive switch, so a new tool does not compile until it is decided.
     */
    public static boolean gates(BuildTool tool) {
        return switch (tool) {
            case MAVEN, GRADLE, SBT, MILL, LEIN, BABASHKA, CLOJURE, CARGO, SCALA_CLI, NPM -> true;
            case GO -> false;
        };
    }

    public static Summary describe(BuildTool tool, Path root, @Nullable Path wrapper, String projectOverride) {
        List<String> items = new ArrayList<>();
        if (wrapper != null) {
            items.add(tr("trust.item.wrapper", wrapper.getFileName().toString()));
        }
        if (tool == BuildTool.MAVEN) {
            items.addAll(mavenItems(root));
        } else if (tool == BuildTool.GRADLE) {
            items.addAll(gradleItems(root));
        } else if (tool == BuildTool.SBT) {
            items.addAll(sbtItems(root));
        } else if (tool == BuildTool.MILL) {
            items.addAll(millItems(root));
        } else if (tool == BuildTool.LEIN) {
            items.addAll(leinItems(root));
        } else if (tool == BuildTool.BABASHKA) {
            items.add(tr("trust.item.bbTasks"));
        } else if (tool == BuildTool.CLOJURE) {
            items.add(tr("trust.item.depsAliases"));
        } else if (tool == BuildTool.CARGO) {
            items.addAll(cargoItems(root));
        } else if (tool == BuildTool.SCALA_CLI) {
            items.add(tr("trust.item.scalaCli"));
        } else if (tool == BuildTool.NPM) {
            items.addAll(npmItems(root));
        }
        if (projectOverride != null && !projectOverride.isBlank()) {
            items.add(tr("trust.item.projectOverride", projectOverride.strip(), tool.commandExample()));
        }
        if (items.isEmpty()) {
            items.add(tr("trust.item.buildFile", tool.markers().get(0)));
        }
        return new Summary(root, wrapper, projectOverride == null ? "" : projectOverride, List.copyOf(items));
    }

    private static List<String> mavenItems(Path root) {
        List<String> items = new ArrayList<>();
        List<String> plugins = new ArrayList<>();
        Path pom = root.resolve("pom.xml");
        try {
            if (!Files.isRegularFile(pom)) {
                throw new java.io.FileNotFoundException(pom.toString());
            }
            PomModel model = PomParser.parseFile(pom);
            for (PomModel.Plugin p : model.plugins()) {
                plugins.add(coordinates(p));
            }
            for (PomModel.Profile profile : model.profiles()) {
                for (PomModel.Plugin p : profile.plugins()) {
                    String c = coordinates(p);
                    if (!plugins.contains(c)) {
                        plugins.add(c);
                    }
                }
            }
        } catch (java.io.FileNotFoundException e) {
            // no pom beside the wrapper: nothing to list for it
        } catch (Exception e) {
            items.add(tr("trust.item.pomUnreadable"));
        }
        if (!plugins.isEmpty()) {
            if (plugins.size() <= MAX_LISTED_PLUGINS) {
                items.add(tr("trust.item.plugins", String.join(", ", plugins)));
            } else {
                items.add(tr(
                        "trust.item.pluginsMore",
                        String.join(", ", plugins.subList(0, MAX_LISTED_PLUGINS)),
                        plugins.size() - MAX_LISTED_PLUGINS));
            }
        }
        List<String> mvn = new ArrayList<>();
        for (String name : List.of("extensions.xml", "maven.config", "jvm.config")) {
            if (Files.isRegularFile(root.resolve(".mvn").resolve(name))) {
                mvn.add(".mvn/" + name);
            }
        }
        if (!mvn.isEmpty()) {
            items.add(tr("trust.item.mvnFiles", String.join(", ", mvn)));
        }
        return items;
    }

    private static List<String> gradleItems(Path root) {
        List<String> scripts = new ArrayList<>();
        for (String name : List.of(
                "build.gradle", "build.gradle.kts", "settings.gradle", "settings.gradle.kts", "gradle.properties")) {
            if (Files.isRegularFile(root.resolve(name))) {
                scripts.add(name);
            }
        }
        if (Files.isDirectory(root.resolve("buildSrc"))) {
            scripts.add("buildSrc/");
        }
        return scripts.isEmpty() ? List.of() : List.of(tr("trust.item.gradleScripts", String.join(", ", scripts)));
    }

    /**
     * sbt compiles and runs {@code build.sbt}, every {@code project/*.sbt} and {@code project/*.scala} (where
     * {@code plugins.sbt} adds plugins), and its launcher reads JVM and sbt options from {@code .jvmopts} and
     * {@code .sbtopts} in the build's folder.
     */
    private static List<String> sbtItems(Path root) {
        List<String> files = present(root, "build.sbt");
        if (Files.isDirectory(root.resolve("project"))) {
            files.add("project/");
        }
        List<String> items = new ArrayList<>();
        if (!files.isEmpty()) {
            items.add(tr("trust.item.sbtScripts", String.join(", ", files)));
        }
        List<String> opts = present(root, ".jvmopts", ".sbtopts");
        if (!opts.isEmpty()) {
            items.add(tr("trust.item.launcherOptions", String.join(", ", opts)));
        }
        return items;
    }

    /**
     * Mill compiles and runs {@code build.mill} (1.x; {@code build.sc} in 0.x, or the YAML header form), the
     * {@code mill-build/} meta-build that builds it, and its launcher reads JVM and Mill options from
     * {@code .mill-jvm-opts} and {@code .mill-opts}.
     */
    private static List<String> millItems(Path root) {
        List<String> files = present(root, "build.mill", "build.mill.yaml", "build.sc");
        if (Files.isDirectory(root.resolve("mill-build"))) {
            files.add("mill-build/");
        }
        List<String> items = new ArrayList<>();
        if (!files.isEmpty()) {
            items.add(tr("trust.item.millScripts", String.join(", ", files)));
        }
        List<String> opts = present(root, ".mill-jvm-opts", ".mill-opts");
        if (!opts.isEmpty()) {
            items.add(tr("trust.item.launcherOptions", String.join(", ", opts)));
        }
        return items;
    }

    /**
     * Leiningen evaluates {@code project.clj} (and a project's {@code profiles.clj}) as Clojure on every task, loading
     * its {@code :plugins}, {@code :hooks} and {@code :middleware}; its launcher puts a {@code .lein-classpath} on
     * lein's own classpath.
     */
    private static List<String> leinItems(Path root) {
        List<String> files = present(root, "project.clj", "profiles.clj");
        List<String> items = new ArrayList<>();
        if (!files.isEmpty()) {
            items.add(tr("trust.item.leinScripts", String.join(", ", files)));
        }
        List<String> opts = present(root, ".lein-classpath");
        if (!opts.isEmpty()) {
            items.add(tr("trust.item.launcherOptions", String.join(", ", opts)));
        }
        return items;
    }

    /**
     * {@code cargo build}, {@code check}, {@code test}, {@code run}, {@code clippy}, {@code doc} and {@code bench}
     * compile and run every crate's {@code build.rs} and procedural macros, the package's and its dependencies';
     * {@code .cargo/config.toml} can swap the compiler ({@code build.rustc}, {@code rustc-wrapper}) or the runner, and
     * a {@code rust-toolchain.toml} can name a toolchain folder ({@code path}).
     */
    private static List<String> cargoItems(Path root) {
        List<String> items = new ArrayList<>();
        items.add(tr("trust.item.cargoBuild"));
        List<String> settings =
                present(root, ".cargo/config.toml", ".cargo/config", "rust-toolchain.toml", "rust-toolchain");
        if (!settings.isEmpty()) {
            items.add(tr("trust.item.cargoSettings", String.join(", ", settings)));
        }
        return items;
    }

    /**
     * A script runs with its {@code pre} and {@code post} scripts, and {@code install}/{@code ci} run the package's
     * and every dependency's install scripts; {@code .npmrc} can set the {@code script-shell} and
     * {@code node-options}, {@code .yarnrc.yml}'s {@code yarnPath} makes a repository file the yarn that runs,
     * {@code .pnpmfile.cjs} hooks pnpm's installs, and {@code bunfig.toml} can preload scripts.
     */
    private static List<String> npmItems(Path root) {
        List<String> items = new ArrayList<>();
        items.add(tr("trust.item.npmScripts"));
        List<String> files = present(root, ".npmrc", ".yarnrc.yml", ".yarnrc", ".pnpmfile.cjs", "bunfig.toml");
        if (!files.isEmpty()) {
            items.add(tr("trust.item.packageManagerFiles", String.join(", ", files)));
        }
        return items;
    }

    /** Those of {@code names} that are regular files in {@code root}, in order. */
    private static List<String> present(Path root, String... names) {
        List<String> found = new ArrayList<>();
        for (String name : names) {
            if (Files.isRegularFile(root.resolve(name))) {
                found.add(name);
            }
        }
        return found;
    }

    private static String coordinates(PomModel.Plugin p) {
        String version = p.version() == null || p.version().isBlank() ? "" : ":" + p.version();
        return p.groupId() + ":" + p.artifactId() + version;
    }
}

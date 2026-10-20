package com.editora.build;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import com.editora.maven.PomModel;
import com.editora.maven.PomParser;

import static com.editora.i18n.Messages.tr;

/**
 * What the workspace-trust prompt for a build says will run. Every build of a Maven or Gradle project
 * executes code the repository supplies — not only a shipped {@code ./mvnw}/{@code ./gradlew} (the one
 * case the gate used to cover) but the pom's plugins and {@code <extensions>}, the files under
 * {@code .mvn/} ({@code extensions.xml} loads core extensions before the build, {@code maven.config} can
 * add {@code -s evil-settings.xml} or {@code -Dmaven.ext.class.path=…}), every Gradle build script, and a
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
    public record Summary(Path root, Path wrapper, String projectOverride, List<String> items) {}

    /** Whether {@code tool}'s builds run repository code and so ask for trust first (Maven and Gradle). */
    public static boolean gates(BuildTool tool) {
        return tool == BuildTool.MAVEN || tool == BuildTool.GRADLE;
    }

    public static Summary describe(BuildTool tool, Path root, Path wrapper, String projectOverride) {
        List<String> items = new ArrayList<>();
        if (wrapper != null) {
            items.add(tr("trust.item.wrapper", wrapper.getFileName().toString()));
        }
        if (tool == BuildTool.MAVEN) {
            items.addAll(mavenItems(root));
        } else if (tool == BuildTool.GRADLE) {
            items.addAll(gradleItems(root));
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

    private static String coordinates(PomModel.Plugin p) {
        String version = p.version() == null || p.version().isBlank() ? "" : ":" + p.version();
        return p.groupId() + ":" + p.artifactId() + version;
    }
}

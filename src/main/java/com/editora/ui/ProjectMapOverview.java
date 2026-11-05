package com.editora.ui;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;

import com.editora.search.ProjectFileList;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jspecify.annotations.Nullable;

/**
 * What a stranger wants to know about a project first, read from its file list (review 3 B4, E6): the README, the
 * build systems and their module folders, the language mix, how the top-level folders divide the files, the likely
 * entry points, and where the tests and docs are. It also counts each folder's children for the Map's folder tooltips.
 * Pure apart from {@code read}, which gives a root-relative file's text (the README, {@code package.json}); run it off
 * the FX thread.
 */
final class ProjectMapOverview {

    /** How many of each list the card shows. */
    static final int MAX_LISTED = 12;

    private static final int MAX_PACKAGE_JSON = 16;
    private static final int EXCERPT_PARAGRAPHS = 3;
    private static final int EXCERPT_CHARS = 480;

    /** A build file name, and the build system it stands for. */
    static final Map<String, String> MARKERS = markers();

    private static final Set<String> SKIPPED_SEGMENTS = com.editora.search.DependencyDirs.with(
            "vendor", "third_party", "third-party", "thirdparty", "testdata", "fixtures");
    private static final Set<String> TEST_FOLDERS = Set.of("test", "tests", "testing", "__tests__", "spec", "e2e");
    private static final Set<String> DOC_FOLDERS = Set.of("docs", "doc", "documentation", "man");
    private static final Set<String> MAIN_EXTENSIONS =
            Set.of("go", "rs", "py", "c", "cc", "cpp", "java", "kt", "scala", "ts", "js", "mjs", "swift", "cs", "rb");
    private static final Pattern GO_COMMAND = Pattern.compile("(?:^|.*/)cmd/[^/]+/main\\.go");
    private static final Pattern GO_COMMAND_FOLDER = Pattern.compile("(?:^|.*/)cmd/[^/]+");
    private static final Pattern APPLICATION =
            Pattern.compile("[A-Z][A-Za-z0-9]*Application\\.(?:java|kt|scala|groovy)");
    private static final Pattern README = Pattern.compile("(?i)readme(?:\\.(?:md|markdown|rst|txt|adoc|org))?");

    /** A build system the project uses: its name, the build file, and how many folders hold one. */
    record Build(String system, String marker, int folders) {}

    /** A folder that holds a build file ("" for the root), and which ones. */
    record Module(String folder, List<String> markers) {}

    /** A language and how many of the project's files are in it. */
    record Language(String name, int files) {}

    /** A top-level folder, its file count (everything below it) and its main language, if any. */
    record Folder(String name, int files, @Nullable String language) {}

    /**
     * What the Overview card shows. {@code folderCounts} maps a root-relative folder ("" for the root) to {@code
     * {child folders, child files, files below}}.
     */
    record Facts(
            int files,
            int folders,
            boolean truncated,
            @Nullable String readme,
            String readmeExcerpt,
            List<Build> builds,
            List<Module> modules,
            List<Language> languages,
            List<Folder> topFolders,
            List<String> entryPoints,
            int moreEntryPoints,
            List<String> testFolders,
            List<String> docFolders,
            Map<String, int[]> folderCounts) {

        static final Facts EMPTY = new Facts(
                0, 0, false, null, "", List.of(), List.of(), List.of(), List.of(), List.of(), 0, List.of(), List.of(),
                Map.of());

        /** The folders that hold a build file, root-relative. */
        Set<String> moduleFolders() {
            Set<String> folders = new LinkedHashSet<>();
            modules.forEach(module -> folders.add(module.folder()));
            return folders;
        }
    }

    private ProjectMapOverview() {}

    private static Map<String, String> markers() {
        Map<String, String> markers = new LinkedHashMap<>();
        markers.put("go.mod", "Go modules");
        markers.put("pom.xml", "Maven");
        markers.put("build.gradle", "Gradle");
        markers.put("build.gradle.kts", "Gradle");
        markers.put("settings.gradle", "Gradle");
        markers.put("settings.gradle.kts", "Gradle");
        markers.put("package.json", "npm");
        markers.put("Cargo.toml", "Cargo");
        markers.put("pyproject.toml", "Python (pyproject)");
        return Map.copyOf(markers);
    }

    /** The facts of a project whose files and folders (root-relative, {@code /}-separated) are {@code list}. */
    static Facts analyse(ProjectFileList.Snapshot list, Function<String, @Nullable String> read) {
        List<String> files = list.files();
        Map<String, int[]> counts = new HashMap<>();
        counts.put("", new int[3]);
        for (String folder : list.folders()) {
            counts.computeIfAbsent(folder, ignored -> new int[3]);
            counts.computeIfAbsent(parentOf(folder), ignored -> new int[3])[0]++;
        }
        Map<String, List<String>> markersByFolder = new LinkedHashMap<>();
        Map<String, Integer> languageFiles = new HashMap<>();
        Map<String, Map<String, Integer>> topLanguages = new HashMap<>();
        Map<String, Integer> topFiles = new LinkedHashMap<>();
        List<String> entries = new ArrayList<>();
        String readme = null;
        for (String file : files) {
            String parent = parentOf(file);
            counts.computeIfAbsent(parent, ignored -> new int[3])[1]++;
            for (String folder = parent; ; folder = parentOf(folder)) {
                counts.computeIfAbsent(folder, ignored -> new int[3])[2]++;
                if (folder.isEmpty()) {
                    break;
                }
            }
            String name = file.substring(file.lastIndexOf('/') + 1);
            boolean skipped = skipped(file);
            if ((MARKERS.containsKey(name) || ownGradleFile(parent, name)) && !skipped) {
                markersByFolder
                        .computeIfAbsent(parent, ignored -> new ArrayList<>())
                        .add(name);
            }
            if (parent.isEmpty() && readme == null && README.matcher(name).matches()) {
                readme = file;
            }
            String language = skipped ? null : language(name);
            String top = file.indexOf('/') < 0 ? null : file.substring(0, file.indexOf('/'));
            if (top != null) {
                topFiles.merge(top, 1, Integer::sum);
            }
            if (language != null) {
                languageFiles.merge(language, 1, Integer::sum);
                if (top != null) {
                    topLanguages
                            .computeIfAbsent(top, ignored -> new HashMap<>())
                            .merge(language, 1, Integer::sum);
                }
            }
            if (!skipped && !testPath(file) && entryPoint(file, name)) {
                entries.add(file);
            }
        }
        for (String folder : list.folders()) {
            if (GO_COMMAND_FOLDER.matcher(folder).matches() && !skipped(folder) && !testPath(folder)) {
                entries.add(folder + "/"); // a Go command: its folder, whatever its main file is called
            }
        }
        entries.addAll(packageEntryPoints(markersByFolder, read));
        Set<String> commandFolders = new java.util.HashSet<>();
        entries.stream().filter(entry -> entry.endsWith("/")).forEach(commandFolders::add);
        List<String> ranked = entries.stream()
                .filter(entry -> entry.endsWith("/") || !commandFolders.contains(parentOf(entry) + "/"))
                .distinct()
                .sorted(Comparator.comparingInt(ProjectMapOverview::depth).thenComparing(Comparator.naturalOrder()))
                .toList();

        List<Module> modules = new ArrayList<>();
        markersByFolder.entrySet().stream()
                .sorted(Map.Entry.<String, List<String>>comparingByKey(
                        Comparator.comparingInt(ProjectMapOverview::depth).thenComparing(Comparator.naturalOrder())))
                .forEach(entry -> modules.add(new Module(entry.getKey(), List.copyOf(entry.getValue()))));
        Map<String, Build> builds = new LinkedHashMap<>();
        for (Module module : modules) {
            Set<String> systems = new LinkedHashSet<>();
            for (String marker : module.markers()) {
                systems.add(MARKERS.getOrDefault(marker, "Gradle"));
            }
            for (String system : systems) {
                String marker = module.markers().stream()
                        .filter(m -> system.equals(MARKERS.getOrDefault(m, "Gradle")))
                        .findFirst()
                        .orElse(system);
                Build before = builds.get(system);
                builds.put(
                        system,
                        new Build(
                                system,
                                before == null ? marker : before.marker(),
                                before == null ? 1 : before.folders() + 1));
            }
        }
        List<Language> languages = languageFiles.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue()
                        .reversed()
                        .thenComparing(Map.Entry.comparingByKey()))
                .limit(6)
                .map(entry -> new Language(entry.getKey(), entry.getValue()))
                .toList();
        List<Folder> topFolders = new ArrayList<>();
        for (String folder : list.folders()) {
            if (folder.indexOf('/') < 0) {
                Map<String, Integer> mix = topLanguages.getOrDefault(folder, Map.of());
                String main = mix.entrySet().stream()
                        .max(Map.Entry.<String, Integer>comparingByValue()
                                .thenComparing(Map.Entry.comparingByKey(Comparator.reverseOrder())))
                        .map(Map.Entry::getKey)
                        .orElse(null);
                topFolders.add(new Folder(folder, topFiles.getOrDefault(folder, 0), main));
            }
        }
        topFolders.sort(Comparator.comparingInt(Folder::files).reversed().thenComparing(Folder::name));
        return new Facts(
                files.size(),
                list.folders().size(),
                list.truncated(),
                readme,
                readme == null ? "" : excerpt(read.apply(readme)),
                List.copyOf(builds.values()),
                List.copyOf(modules),
                languages,
                List.copyOf(topFolders.subList(0, Math.min(MAX_LISTED, topFolders.size()))),
                List.copyOf(ranked.subList(0, Math.min(MAX_LISTED, ranked.size()))),
                Math.max(0, ranked.size() - MAX_LISTED),
                roleFolders(list.folders(), TEST_FOLDERS, true),
                roleFolders(list.folders(), DOC_FOLDERS, false),
                Map.copyOf(counts));
    }

    /**
     * Whether {@code name} is a Gradle build file named after its folder ({@code spring-core/spring-core.gradle}), as
     * projects that name each module's build file so do.
     */
    static boolean ownGradleFile(String folder, String name) {
        if (folder.isEmpty()) {
            return false;
        }
        String own = folder.substring(folder.lastIndexOf('/') + 1);
        return name.equals(own + ".gradle") || name.equals(own + ".gradle.kts");
    }

    /** Whether a file is a likely entry point: a Go command, a {@code main.*} source, a {@code *Application} class. */
    static boolean entryPoint(String rel, String name) {
        if (GO_COMMAND.matcher(rel).matches() || name.equals("__main__.py")) {
            return true;
        }
        int dot = name.lastIndexOf('.');
        if (dot > 0) {
            String stem = name.substring(0, dot);
            String extension = name.substring(dot + 1).toLowerCase(Locale.ROOT);
            if (stem.equalsIgnoreCase("main") && MAIN_EXTENSIONS.contains(extension)) {
                return true;
            }
        }
        return APPLICATION.matcher(name).matches();
    }

    /** The {@code main} and {@code bin} files of the project's {@code package.json} files, root-relative. */
    private static List<String> packageEntryPoints(
            Map<String, List<String>> markersByFolder, Function<String, @Nullable String> read) {
        List<String> found = new ArrayList<>();
        int packagesRead = 0;
        ObjectMapper json = new ObjectMapper();
        for (Map.Entry<String, List<String>> entry : markersByFolder.entrySet()) {
            if (!entry.getValue().contains("package.json") || packagesRead++ >= MAX_PACKAGE_JSON) {
                continue;
            }
            String folder = entry.getKey();
            String text = read.apply(folder.isEmpty() ? "package.json" : folder + "/package.json");
            if (text == null) {
                continue;
            }
            try {
                JsonNode root = json.readTree(text);
                List<String> targets = new ArrayList<>();
                JsonNode main = root.path("main");
                if (main.isTextual()) {
                    targets.add(main.asText());
                }
                JsonNode bin = root.path("bin");
                if (bin.isTextual()) {
                    targets.add(bin.asText());
                } else if (bin.isObject()) {
                    bin.properties().forEach(property -> {
                        if (property.getValue().isTextual()) {
                            targets.add(property.getValue().asText());
                        }
                    });
                }
                for (String target : targets) {
                    String clean = target.strip().replace('\\', '/');
                    while (clean.startsWith("./")) {
                        clean = clean.substring(2);
                    }
                    if (!clean.isEmpty() && !clean.startsWith("/") && !clean.contains("..")) {
                        found.add(folder.isEmpty() ? clean : folder + "/" + clean);
                    }
                }
            } catch (Exception e) {
                // a package.json that does not parse names no entry point
            }
        }
        return found;
    }

    /** The shallowest folders named for a role (tests, docs), at most {@link #MAX_LISTED}. */
    private static List<String> roleFolders(List<String> folders, Set<String> names, boolean tests) {
        List<String> found = new ArrayList<>();
        for (String folder : folders) {
            String name = folder.substring(folder.lastIndexOf('/') + 1).toLowerCase(Locale.ROOT);
            if (skipped(folder)) {
                continue;
            }
            // A test folder inside another one (test/e2e) adds nothing: its parent is listed.
            if (names.contains(name) && found.stream().noneMatch(outer -> folder.startsWith(outer + "/"))) {
                found.add(folder);
            } else if (tests && folder.endsWith("src/test") && found.stream().noneMatch(folder::startsWith)) {
                found.add(folder);
            }
        }
        return found.stream()
                .sorted(Comparator.comparingInt(ProjectMapOverview::depth).thenComparing(Comparator.naturalOrder()))
                .limit(MAX_LISTED)
                .toList();
    }

    /**
     * The first paragraphs of a README as plain text: no badges, HTML, tables, code, link targets or Markdown marks; a
     * paragraph's wrapped lines joined again.
     */
    static String excerpt(@Nullable String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        List<String> paragraphs = new ArrayList<>();
        StringBuilder paragraph = new StringBuilder();
        int chars = 0;
        boolean fence = false;
        for (String raw : text.split("\\R")) {
            String line = raw.strip();
            if (line.startsWith("```") || line.startsWith("~~~")) {
                fence = !fence;
                continue;
            }
            boolean skipped = fence
                    || line.startsWith("<")
                    || line.startsWith("[![")
                    || line.startsWith("![")
                    || line.startsWith("[!")
                    || line.startsWith("|")
                    || line.matches("[-=*_]{3,}")
                    || line.matches("\\[[^]]+]:\\s*\\S+.*"); // a link reference definition
            boolean heading = line.startsWith("#");
            if (line.isEmpty() || skipped || heading) {
                if (!paragraph.isEmpty()) {
                    paragraphs.add(paragraph.toString());
                    chars += paragraph.length();
                    paragraph.setLength(0);
                }
                if (heading && !skipped) {
                    String title = plain(line.replaceAll("^#+\\s*", ""));
                    if (!title.isEmpty()) {
                        paragraphs.add(title);
                        chars += title.length();
                    }
                }
                if (paragraphs.size() >= EXCERPT_PARAGRAPHS || chars >= EXCERPT_CHARS) {
                    break;
                }
                continue;
            }
            String clean = plain(line.replaceAll("^[-*+]\\s+", "• "));
            if (!clean.isEmpty()) {
                paragraph.append(paragraph.isEmpty() ? "" : " ").append(clean);
            }
        }
        if (!paragraph.isEmpty() && paragraphs.size() < EXCERPT_PARAGRAPHS && chars < EXCERPT_CHARS) {
            paragraphs.add(paragraph.toString()); // the text ended inside a paragraph
        }
        String joined = String.join("\n", paragraphs);
        return joined.length() > EXCERPT_CHARS ? joined.substring(0, EXCERPT_CHARS - 1) + "…" : joined;
    }

    /** A Markdown line's text: images dropped, links and emphasis reduced to their words. */
    private static String plain(String line) {
        return line.replaceAll("<[^>]*>", "")
                .replaceAll("!\\[[^]]*]\\([^)]*\\)", "")
                .replaceAll("\\[([^]]*)]\\([^)]*\\)", "$1")
                .replaceAll("\\[([^]]*)]\\[[^]]*]", "$1")
                .replaceAll("\\[([^]]+)]", "$1")
                .replaceAll("[*_`]{1,3}([^*_`]+)[*_`]{1,3}", "$1")
                .strip();
    }

    /** The programming language a file is in, from its extension; null for prose, data and unknown kinds. */
    static @Nullable String language(String name) {
        int dot = name.lastIndexOf('.');
        if (dot <= 0) {
            return null;
        }
        return switch (name.substring(dot + 1).toLowerCase(Locale.ROOT)) {
            case "go" -> "Go";
            case "java" -> "Java";
            case "kt", "kts" -> "Kotlin";
            case "scala", "sc" -> "Scala";
            case "groovy", "gradle" -> "Groovy";
            case "clj", "cljs", "cljc", "edn" -> "Clojure";
            case "py", "pyi" -> "Python";
            case "rs" -> "Rust";
            case "c", "h" -> "C";
            case "cc", "cpp", "cxx", "hpp", "hh", "hxx" -> "C++";
            case "cs" -> "C#";
            case "ts", "tsx", "mts", "cts" -> "TypeScript";
            case "js", "jsx", "mjs", "cjs" -> "JavaScript";
            case "rb" -> "Ruby";
            case "php" -> "PHP";
            case "swift" -> "Swift";
            case "m", "mm" -> "Objective-C";
            case "dart" -> "Dart";
            case "lua" -> "Lua";
            case "sh", "bash", "zsh" -> "Shell";
            case "ps1" -> "PowerShell";
            case "sql" -> "SQL";
            case "proto" -> "Protocol Buffers";
            case "html", "htm" -> "HTML";
            case "css", "scss", "sass", "less" -> "CSS";
            case "vue" -> "Vue";
            case "svelte" -> "Svelte";
            case "ex", "exs" -> "Elixir";
            case "erl", "hrl" -> "Erlang";
            case "hs" -> "Haskell";
            case "ml", "mli" -> "OCaml";
            case "r" -> "R";
            case "jl" -> "Julia";
            case "zig" -> "Zig";
            case "tf" -> "HCL";
            default -> null;
        };
    }

    private static boolean skipped(String rel) {
        for (String segment : rel.split("/")) {
            if (SKIPPED_SEGMENTS.contains(segment.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private static boolean testPath(String rel) {
        for (String segment : rel.split("/")) {
            String lower = segment.toLowerCase(Locale.ROOT);
            if (TEST_FOLDERS.contains(lower) || lower.equals("examples") || lower.equals("samples")) {
                return true;
            }
        }
        return rel.contains("src/test/");
    }

    private static String parentOf(String rel) {
        int slash = rel.lastIndexOf('/');
        return slash < 0 ? "" : rel.substring(0, slash);
    }

    private static int depth(String rel) {
        int depth = 0;
        for (int i = 0; i < rel.length(); i++) {
            if (rel.charAt(i) == '/') {
                depth++;
            }
        }
        return depth;
    }
}

package com.editora.process;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every place Editora starts a child process, classified by which locale the child gets.
 *
 * <p>{@code LC_ALL=C} is for output Editora <em>parses</em>. It was once the default for everything, which
 * broke the user's own programs, builds and language servers on any path or text outside ASCII. The split is
 * only as good as the call sites, and a new one written by analogy with the nearest existing one would
 * silently pick a side. So the sides are written down here: a file that starts spawning processes fails this
 * test until someone decides, on purpose, which locale its child needs.
 *
 * <p>The two rules: <b>parse-stable</b> ({@code ProcessRunner.run}/{@code runBytes}/{@code applyStandardEnv})
 * only where the output is parsed or only the exit code matters; <b>user locale</b>
 * ({@code runInUserLocale}/{@code applyUserEnv}) for anything long-lived, anything whose output the user
 * reads, and any JVM tool handed the user's paths.
 */
class ChildLocalePolicyTest {

    private static final Path MAIN = Path.of("src/main/java/com/editora");
    private static final String RUNNER = "process/ProcessRunner.java";

    private static final Pattern PARSE_STABLE =
            Pattern.compile("ProcessRunner\\s*\\.\\s*(?:run|runBytes|applyStandardEnv)\\s*\\(");
    private static final Pattern USER_LOCALE =
            Pattern.compile("ProcessRunner\\s*\\.\\s*(?:runInUserLocale|runScrubbed|applyUserEnv)\\s*\\(");
    /** Starting a process any other way bypasses both environments (and the augmented PATH). */
    private static final Pattern RAW_SPAWN =
            Pattern.compile("new\\s+ProcessBuilder\\s*\\(|getRuntime\\(\\)\\s*\\.exec\\s*\\(");

    /** Output is parsed, or only the exit code is read: these may start a child with {@code LC_ALL=C}. */
    private static final Set<String> PARSE_STABLE_FILES = Set.of(
            "AppInfo.java", // git rev-parse of the build's own checkout
            "dap/DapManager.java", // probe(): `node --version`, `python -c "import debugpy"`
            "diagram/DiagramRenderer.java", // detect(): --version / --help
            "doctor/DoctorProbes.java", // version probes
            "git/GitService.java", // background reads: porcelain output
            "github/GitHubService.java", // gh JSON
            "install/InstallService.java", // `npm root -g`: a path
            "mermaid/Mermaid.java", // detect() + maid's JSON report
            "run/RunService.java", // `java -version`
            "search/Ripgrep.java", // rg --version
            "search/SearchService.java", // rg matches
            "typst/TypstRenderer.java", // detect()
            "ui/FileWorkflowCoordinator.java"); // pkexec probe + elevated save (exit code)

    /**
     * Long-lived, shown to the user, or a JVM tool on the user's paths: the inherited locale. The number is
     * how many such children the file starts at least — a count that drops means one of them went back to
     * the parse-stable environment (or to none).
     */
    private static final Map<String, Integer> USER_LOCALE_FILES = Map.ofEntries(
            Map.entry("agent/AcpClient.java", 1), // the agent CLI
            Map.entry("build/BuildService.java", 1), // Maven/Gradle/npm/cargo/go builds
            Map.entry("build/BuildTool.java", 1), // `gradle tasks` (a JVM in the project dir)
            Map.entry("dap/DapManager.java", 3), // debugpy adapter, js-debug adapter, javac of the user's file
            Map.entry("diagram/DiagramRenderer.java", 2), // render + export, scrubbed (PlantUML is a JVM)
            Map.entry("externaltool/ExternalToolService.java", 1), // the user's own filters
            Map.entry("git/GitService.java", 1), // user-initiated commands: they run the user's hooks
            Map.entry("install/InstallService.java", 5), // npm/pip/gem/… installs + tar extraction
            Map.entry("lsp/LanguageServerSession.java", 1), // language servers
            Map.entry("maven/MavenClasspathResolver.java", 1), // Maven on the user's project
            Map.entry("mermaid/Mermaid.java", 2), // render + export
            Map.entry("process/DesktopActions.java", 1), // terminal / file manager
            Map.entry("run/RunService.java", 1), // the user's program
            Map.entry("typst/TypstRenderer.java", 2), // render + export
            Map.entry("ui/PluginCoordinator.java", 1), // a plugin's own command
            Map.entry("web/HtmlPreviewService.java", 1)); // the browser

    /** Build their own {@code ProcessBuilder} and then apply one of the two environments above. */
    private static final Set<String> RAW_SPAWN_FILES = Set.of(
            "agent/AcpClient.java",
            "build/BuildService.java",
            "dap/DapManager.java",
            "lsp/LanguageServerSession.java",
            "process/DesktopActions.java",
            RUNNER, // run() itself, and the login-shell PATH probe
            "run/RunService.java",
            "web/HtmlPreviewService.java");

    @Test
    void onlyOutputParsersStartAChildInTheCLocale() throws IOException {
        Set<String> unexpected = new TreeSet<>(count(PARSE_STABLE).keySet());
        unexpected.removeAll(PARSE_STABLE_FILES);

        assertEquals(Set.of(), unexpected, """
                These files start a child with LC_ALL=C but are not known output parsers. That environment is \
                only for output Editora parses; a long-lived or user-facing child needs \
                ProcessRunner.runInUserLocale / applyUserEnv. Decide which it is, then list the file.""");
    }

    @Test
    void userFacingChildrenKeepTheUsersLocale() throws IOException {
        Map<String, Integer> found = count(USER_LOCALE);
        Map<String, String> regressed = new TreeMap<>();
        USER_LOCALE_FILES.forEach((file, atLeast) -> {
            int actual = found.getOrDefault(file, 0);
            if (actual < atLeast) {
                regressed.put(file, actual + " of " + atLeast);
            }
        });

        assertEquals(Map.of(), regressed, "user-facing children that no longer inherit the user's locale");

        Set<String> unlisted = new TreeSet<>(found.keySet());
        unlisted.removeAll(USER_LOCALE_FILES.keySet());
        assertEquals(Set.of(), unlisted, """
                These files start a child in the user's locale but are not listed. If Editora parses the \
                output, use ProcessRunner.run instead; otherwise list the file.""");
    }

    @Test
    void theOneParseStableChildOfTheDebuggerIsItsProbe() throws IOException {
        // DapManager starts both kinds. Its adapters and the javac of the user's file are counted above; what
        // may still use the C locale is the single availability probe.
        assertTrue(count(PARSE_STABLE).getOrDefault("dap/DapManager.java", 0) <= 1);
        // The language server session has none at all.
        assertEquals(0, count(PARSE_STABLE).getOrDefault("lsp/LanguageServerSession.java", 0));
    }

    @Test
    void nothingStartsAProcessWithoutChoosingAnEnvironment() throws IOException {
        Set<String> unexpected = new TreeSet<>(count(RAW_SPAWN).keySet());
        unexpected.removeAll(RAW_SPAWN_FILES);

        assertEquals(Set.of(), unexpected, """
                These files start a process outside ProcessRunner's two environments (no augmented PATH, no \
                decision about the locale). Use ProcessRunner.run / runInUserLocale, or build the \
                ProcessBuilder and apply applyStandardEnv / applyUserEnv, then list the file.""");
    }

    /** {@code relative path → matches} over the production sources. {@code ProcessRunner}'s own definitions
     *  of the two environments are not call sites, so it is skipped for those patterns. */
    private static Map<String, Integer> count(Pattern pattern) throws IOException {
        assertTrue(Files.isDirectory(MAIN), "Run from the Maven project root");
        Map<String, Integer> found = new TreeMap<>();
        try (Stream<Path> files = Files.walk(MAIN)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                String relative = MAIN.relativize(file).toString().replace('\\', '/');
                if (pattern != RAW_SPAWN && relative.equals(RUNNER)) {
                    continue;
                }
                Matcher m = pattern.matcher(stripComments(Files.readString(file)));
                int n = 0;
                while (m.find()) {
                    n++;
                }
                if (n > 0) {
                    found.put(relative, n);
                }
            }
        }
        return found;
    }

    /** Drops {@code //} and block comments, so a call named in prose (or a {@code {@link}}) is not counted. */
    private static String stripComments(String source) {
        return source.replaceAll("(?s)/\\*.*?\\*/", " ").replaceAll("(?m)//.*$", " ");
    }
}

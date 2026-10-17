package com.editora.lsp;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import org.eclipse.lsp4j.CodeActionContext;
import org.eclipse.lsp4j.CodeActionParams;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.TextDocumentIdentifier;
import org.eclipse.lsp4j.jsonrpc.ResponseErrorException;
import org.eclipse.lsp4j.jsonrpc.messages.ResponseErrorCode;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * MANUAL PROBE (opt-in, {@code @Tag("probe")}) — the jdtls behaviours {@link com.editora.install.InstallCatalog}
 * says to re-check whenever {@code JDTLS_VERSION} is bumped, measured in one run against a real server:
 *
 * <ol>
 *   <li><b>extendedClientCapabilities</b> — the code actions at a field with and without
 *       {@link LspManager#javaExtendedClientCapabilities}. Without them jdtls withholds the Generate / Override /
 *       Extract Interface actions; 1.60 went from 9 to 16.</li>
 *   <li><b>The inlay-hint off-by-one (#715)</b> — a range ending one line past the document returns an empty list,
 *       not an error; {@link LspManager#inclusiveLineRange} clamps it. Recorded either way, so a server that
 *       stops doing this shows up as {@code pastTheEnd > 0}.</li>
 *   <li><b>The blind {@code java/…} requests</b> — Editora sends them without any capability to gate on (they are
 *       never listed in {@code executeCommandProvider.commands}), so each must still exist: anything but
 *       {@code MethodNotFound} counts, since the probe sends throwaway parameters. Also checks that the commands
 *       Editora <em>does</em> gate on are still advertised.</li>
 * </ol>
 *
 * <p>Run: {@code ./mvnw test -Dtest=JdtlsUpgradeProbeTest -Dgroups=probe -Dlsp.probe=true
 * -Dlsp.probe.jdtls=/path/to/jdtls/bin/jdtls} (without the last property it uses the installed one).
 */
@Tag("probe")
class JdtlsUpgradeProbeTest {

    private static final String SOURCE = """
            package demo;

            import java.util.ArrayList;
            import java.util.List;

            public class App implements Runnable {
                private int count;
                private String label;

                public String greet(String prefix, int times) {
                    return prefix.repeat(times);
                }

                public void run() {
                    List<String> names = new ArrayList<>();
                    names.add(greet("hi", 2));
                    names.add(greet("bye", 3));
                    System.out.println(String.join(",", names) + label + count);
                }
            }
            """;

    /** Every request {@code src/main/java} sends with {@code rawRequest("java/…")}. */
    private static final List<String> BLIND_REQUESTS = List.of(
            "java/addOverridableMethods",
            "java/buildWorkspace",
            "java/checkConstructorsStatus",
            "java/checkDelegateMethodsStatus",
            "java/checkExtractInterfaceStatus",
            "java/checkHashCodeEqualsStatus",
            "java/checkToStringStatus",
            "java/classFileContents",
            "java/generateAccessors",
            "java/generateConstructors",
            "java/generateDelegateMethods",
            "java/generateHashCodeEquals",
            "java/generateToString",
            "java/getChangeSignatureInfo",
            "java/getMoveDestinations",
            "java/getRefactorEdit",
            "java/listOverridableMethods",
            "java/move",
            "java/organizeImports",
            "java/resolveUnimplementedAccessors",
            "java/searchSymbols");

    /** The {@code workspace/executeCommand} ids Editora gates on or calls. */
    private static final List<String> ADVERTISED_COMMANDS = List.of(
            JdtlsPaste.COMMAND,
            JdtlsSmartSemicolon.COMMAND,
            "java.project.isTestFile",
            "java.project.resolveStackTraceLocation",
            "java.project.listSourcePaths",
            "java.project.addToSourcePath",
            "java.project.removeFromSourcePath");

    private static String jdtls() {
        String explicit = System.getProperty("lsp.probe.jdtls", "");
        if (!explicit.isBlank()) return explicit;
        for (String candidate : List.of(
                System.getProperty("user.home") + "/.editora/plugins/lsp/java/bin/jdtls",
                "/opt/homebrew/bin/jdtls",
                "/usr/local/bin/jdtls")) {
            if (Files.isExecutable(Path.of(candidate))) return candidate;
        }
        return "";
    }

    private static void record(String name, Object value) {
        System.out.println("EVALUATION " + name + " = " + value);
    }

    private static <T> T get(CompletableFuture<T> f) throws Exception {
        return f.get(60, TimeUnit.SECONDS);
    }

    private static Position pos(String needle, int offset) {
        int at = SOURCE.indexOf(needle) + offset;
        String pre = SOURCE.substring(0, at);
        return new Position((int) pre.chars().filter(c -> c == '\n').count(), at - pre.lastIndexOf('\n') - 1);
    }

    @Test
    void behavioursEditoraDependsOn() throws Exception {
        Assumptions.assumeTrue(Boolean.getBoolean("lsp.probe"), "opt-in: -Dlsp.probe=true");
        Assumptions.assumeFalse(jdtls().isBlank(), "needs a local jdtls");
        record("jdtls", jdtls());
        Path root = Files.createTempDirectory("editora-jdtls-upgrade-").toRealPath();

        Map<String, Object> withoutExtended = new HashMap<>(LspManager.javaInitOptions(List.of()));
        withoutExtended.remove("extendedClientCapabilities");
        TreeSet<String> bare = codeActionTitles(root.resolve("bare"), withoutExtended, null);
        TreeSet<String> full = codeActionTitles(root.resolve("full"), LspManager.javaInitOptions(List.of()), root);

        record("codeActions.withoutExtended", bare.size() + " " + bare);
        record("codeActions.withExtended", full.size() + " " + full);
        TreeSet<String> gated = new TreeSet<>(full);
        gated.removeAll(bare);
        record("codeActions.gatedByExtended", gated);
        assertTrue(full.size() > bare.size(), "extendedClientCapabilities must still widen the code actions");
        for (String expected : List.of("toString", "hashCode", "Constructors", "Override", "Extract interface")) {
            assertTrue(
                    gated.stream().anyMatch(t -> t.toLowerCase().contains(expected.toLowerCase())),
                    "expected a gated action mentioning " + expected + " in " + gated);
        }
    }

    /**
     * Opens the fixture in a fresh project + workspace and returns the code-action titles at the {@code label}
     * field. With {@code fullProbes} non-null it also runs the inlay and blind-request checks on that session.
     */
    private TreeSet<String> codeActionTitles(Path dir, Map<String, Object> initOptions, Path fullProbes)
            throws Exception {
        Path project = Files.createDirectories(dir.resolve("project"));
        Path file = project.resolve("src/main/java/demo/App.java");
        Files.createDirectories(file.getParent());
        Files.writeString(file, SOURCE);
        Files.writeString(project.resolve("pom.xml"), """
                <project xmlns="http://maven.apache.org/POM/4.0.0"><modelVersion>4.0.0</modelVersion>
                <groupId>demo</groupId><artifactId>upgrade</artifactId><version>1</version>
                <properties><maven.compiler.release>21</maven.compiler.release></properties></project>
                """);
        String uri = file.toUri().toString();
        var s = new LanguageServerSession(
                new LspServerRegistry.ServerSpec(
                        "java", List.of(jdtls(), "-data", dir.resolve("data").toString()), List.of("pom.xml")),
                project,
                diagnostics -> {},
                (type, message) -> {},
                initOptions);
        try {
            assertTrue(s.start());
            s.didOpen(uri, "java", SOURCE);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(120);
            while (!s.isInitialized() && System.nanoTime() < deadline) Thread.sleep(100);
            assertTrue(s.isInitialized(), "handshake");
            // Document requests queue behind the import; one answered request means the project is in.
            get(s.documentSymbol(uri));

            Position at = pos("label;", 2);
            var actions = get(s.codeAction(uri, new Range(at, at), List.of()));
            TreeSet<String> titles = new TreeSet<>();
            for (var a : actions) {
                titles.add(a.isLeft() ? a.getLeft().getTitle() : a.getRight().getTitle());
            }
            if (fullProbes != null) {
                inlayRange(s, uri);
                blindRequests(s, uri);
            }
            return titles;
        } finally {
            s.dispose();
        }
    }

    private void inlayRange(LanguageServerSession s, String uri) throws Exception {
        String[] lines = SOURCE.split("\n", -1);
        int lineCount = lines.length;
        int lastLine = lineCount - 1;
        Range clamped = LspManager.inclusiveLineRange(0, lastLine, lineCount, lines[lastLine].length());
        // Hints can lag the import; poll the range that is known to work before comparing.
        int inRange = 0;
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
        while (inRange == 0 && System.nanoTime() < deadline) {
            inRange = get(s.inlayHint(uri, clamped)).size();
            if (inRange == 0) Thread.sleep(1_000);
        }
        int pastTheEnd = get(s.inlayHint(uri, new Range(new Position(0, 0), new Position(lineCount, 0))))
                .size();
        int padded = get(s.inlayHint(
                        uri, LspManager.inclusiveLineRange(-200, lastLine + 200, lineCount, lines[lastLine].length())))
                .size();
        record("inlay.lines", lineCount);
        record("inlay.pastTheEnd", pastTheEnd);
        record("inlay.clamped", inRange);
        record("inlay.clampedPadded", padded);
        assertTrue(inRange > 0, "the clamped range must return hints");
        assertEquals(inRange, padded, "the coordinator's over-scan must survive the clamp");
    }

    private void blindRequests(LanguageServerSession s, String uri) {
        var gson = new com.google.gson.Gson();
        Position at = pos("label;", 2);
        Object actionJson = gson.toJsonTree(
                new CodeActionParams(new TextDocumentIdentifier(uri), new Range(at, at), new CodeActionContext()));
        Map<String, String> outcome = new LinkedHashMap<>();
        List<String> missing = new ArrayList<>();
        for (String method : BLIND_REQUESTS) {
            Object params =
                    switch (method) {
                        case "java/buildWorkspace" -> Boolean.FALSE;
                        case "java/classFileContents" ->
                            new TextDocumentIdentifier("jdt://contents/none.jar/demo/None.class");
                        case "java/searchSymbols" -> Map.of("query", "App");
                        default -> actionJson;
                    };
            try {
                s.rawRequest(method, params, Duration.ofSeconds(60)).get(70, TimeUnit.SECONDS);
                outcome.put(method, "answered");
            } catch (ExecutionException ex) {
                if (ex.getCause() instanceof ResponseErrorException re) {
                    int code = re.getResponseError().getCode();
                    outcome.put(method, "error " + code);
                    if (code == ResponseErrorCode.MethodNotFound.getValue()) missing.add(method);
                } else {
                    outcome.put(method, "failed " + ex.getCause());
                }
            } catch (Exception ex) {
                outcome.put(method, "failed " + ex);
            }
        }
        outcome.forEach((method, result) -> record("request " + method, result));

        var provider = s.capabilities().getExecuteCommandProvider();
        List<String> commands = provider == null || provider.getCommands() == null ? List.of() : provider.getCommands();
        record("executeCommandProvider.commands", new TreeSet<>(commands));
        List<String> listedRequests =
                BLIND_REQUESTS.stream().filter(commands::contains).toList();
        record("blindRequestsNowAdvertised", listedRequests);
        List<String> unadvertised =
                ADVERTISED_COMMANDS.stream().filter(c -> !commands.contains(c)).toList();
        record("commandsNoLongerAdvertised", unadvertised);

        assertTrue(missing.isEmpty(), "jdtls no longer implements " + missing);
        assertTrue(unadvertised.isEmpty(), "jdtls no longer advertises " + unadvertised);
    }
}

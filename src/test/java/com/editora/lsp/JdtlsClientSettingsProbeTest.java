package com.editora.lsp;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.eclipse.lsp4j.CodeAction;
import org.eclipse.lsp4j.Command;
import org.eclipse.lsp4j.CompletionItem;
import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.InlayHint;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.PublishDiagnosticsParams;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Opt-in: every Java preference and client capability fix-group 1 of the Java LSP review turned on, asserted by
 * its effect on a real jdtls (run with {@code -Dgroups=probe -Dlsp.probe=true}; {@code -Dlsp.probe.jdtls=PATH}
 * picks another server, e.g. a newer release unpacked by hand). The session is the production one, started
 * with Editora's own {@code initializationOptions}, capabilities and {@code didChangeConfiguration} push, so a
 * key that stops reaching the server fails here:
 *
 * <ul>
 *   <li>A1 — {@code resolveSupport} lists {@code documentation}: a resolved completion carries Javadoc.
 *   <li>A2 — {@code java.signatureHelp.description.enabled}: the signature carries Javadoc.
 *   <li>A9 — {@code java.completion.filteredTypes}: no {@code sun.*}/{@code com.sun.*}/{@code jdk.*} type.
 *   <li>A10, A15 — {@code java.inlayHints.*}: a hint for a non-literal argument, a type hint for {@code var},
 *       none for numbered parameters ({@code List.of(e1:, e2:)}).
 *   <li>B3 — {@code java.symbols.includeSourceMethodDeclarations}: methods in {@code workspace/symbol}, and
 *       library types under {@code jdt://}.
 *   <li>B8, D9 — the {@code java.format.tabSize}/{@code insertSpaces} answers: a quick fix's new method is
 *       indented with spaces.
 *   <li>D7 — {@code inferSelectionSupport}: Extract to method and Introduce Parameter at a bare caret, an
 *       expression from {@code java/inferSelection}, and an edit for it.
 *   <li>D10 — Extract to field names its {@code initializedScopes}.
 *   <li>D11 — {@code moveRefactoringConfirmationSupport} (jdtls 1.61 final and newer): a move with problems
 *       answers a token, and asking again with it gives the edit. Recorded, not required, on an older server.
 * </ul>
 */
@Tag("probe")
class JdtlsClientSettingsProbeTest {

    private static String jdtls() {
        String chosen = System.getProperty("lsp.probe.jdtls", "");
        if (!chosen.isBlank()) {
            return chosen;
        }
        for (String candidate : List.of(
                System.getProperty("user.home") + "/.editora/plugins/lsp/java/bin/jdtls",
                System.getProperty("user.home") + "/.editora-dev/plugins/lsp/java/bin/jdtls",
                "/opt/homebrew/bin/jdtls",
                "/usr/local/bin/jdtls")) {
            if (Files.isExecutable(Path.of(candidate))) {
                return candidate;
            }
        }
        return "";
    }

    private static final String APP = """
            package demo;

            import java.util.ArrayList;
            import java.util.List;

            public class App {
                private String label = "x";
                private int count = 2;
                private Helper helper = new Helper();

                /**
                 * Repeats a prefix.
                 *
                 * @param prefix what to repeat
                 * @param n how often
                 * @return the greeting
                 */
                public String greet(String prefix, int n) {
                    return prefix.repeat(n);
                }

                int twice() {
                    if (helper != null) {
                        return count * 2;
                    }
                    return 0;
                }

                public void run() {
                    ArrayList<String> names = new ArrayList<>();
                    names.add(greet(label, count));
                    var size = names.size();
                    var list = List.of(1, 2);
                    names.removeIf(name -> name.isEmpty());
                    String f = String.format("%d", size);
                    System.out.println(names + f + list + helper);
                }
            }
            """;

    private static final String HELPER = """
            package demo;

            public class Helper {
            }
            """;

    private static final String AMBIGUOUS = """
            package demo;

            public class Ambig {
                Date when;
                Duration took;
            }
            """;

    private static final String BROKEN = """
            package demo;

            public class Broken {
                void typing() {
                    missing();
                    Object o = new Arr
                }
            }
            """;

    private static <T> T get(CompletableFuture<T> f) throws Exception {
        return f.get(60, TimeUnit.SECONDS);
    }

    private static Position pos(String text, String needle, int offset) {
        int at = text.indexOf(needle);
        assertTrue(at >= 0, needle);
        at += offset;
        String pre = text.substring(0, at);
        return new Position((int) pre.chars().filter(c -> c == '\n').count(), at - pre.lastIndexOf('\n') - 1);
    }

    private static void record(String name, Object value) {
        System.out.println("PROBE " + name + " = " + value);
    }

    private static List<CompletionItem> items(Either<List<CompletionItem>, org.eclipse.lsp4j.CompletionList> r) {
        return r == null ? List.of() : r.isLeft() ? r.getLeft() : r.getRight().getItems();
    }

    private static List<CodeAction> actions(List<Either<Command, CodeAction>> all) {
        List<CodeAction> out = new ArrayList<>();
        for (var e : all) {
            if (e.isRight()) {
                out.add(e.getRight());
            }
        }
        return out;
    }

    private static JdtlsRefactor.Request refactor(List<CodeAction> actions, String name) {
        for (CodeAction a : actions) {
            if (a.getCommand() != null) {
                JdtlsRefactor.Request r = JdtlsRefactor.parse(
                        a.getCommand().getCommand(),
                        a.getCommand().getArguments() == null
                                ? List.of()
                                : a.getCommand().getArguments().stream()
                                        .map(LspManager::asJson)
                                        .toList());
                if (r != null && r.name().equals(name)) {
                    return r;
                }
            }
        }
        return null;
    }

    private static Range range(Position at) {
        return new Range(at, at);
    }

    @Test
    void theJavaPreferencesAndCapabilitiesReachJdtls() throws Exception {
        Assumptions.assumeTrue(Boolean.getBoolean("lsp.probe"), "opt-in: -Dlsp.probe=true");
        Assumptions.assumeFalse(jdtls().isBlank(), "needs a local jdtls");
        Path root = Files.createTempDirectory("editora-jdtls-settings-").toRealPath();
        Path project = Files.createDirectories(root.resolve("project"));
        Path src = Files.createDirectories(project.resolve("src/main/java/demo"));
        Files.writeString(src.resolve("App.java"), APP);
        Files.writeString(src.resolve("Helper.java"), HELPER);
        Files.writeString(src.resolve("Broken.java"), BROKEN);
        Files.writeString(src.resolve("Ambig.java"), AMBIGUOUS);
        Files.writeString(project.resolve("pom.xml"), """
                <project xmlns="http://maven.apache.org/POM/4.0.0"><modelVersion>4.0.0</modelVersion>
                <groupId>demo</groupId><artifactId>settings-probe</artifactId><version>1</version>
                <properties><maven.compiler.release>21</maven.compiler.release></properties></project>
                """);
        String app = src.resolve("App.java").toUri().toString();
        String broken = src.resolve("Broken.java").toUri().toString();
        String ambiguous = src.resolve("Ambig.java").toUri().toString();
        var diagnostics = new CopyOnWriteArrayList<PublishDiagnosticsParams>();
        var imported = new java.util.concurrent.CountDownLatch(1); // jdtls's "ProjectStatus" ends the import
        var s = new LanguageServerSession(
                new LspServerRegistry.ServerSpec(
                        "java", List.of(jdtls(), "-data", root.resolve("data").toString()), List.of("pom.xml")),
                project,
                diagnostics::add,
                (type, message) -> {
                    record("status", type + " " + message);
                    if ("ProjectStatus".equals(type)) {
                        imported.countDown();
                    }
                },
                LspManager.javaInitOptions(List.of()));
        // The document's indentation, as LspManager.setDocumentIndent records it: four spaces.
        s.setIndentForUri(uri -> new FormattingIndent(4, true));
        try {
            assertTrue(s.start());
            s.didOpen(app, "java", APP);
            s.didOpen(broken, "java", BROKEN);
            s.didOpen(ambiguous, "java", AMBIGUOUS);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(90);
            while (!s.isInitialized() && System.nanoTime() < deadline) {
                Thread.sleep(100);
            }
            assertTrue(s.isInitialized(), "real handshake");
            assertTrue(imported.await(120, TimeUnit.SECONDS), "the project imported");
            // C3: Eclipse's project files stay in the -data workspace, out of the user's folder.
            for (String eclipse : List.of(".project", ".classpath", ".settings")) {
                assertFalse(Files.exists(project.resolve(eclipse)), eclipse + " written into the project");
            }
            assertNotNull(
                    get(s.hover(app, pos(APP, "greet(String", 2))), "imported (document requests queue behind it)");

            // A1: the resolved completion carries the Javadoc.
            var greetItems = items(get(s.completion(app, pos(APP, "names.add(", "names.".length()))));
            CompletionItem add = greetItems.stream()
                    .filter(i -> i.getLabel().equals("add"))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("names. completes add: "
                            + greetItems.stream().map(CompletionItem::getLabel).toList()));
            CompletionItem resolved = get(s.resolveCompletion(add));
            record("A1 resolved documentation", resolved.getDocumentation());
            assertNotNull(resolved.getDocumentation(), "A1: completionItem/resolve must carry the Javadoc");

            // A2: signature help carries the Javadoc.
            var help = get(s.signatureHelp(app, pos(APP, "String.format(", "String.format(".length()), null, false));
            assertNotNull(help);
            assertFalse(help.getSignatures().isEmpty());
            record("A2 signature documentation", help.getSignatures().get(0).getDocumentation());
            assertNotNull(help.getSignatures().get(0).getDocumentation(), "A2: java.signatureHelp.description.enabled");

            // A9: no JDK-internal types.
            var arr = items(get(s.completion(broken, pos(BROKEN, "new Arr", "new Arr".length()))));
            List<String> internal = arr.stream()
                    .map(i -> i.getLabel() + " " + i.getDetail() + " " + labelDetail(i))
                    .filter(t -> t.contains("sun.") || t.contains("com.sun") || t.contains("jdk."))
                    .toList();
            record("A9 completions", arr.size());
            record("A9 internal", internal);
            assertFalse(arr.isEmpty(), "new Arr completes");
            assertTrue(internal.isEmpty(), "A9: java.completion.filteredTypes");

            // A10, A15: inlay hints.
            List<InlayHint> hints = get(s.inlayHint(app, new Range(new Position(0, 0), pos(APP, "\n}\n", 1))));
            List<String> labels =
                    hints.stream().map(JdtlsClientSettingsProbeTest::label).toList();
            record("A10 hints", labels);
            record(
                    "A10 kinds",
                    hints.stream().map(h -> label(h) + "=" + h.getKind()).toList());
            assertTrue(
                    labels.stream().anyMatch(l -> l.startsWith("prefix")), "A10: a hint for the non-literal `label`");
            // jdtls leaves the kind out; LspManager.isParameterHint reads the label, and a type hint must not be
            // classed as a parameter hint, which Editora's literals filter could drop.
            assertTrue(
                    hints.stream()
                            .anyMatch(h ->
                                    label(h).startsWith(": int") && !LspManager.isParameterHint(h.getKind(), label(h))),
                    "A10: a type hint for `var` (java.inlayHints.variableTypes.enabled)");
            assertTrue(
                    hints.stream()
                            .anyMatch(h ->
                                    label(h).equals("String") && !LspManager.isParameterHint(h.getKind(), label(h))),
                    "A10: a type hint for a lambda parameter (java.inlayHints.parameterTypes.enabled)");
            assertFalse(labels.stream().anyMatch(l -> l.startsWith("e1")), "A15: suppressWhenSameNameNumbered");

            // B3: methods and library types in workspace/symbol.
            var greet = get(s.workspaceSymbol("greet"));
            record("B3 greet", greet);
            assertTrue(symbolNames(greet).stream().anyMatch(n -> n.startsWith("greet")), "B3: methods are symbols");
            var arrayList = get(s.workspaceSymbol("ArrayList"));
            assertTrue(symbolUris(arrayList).stream().anyMatch(u -> u.startsWith("jdt://")), "B3: library types");

            // B8, D9: a quick fix's new method follows the answered indentation.
            Diagnostic undefined = null;
            deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
            while (undefined == null && System.nanoTime() < deadline) {
                for (var p : diagnostics) {
                    if (p.getUri().endsWith("Broken.java")) {
                        for (Diagnostic d : p.getDiagnostics()) {
                            if (String.valueOf(
                                            d.getMessage().isLeft()
                                                    ? d.getMessage().getLeft()
                                                    : d.getMessage().getRight().getValue())
                                    .contains("missing()")) {
                                undefined = d;
                            }
                        }
                    }
                }
                Thread.sleep(100);
            }
            assertNotNull(undefined, "Broken.java's missing() is reported");
            var fixes = actions(get(s.codeAction(broken, undefined.getRange(), List.of(undefined))));
            CodeAction create = fixes.stream()
                    .filter(a -> a.getTitle().startsWith("Create method 'missing()'"))
                    .findFirst()
                    .orElseThrow();
            if (create.getEdit() == null) {
                create = get(s.resolveCodeAction(create));
            }
            String created = String.valueOf(create.getEdit());
            record("B8 created", created);
            assertTrue(created.contains("missing()"));
            assertFalse(created.contains("\t"), "B8: the new method is indented with the answered spaces");

            // D7: Extract to method and Introduce Parameter at a bare caret.
            Position caret = pos(APP, "prefix.repeat", 2);
            var atCaret = actions(get(s.codeAction(app, range(caret), List.of())));
            record("D7 titles", atCaret.stream().map(CodeAction::getTitle).toList());
            assertTrue(atCaret.stream().anyMatch(a -> a.getTitle().startsWith("Extract to method")), "D7: extract");
            assertTrue(
                    atCaret.stream().anyMatch(a -> a.getTitle().startsWith("Introduce Parameter")),
                    "D7: advancedIntroduceParameterRefactoringSupport");
            JdtlsRefactor.Request extract = refactor(atCaret, "extractMethod");
            assertNotNull(extract);
            assertTrue(JdtlsRefactor.needsInferredSelection(extract));
            JsonElement inferred = LspManager.asJson(get(s.rawRequest(
                    "java/inferSelection", JdtlsRefactor.inferSelectionParams(extract.name(), extract.params()))));
            var selections = JdtlsRefactor.selections(inferred);
            record(
                    "D7 selections",
                    selections.stream().map(JdtlsRefactor.Selection::label).toList());
            assertFalse(selections.isEmpty(), "D7: java/inferSelection offers expressions");
            JsonObject edit = LspManager.asJson(get(s.rawRequest(
                            "java/getRefactorEdit",
                            JdtlsRefactor.refactorEditParams(
                                    extract.name(),
                                    extract.params(),
                                    4,
                                    true,
                                    JdtlsRefactor.extractArguments(null, selections.get(0))))))
                    .getAsJsonObject();
            assertNotNull(JdtlsRefactor.editOf(edit), "D7: the chosen expression extracts: " + edit);

            // D10: Extract to field names where the field may be initialized.
            Position from = pos(APP, "prefix.repeat(n)", 0);
            Position to = pos(APP, "prefix.repeat(n)", "prefix.repeat(n)".length());
            var selected = actions(get(s.codeAction(app, new Range(from, to), List.of())));
            JdtlsRefactor.Request field = refactor(selected, "extractField");
            assertNotNull(field, "Extract to field is offered on a selection");
            record("D10 scopes", JdtlsRefactor.initializedScopes(field));
            assertFalse(JdtlsRefactor.initializedScopes(field).isEmpty(), "D10: initializedScopes");

            // A3: Organize Imports asks which Date and Duration are meant (java.util / java.sql, java.time /
            // javax.xml.datatype) and imports the choices; unanswered, jdtls gave up and changed nothing.
            List<String> askedNames = new CopyOnWriteArrayList<>();
            s.setClientUi(new LspClientUi() {
                @Override
                public CompletableFuture<String> chooseImport(
                        String serverId, String simpleName, int line, List<PickItem> items) {
                    askedNames.add(
                            simpleName + items.stream().map(PickItem::label).toList());
                    return CompletableFuture.completedFuture(items.get(0).id());
                }

                @Override
                public CompletableFuture<String> chooseAction(
                        String serverId, org.eclipse.lsp4j.MessageType type, String message, List<String> actions) {
                    return CompletableFuture.completedFuture(null);
                }

                @Override
                public CompletableFuture<String> inputBox(
                        String serverId, String prompt, String value, String placeHolder) {
                    return CompletableFuture.completedFuture(null);
                }

                @Override
                public CompletableFuture<String> quickPick(String serverId, String placeHolder, List<PickItem> items) {
                    return CompletableFuture.completedFuture(null);
                }

                @Override
                public void openLocation(String serverId, Path file, int line, int column) {}
            });
            var organize = new org.eclipse.lsp4j.CodeActionParams(
                    new org.eclipse.lsp4j.TextDocumentIdentifier(ambiguous),
                    new Range(new Position(0, 0), new Position(6, 0)),
                    new org.eclipse.lsp4j.CodeActionContext(List.of()));
            String organized = String.valueOf(LspManager.asJson(get(s.rawRequest("java/organizeImports", organize))));
            record("A3 asked", askedNames);
            record("A3 organized", organized);
            assertTrue(
                    askedNames.stream().anyMatch(n -> n.startsWith("Date")), "A3: the ambiguous Date is asked about");
            assertTrue(organized.contains("import java."), "A3: the chosen imports are written: " + organized);

            // B15: renaming Helper.java in the Project tree renames the class and its uses with it.
            String helper = src.resolve("Helper.java").toUri().toString();
            String assistant = src.resolve("Assistant.java").toUri().toString();
            var renamed = get(s.willRenameFiles(helper, assistant));
            record("B15 willRenameFiles", renamed);
            assertTrue(String.valueOf(renamed).contains("Assistant"), "B15: the class is renamed with its file");

            // D11: move twice() to the helper field, which it compares to null — an error jdtls asks about.
            var atTwice = actions(get(s.codeAction(app, range(pos(APP, "int twice", 6)), List.of())));
            JdtlsRefactor.Request move = refactor(atTwice, JdtlsRefactor.MOVE_INSTANCE_METHOD);
            assertNotNull(move, "Move instance method is offered");
            JsonElement targets = LspManager.asJson(get(s.rawRequest(
                    "java/getMoveDestinations",
                    JdtlsRefactor.moveParams("moveInstanceMethod", app, move.params(), null))));
            var destinations = JdtlsRefactor.instanceTargets(targets);
            assertFalse(destinations.isEmpty(), "the helper field is a destination: " + targets);
            JsonObject moveParams = JdtlsRefactor.moveParams(
                    "moveInstanceMethod",
                    app,
                    move.params(),
                    destinations.get(0).raw());
            JsonElement first = LspManager.asJson(get(s.rawRequest("java/move", moveParams)));
            String token = JdtlsRefactor.confirmationToken(first);
            record("D11 first answer", first);
            Boolean confirms = confirmsMoves();
            record("D11 server confirms moves", confirms);
            if (Boolean.TRUE.equals(confirms)) {
                assertNotNull(token, "D11: moveRefactoringConfirmationSupport makes jdtls ask: " + first);
                assertNull(JdtlsRefactor.editOf(first), "nothing is applied before the user agrees");
                assertNotNull(JdtlsRefactor.errorMessage(first), "the problems come with the token");
            }
            if (token != null) {
                JsonElement again = LspManager.asJson(
                        get(s.rawRequest("java/move", JdtlsRefactor.withConfirmationToken(moveParams, token))));
                assertNotNull(JdtlsRefactor.editOf(again), "D11: asking with the token applies: " + again);
            }
        } finally {
            s.dispose();
        }
    }

    private static final java.util.regex.Pattern CORE_BUNDLE =
            java.util.regex.Pattern.compile("org\\.eclipse\\.jdt\\.ls\\.core_1\\.(\\d+)\\.\\d+\\.(\\d{12})\\.jar");

    /**
     * Whether the server under test implements {@code moveRefactoringConfirmationSupport}: jdtls 1.61 final
     * (built September 2026) and newer. Read from the core bundle's name; null when it cannot be found.
     */
    private static Boolean confirmsMoves() {
        try {
            Path plugins = Path.of(jdtls()).toRealPath().getParent().getParent().resolve("plugins");
            try (var files = Files.list(plugins)) {
                for (Path jar : (Iterable<Path>) files::iterator) {
                    var m = CORE_BUNDLE.matcher(jar.getFileName().toString());
                    if (m.matches()) {
                        int minor = Integer.parseInt(m.group(1));
                        long build = Long.parseLong(m.group(2));
                        return minor > 61 || (minor == 61 && build >= 202609000000L);
                    }
                }
            }
        } catch (java.io.IOException | RuntimeException e) {
            // an unknown layout (a package manager's launcher): recorded, not asserted
        }
        return null;
    }

    private static String labelDetail(CompletionItem i) {
        return i.getLabelDetails() == null
                ? ""
                : String.valueOf(i.getLabelDetails().getDescription());
    }

    private static String label(InlayHint h) {
        if (h.getLabel() == null) {
            return "";
        }
        return h.getLabel().isLeft()
                ? h.getLabel().getLeft()
                : h.getLabel().getRight().stream()
                        .map(org.eclipse.lsp4j.InlayHintLabelPart::getValue)
                        .reduce("", String::concat);
    }

    private static List<String> symbolNames(
            Either<
                            List<? extends org.eclipse.lsp4j.SymbolInformation>,
                            List<? extends org.eclipse.lsp4j.WorkspaceSymbol>>
                    r) {
        if (r == null) {
            return List.of();
        }
        return r.isLeft()
                ? r.getLeft().stream()
                        .map(org.eclipse.lsp4j.SymbolInformation::getName)
                        .toList()
                : r.getRight().stream()
                        .map(org.eclipse.lsp4j.WorkspaceSymbol::getName)
                        .toList();
    }

    private static List<String> symbolUris(
            Either<
                            List<? extends org.eclipse.lsp4j.SymbolInformation>,
                            List<? extends org.eclipse.lsp4j.WorkspaceSymbol>>
                    r) {
        if (r == null) {
            return List.of();
        }
        return r.isLeft()
                ? r.getLeft().stream().map(si -> si.getLocation().getUri()).toList()
                : r.getRight().stream()
                        .map(ws -> ws.getLocation().isLeft()
                                ? ws.getLocation().getLeft().getUri()
                                : ws.getLocation().getRight().getUri())
                        .toList();
    }
}

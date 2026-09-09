package com.editora.lsp;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import org.eclipse.lsp4j.*;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

/** Evaluation-only, opt-in probe. Uses the actual production session and temporary Maven project. */
@Tag("probe")
class JavaLspEvaluationProbeTest {
    private static final String SOURCE = """
            package demo;
            import java.util.ArrayList;
            import java.util.HashMap;
            public class App implements Runnable {
                private int count;
                public String greet(String prefix, int n) { return prefix.repeat(n); }
                public void run() {
                    ArrayList<String> names = new ArrayList<>();
                    names.add(greet("hi", 2));
                    System.out.println(names);
                }
            }
            """;
    private static <T> T get(CompletableFuture<T> f) throws Exception { return f.get(45, TimeUnit.SECONDS); }
    private static Position pos(String text, String needle, int offset) {
        int at = text.indexOf(needle) + offset;
        String pre = text.substring(0, at);
        return new Position((int) pre.chars().filter(c -> c == '\n').count(), at - pre.lastIndexOf('\n') - 1);
    }
    private static void record(String name, Object value) { System.out.println("EVALUATION " + name + " = " + value); }
    @Test void realJavaFeatureMatrix() throws Exception {
        Assumptions.assumeTrue(Boolean.getBoolean("lsp.evaluation"));
        Path root = Files.createTempDirectory("editora-lsp-evaluation-").toRealPath();
        Path project = Files.createDirectories(root.resolve("project"));
        Path file = project.resolve("src/main/java/demo/App.java");
        Files.createDirectories(file.getParent());
        Files.writeString(file, SOURCE);
        Files.writeString(project.resolve("pom.xml"), """
                <project xmlns="http://maven.apache.org/POM/4.0.0"><modelVersion>4.0.0</modelVersion>
                <groupId>demo</groupId><artifactId>evaluation</artifactId><version>1</version>
                <properties><maven.compiler.release>25</maven.compiler.release></properties></project>
                """);
        String uri = file.toUri().toString();
        var diagnostics = new CopyOnWriteArrayList<PublishDiagnosticsParams>();
        var s = new LanguageServerSession(new LspServerRegistry.ServerSpec("java",
                List.of("/opt/homebrew/bin/jdtls", "-data", root.resolve("data").toString()), List.of("pom.xml")),
                project, diagnostics::add, (type, message) -> record("status", type + " " + message),
                LspManager.javaInitOptions(List.of()));
        long start = System.nanoTime();
        try {
            assertTrue(s.start());
            s.didOpen(uri, "java", SOURCE);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(70);
            while (!s.isInitialized() && System.nanoTime() < deadline) Thread.sleep(100);
            assertTrue(s.isInitialized(), "real handshake");
            record("initializeMillis", (System.nanoTime() - start) / 1_000_000);
            record("capabilities", s.capabilities());
            // Document requests serialize behind the server's import jobs.
            var symbols = get(s.documentSymbol(uri));
            record("documentSymbols", symbols.size()); assertFalse(symbols.isEmpty());
            var hover = get(s.hover(uri, pos(SOURCE, "greet(String", 2)));
            record("hover", hover); assertNotNull(hover);
            var defs = get(s.definition(uri, pos(SOURCE, "greet(\"hi\"", 2)));
            record("definition", defs);
            var refs = get(s.references(uri, pos(SOURCE, "greet(String", 2)));
            record("references", refs.size()); assertTrue(refs.size() >= 2);
            var library = get(s.definition(uri, pos(SOURCE, "ArrayList<String>", 2)));
            record("libraryDefinition", library);
            if (library.isLeft() && !library.getLeft().isEmpty()) {
                String libUri = library.getLeft().get(0).getUri();
                if (libUri.startsWith("jdt:")) {
                    Object source = get(s.rawRequest("java/classFileContents", new TextDocumentIdentifier(libUri)));
                    record("librarySourceRaw", source);
                    record("librarySourceLength", LspManager.rawStringResult(source) == null ? null : LspManager.rawStringResult(source).length());
                }
            }
            var sig = get(s.signatureHelp(uri, pos(SOURCE, "greet(\"hi\",", 6), null, false));
            record("signatures", sig == null ? null : sig.getSignatures().size());
            assertNotNull(sig); assertFalse(sig.getSignatures().isEmpty());
            var range = new Range(new Position(0, 0), new Position(10, 0));
            record("inlayHints", get(s.inlayHint(uri, range)).size());
            var tokens = get(s.semanticTokensFull(uri));
            record("semanticTokenIntegers", tokens == null ? null : tokens.getData().size());
            assertNotNull(tokens); assertFalse(tokens.getData().isEmpty());
            record("foldingRanges", get(s.foldingRange(uri)).size());
            record("selectionRanges", get(s.selectionRange(uri, List.of(pos(SOURCE,"names.add",2)))).size());
            record("highlights", get(s.documentHighlight(uri, pos(SOURCE,"names.add",2))).size());
            record("formatEdits", get(s.formatting(uri, new FormattingOptions(4, true))).size());
            record("rangeFormatEdits", get(s.rangeFormatting(uri, range, new FormattingOptions(4, true))).size());
            record("workspaceSymbols", get(s.workspaceSymbol("App")));
            record("prepareRename", get(s.prepareRename(uri, pos(SOURCE,"App implements",1))));
            var rename = get(s.rename(uri, pos(SOURCE,"App implements",1), "Renamed"));
            record("rename", rename);
            assertNotNull(rename); assertNotNull(WorkspaceEditMapper.map(rename));
            var calls = get(s.prepareCallHierarchy(uri, pos(SOURCE,"greet(String",2)));
            record("callHierarchy", calls.size());
            if (!calls.isEmpty()) record("incomingCalls", get(s.incomingCalls(calls.get(0))).size());
            var types = get(s.prepareTypeHierarchy(uri, pos(SOURCE,"App implements",1)));
            record("typeHierarchy", types.size());
            if (!types.isEmpty()) record("supertypes", get(s.supertypes(types.get(0))).size());
            record("codeActions", get(s.codeAction(uri, range, List.of())).stream().map(Object::toString).toList());
            var actionParams = new CodeActionParams(new TextDocumentIdentifier(uri),
                    new Range(pos(SOURCE, "count;", 2), pos(SOURCE, "count;", 2)), new CodeActionContext(List.of()));
            var gson = new com.google.gson.Gson();
            var actionJson = gson.toJsonTree(actionParams);
            Object organized = get(s.rawRequest("java/organizeImports", actionJson));
            record("organizeImports", organized);
            assertNotNull(organized, "typed custom response must preserve the organize-imports edit");
            if (Boolean.getBoolean("lsp.evaluation.generators")) for (var kind : JdtlsGenerate.Kind.values()) {
                try {
                    Object checked = get(s.rawRequest(kind.checkRequest(), actionJson));
                    var status = gson.toJsonTree(checked);
                    var candidates = JdtlsGenerate.candidates(kind, status);
                    record(kind + " candidates", candidates.size());
                    assertFalse(candidates.isEmpty(), kind + " must expose candidates");
                    Object generated = get(s.rawRequest(kind.generateRequest(),
                            JdtlsGenerate.generateParams(kind, actionJson, candidates, status)));
                    record(kind + " productionGenerate", generated);
                    assertNotNull(generated, kind + " must return a workspace edit");
                } catch (Exception ex) {
                    record(kind + " productionGenerateFailure", ex.toString());
                    throw ex;
                }
            }
            String completionText = SOURCE.replace("names.add", "names.ad");
            s.didChange(uri, completionText);
            var result = get(s.completion(uri, pos(completionText,"names.ad",8)));
            var items = result.isLeft() ? result.getLeft() : result.getRight().getItems();
            record("completionItems", items.size()); assertFalse(items.isEmpty());
            record("firstResolvedCompletion", get(s.resolveCompletion(items.get(0))));
            s.didChange(uri, SOURCE.replace("private int count", "private MissingType count"));
            deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
            while (System.nanoTime() < deadline && diagnostics.stream().noneMatch(d -> d.getDiagnostics().stream().anyMatch(x -> x.getMessage().toString().contains("MissingType")))) Thread.sleep(100);
            assertTrue(diagnostics.stream().anyMatch(d -> d.getDiagnostics().stream().anyMatch(x -> x.getMessage().toString().contains("MissingType"))), "live diagnostic after incremental change");
            record("diagnostics", diagnostics);
            s.didChange(uri, SOURCE); s.didSave(uri); s.didClose(uri);
            record("fixture", root);
        } finally { s.dispose(); }
    }
    @Test void evaluateRepositoryJavaSources() throws Exception {
        Assumptions.assumeTrue(Boolean.getBoolean("lsp.evaluation"));
        Path project = Path.of(System.getProperty("user.dir")).toRealPath();
        Path data = Files.createTempDirectory("editora-lsp-repository-data-");
        var diagnostics = new CopyOnWriteArrayList<PublishDiagnosticsParams>();
        var session = new LanguageServerSession(new LspServerRegistry.ServerSpec("java",
                List.of("/opt/homebrew/bin/jdtls", "-data", data.toString()), List.of("pom.xml")),
                project, diagnostics::add, (type, message) -> record("repoStatus", type + " " + message),
                LspManager.javaInitOptions(List.of()));
        try {
            assertTrue(session.start());
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(70);
            while (!session.isInitialized() && System.nanoTime() < deadline) Thread.sleep(100);
            assertTrue(session.isInitialized(), "repository handshake");
            for (String name : List.of("lsp/JavaRuntimes.java", "lsp/LanguageServerSession.java", "ui/LspCoordinator.java")) {
                Path file = project.resolve("src/main/java/com/editora/" + name);
                session.didOpen(file.toUri().toString(), "java", Files.readString(file));
                var symbols = get(session.documentSymbol(file.toUri().toString()));
                record("repoSymbols " + name, symbols.size());
                assertFalse(symbols.isEmpty());
            }
            record("repoWorkspaceSymbol", get(session.workspaceSymbol("LspManager")));
            if (Boolean.getBoolean("lsp.evaluation.build")) {
                record("repoFullBuildResponse", session.rawRequest("java/buildWorkspace", Boolean.TRUE).get(180, TimeUnit.SECONDS));
            }
            // Allow reconcile diagnostics to arrive after initial symbol/index requests.
            Thread.sleep(5000);
            for (var d : diagnostics) record("repoDiagnostics", d);
            record("repoDiagnosticsMessages", diagnostics.size());
            var latest = new java.util.HashMap<String, PublishDiagnosticsParams>();
            diagnostics.forEach(d -> latest.put(d.getUri(), d));
            record("repoDiagnosedUris", latest.size());
            record("repoErrorCount", latest.values().stream().flatMap(d -> d.getDiagnostics().stream()).filter(d -> d.getSeverity() == DiagnosticSeverity.Error).count());
            record("repoWarningCount", latest.values().stream().flatMap(d -> d.getDiagnostics().stream()).filter(d -> d.getSeverity() == DiagnosticSeverity.Warning).count());
            record("repoData", data);
        } finally { session.dispose(); }
    }

}

package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import javafx.scene.Scene;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;

import com.editora.lsp.FakeLanguageServer;
import com.editora.lsp.LspManager;
import com.editora.lsp.LspTestHooks;
import com.editora.search.ProjectFileList;
import com.editora.text.SemanticToken;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The editor's reading aids in the Project Map's code cards (Map code browser round 2): the server's semantic
 * highlighting and inlay hints, and the pinned scope headers, as the editor's settings say.
 */
@Tag("fx")
class MapCardEditorAidsFxTest {

    @TempDir
    Path temp;

    private static final String SOURCE = """
            class A {
                void run() {
                    int count = 1;
                    go(count);
                }
            }
            """;

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
    }

    /** A server that holds every file: its tokens and hints are the test's. */
    private static final class Server implements MapCardLinks.Source {
        final List<String> asked = new ArrayList<>();

        @Override
        public void find(Path root, MapCardLinks.Request request, Consumer<MapCardLinks.Answer> answer) {}

        @Override
        public void semanticTokens(Path file, int lineCount, int lastLineLength, Consumer<List<SemanticToken>> tokens) {
            asked.add("tokens " + lineCount + " " + lastLineLength);
            tokens.accept(List.of(new SemanticToken(2, 12, 5, "sem-variable")));
        }

        @Override
        public void inlayHints(
                Path file,
                int startLine,
                int endLine,
                int lineCount,
                int lastLineLength,
                Consumer<List<LspManager.InlayHintSpan>> hints) {
            asked.add("hints " + startLine + "-" + endLine);
            hints.accept(List.of(
                    new LspManager.InlayHintSpan(3, 11, "amount:", true),
                    new LspManager.InlayHintSpan(3, 11, "x:", true))); // a one-letter name: filtered out
        }
    }

    @Test
    void aCardShowsTheServersColoursAndHintsAndPinsItsScopes() throws Exception {
        Path project = Files.createDirectories(temp.resolve("project")).toRealPath();
        Path file = Files.writeString(project.resolve("A.java"), SOURCE);
        Server server = new Server();
        MapCardAids[] aids = {new MapCardAids(true, true, "all", true)};
        Stage[] stage = new Stage[1];
        ProjectMapView view = FxTestSupport.callOnFx(() -> {
            ProjectMapView made = new ProjectMapView(path -> {}, path -> false, path -> false);
            stage[0] = new Stage();
            Scene scene = new Scene(new StackPane(made), 1200, 800);
            scene.getStylesheets()
                    .add(MapCardEditorAidsFxTest.class
                            .getResource("/com/editora/styles/app.css")
                            .toExternalForm());
            stage[0].setScene(scene);
            stage[0].show();
            made.setRoot(project);
            made.setLinkSource(server);
            made.setCardAids(() -> aids[0]);
            return made;
        });
        try {
            ProjectMapPreview card = FxTestSupport.callOnFx(() -> view.cards.openFileCard(file));
            waitFor(() -> card.editor().getLength() > 0
                    && card.editor().getStyleOfChar(offset(card, 2, 13)).contains("sem-variable"));
            FxTestSupport.runOnFx(() -> {
                assertTrue(server.asked.contains("tokens 7 0"), server.asked.toString());
                assertTrue(server.asked.contains("hints 0-6"), server.asked.toString());
                assertEquals(1, card.inlayLabels(3).size());
                assertTrue(
                        card.inlayLabels(3).get(0).contains("amount:"),
                        card.inlayLabels(3).toString());
                assertTrue(card.inlayLabels(2).isEmpty());
                assertFalse(
                        card.editor().getStyleOfChar(offset(card, 2, 8)).contains("sem-variable"),
                        "only the token's characters");
                assertEquals(List.of(0, 1), card.stickyLinesFor(3), "class A and run() scrolled past");
                assertEquals(List.of(), card.stickyLinesFor(0));

                // Tokens or hints for text the card no longer shows are dropped.
                card.setSemanticTokens("other text", List.of(new SemanticToken(0, 0, 5, "sem-type")));
                assertFalse(card.editor().getStyleOfChar(0).contains("sem-type"));
                card.setInlayHints("other text", List.of(new LspManager.InlayHintSpan(0, 0, "y:")));
                assertTrue(card.inlayLabels(0).isEmpty());

                // The settings turn them off: taken away at once.
                aids[0] = MapCardAids.NONE;
                view.applyCardAids();
                assertFalse(card.editor().getStyleOfChar(offset(card, 2, 13)).contains("sem-variable"));
                assertTrue(card.inlayLabels(3).isEmpty());
                assertEquals(List.of(), card.stickyLinesFor(3));

                // And on again.
                aids[0] = new MapCardAids(true, false, "literals", true);
                view.applyCardAids();
                assertTrue(card.editor().getStyleOfChar(offset(card, 2, 13)).contains("sem-variable"));
                assertEquals(List.of(0, 1), card.stickyLinesFor(3));

                // Hints off the card's lines, or past a line's end, are not drawn.
                card.setInlayHints(
                        card.fullText(),
                        List.of(
                                new LspManager.InlayHintSpan(-1, 0, "a:"),
                                new LspManager.InlayHintSpan(99, 0, "b:"),
                                new LspManager.InlayHintSpan(3, 999, "c:")));
                assertTrue(card.inlayLabels(3).isEmpty());
                card.setInlayHints(card.fullText(), List.of());
                card.setStickyScroll(true); // already on: nothing changes

                // No source of links: the aids the settings want are none.
                view.setLinkSource(null);
                aids[0] = new MapCardAids(true, true, "all", true);
                view.applyCardAids();
                assertFalse(card.editor().getStyleOfChar(offset(card, 2, 13)).contains("sem-variable"));
                assertTrue(card.inlayLabels(3).isEmpty());
            });
        } finally {
            FxTestSupport.runOnFx(() -> {
                view.dispose();
                stage[0].close();
            });
        }
    }

    @Test
    void theLinkSearchAsksTheServerThatHoldsTheFileAndNoOther() throws Exception {
        Path root = Files.createDirectories(temp.resolve("lsp")).toRealPath();
        Path file = Files.writeString(root.resolve("A.java"), SOURCE);
        Path loose = Files.writeString(root.resolve("B.java"), "class B {}\n");
        ProjectMapLinkSearch none = new ProjectMapLinkSearch(null, noFiles(), Runnable::run, Runnable::run, () -> 0L);
        List<Object> got = new java.util.concurrent.CopyOnWriteArrayList<>();
        none.semanticTokens(file, 7, 0, got::add);
        none.inlayHints(file, 0, 6, 7, 0, got::add);
        assertEquals(List.of(List.of(), List.of()), got, "no servers: nothing");

        LspManager manager = new LspManager((f, d) -> {}, (t, m) -> {});
        try {
            org.eclipse.lsp4j.ServerCapabilities capabilities = new org.eclipse.lsp4j.ServerCapabilities();
            org.eclipse.lsp4j.SemanticTokensWithRegistrationOptions semantic =
                    new org.eclipse.lsp4j.SemanticTokensWithRegistrationOptions(
                            new org.eclipse.lsp4j.SemanticTokensLegend(List.of("variable"), List.of()));
            semantic.setFull(true);
            capabilities.setSemanticTokensProvider(semantic);
            capabilities.setInlayHintProvider(true);
            List<FakeLanguageServer> fakes = LspTestHooks.useFakeSessions(manager, capabilities);
            manager.configure(true, java.util.Map.of("java", "jdtls"));
            FxTestSupport.runOnFx(() -> manager.openDocument(file, root, "java", SOURCE));
            FakeLanguageServer fake = fakes.getFirst();
            fake.semanticTokensResponse = new org.eclipse.lsp4j.SemanticTokens(List.of(2, 12, 5, 0, 0));
            org.eclipse.lsp4j.InlayHint hint = new org.eclipse.lsp4j.InlayHint(
                    new org.eclipse.lsp4j.Position(3, 11),
                    org.eclipse.lsp4j.jsonrpc.messages.Either.forLeft("amount:"));
            hint.setKind(org.eclipse.lsp4j.InlayHintKind.Parameter);
            fake.inlayHintResponse = List.of(hint);
            ProjectMapLinkSearch search =
                    new ProjectMapLinkSearch(manager, noFiles(), Runnable::run, Runnable::run, () -> 0L);
            List<List<SemanticToken>> tokens = new java.util.concurrent.CopyOnWriteArrayList<>();
            List<List<LspManager.InlayHintSpan>> hints = new java.util.concurrent.CopyOnWriteArrayList<>();
            FxTestSupport.runOnFx(() -> {
                search.semanticTokens(file, 7, 0, tokens::add);
                search.inlayHints(file, 0, 6, 7, 0, hints::add);
                search.semanticTokens(loose, 1, 0, tokens::add); // no server holds it
            });
            waitFor(() -> tokens.size() == 2 && hints.size() == 1);
            List<SemanticToken> held =
                    tokens.stream().filter(list -> !list.isEmpty()).findFirst().orElseThrow();
            assertEquals(2, held.get(0).line());
            assertEquals(12, held.get(0).startChar());
            assertTrue(held.get(0).cssClasses().contains("sem-variable"), held.toString());
            assertEquals("amount:", hints.get(0).get(0).label().strip());
            assertTrue(tokens.contains(List.of()), "the file no server holds: none");
        } finally {
            manager.shutdownAll();
        }
    }

    private static ProjectIndex noFiles() {
        return new ProjectIndex() {
            @Override
            public ProjectMapSearchResult search(
                    Path at,
                    String query,
                    Kinds kinds,
                    int max,
                    Freshness freshness,
                    java.util.function.BooleanSupplier cancelled) {
                return ProjectMapSearchResult.EMPTY;
            }

            @Override
            public ProjectMapSearchResult searchListed(Path at, String query, Kinds kinds, int max) {
                return ProjectMapSearchResult.EMPTY;
            }

            @Override
            public void whenListed(Path at, Runnable then) {
                then.run();
            }

            @Override
            public ProjectFileList.@Nullable Snapshot snapshot(Path at) {
                return null;
            }
        };
    }

    private static int offset(ProjectMapPreview card, int line, int column) {
        return card.editor().getAbsolutePosition(line, column);
    }

    private static void waitFor(BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (System.nanoTime() < deadline) {
            if (FxTestSupport.callOnFx(condition::getAsBoolean)) {
                return;
            }
            TimeUnit.MILLISECONDS.sleep(20);
        }
        assertNotNull(null, "timed out");
    }
}

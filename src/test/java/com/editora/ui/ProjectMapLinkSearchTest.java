package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;

import com.editora.search.ProjectFileList;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static com.editora.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The name match that stands in for a language server ({@link ProjectMapLinkSearch}, Map explore position 5), with no
 * server: the name found under the asked column or on the asked line, the file read from disk when no tab holds it,
 * and the budget — files, time, size — that keeps a match on a huge project short and says so.
 */
class ProjectMapLinkSearchTest {

    @TempDir
    Path root;

    @BeforeAll
    static void englishCatalog() {
        com.editora.i18n.Messages.init("en");
    }

    /** A project file list of {@code files} (root-relative), or none at all. */
    private static ProjectIndex index(ProjectFileList.@Nullable Snapshot snapshot) {
        return new ProjectIndex() {
            @Override
            public ProjectMapSearchResult search(
                    Path at, String query, Kinds kinds, int max, Freshness freshness, BooleanSupplier cancelled) {
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
                return snapshot;
            }
        };
    }

    private static ProjectFileList.Snapshot listed(boolean truncated, String... files) {
        return new ProjectFileList.Snapshot(List.of(files), List.of(), truncated);
    }

    private ProjectMapLinkSearch search(ProjectFileList.@Nullable Snapshot snapshot) {
        return new ProjectMapLinkSearch(null, index(snapshot), Runnable::run, Runnable::run, () -> 0L);
    }

    private List<MapCardLinks.Answer> ask(ProjectMapLinkSearch search, MapCardLinks.Request request) {
        List<MapCardLinks.Answer> answers = new ArrayList<>();
        search.find(root, request, answers::add);
        return answers;
    }

    private void project() throws Exception {
        Files.createDirectories(root.resolve("src"));
        Files.writeString(root.resolve("src/A.java"), "class A {\n  void go() { b.add(1); add(2); }\n}\n");
        Files.writeString(root.resolve("src/B.java"), "class B {\n  int add(int x) { return x; }\n}\n");
        Files.writeString(root.resolve("src/C.java"), "class C {}\n"); // the name is not in it
    }

    @Test
    void withNoTextGivenTheFileIsReadAndTheNameUnderTheColumnIsAskedAbout() throws Exception {
        project();
        List<MapCardLinks.Answer> answers = ask(
                search(listed(false, "src/A.java", "src/B.java", "src/C.java")),
                new MapCardLinks.Request(MapCardLinks.Kind.REFERENCES, root.resolve("src/B.java"), 1, 7, null, null));
        assertEquals(1, answers.size());
        MapCardLinks.Answer answer = answers.getFirst();
        assertEquals("add", answer.name(), "the word at line 2, column 8");
        assertEquals(MapCardLinks.Via.NAME, answer.via());
        assertEquals(
                List.of("A.java:1:16", "A.java:1:24", "B.java:1:6"),
                answer.hits().stream()
                        .map(hit -> hit.file().getFileName() + ":" + hit.line() + ":" + hit.column())
                        .toList(),
                "references count every whole-word use, calls or not");
    }

    @Test
    void aFileThatCannotBeReadIsSaidToBeUnreadable() {
        List<MapCardLinks.Answer> answers = ask(
                search(listed(false)),
                new MapCardLinks.Request(MapCardLinks.Kind.CALLERS, root.resolve("Gone.java"), 0, 0, null, null));
        assertEquals(1, answers.size());
        assertTrue(answers.getFirst().hits().isEmpty());
        assertEquals(
                tr("project.map.links.unreadable", "Gone.java"),
                answers.getFirst().note());
    }

    @Test
    void noNameAtTheColumnOrOnTheLineAsksNothing() {
        MapCardLinks.Request blank =
                new MapCardLinks.Request(MapCardLinks.Kind.CALLERS, root.resolve("A.java"), 0, 3, null, "   {   }\n");
        assertEquals(
                tr("project.map.links.noName"),
                ask(search(listed(false)), blank).getFirst().note());
        MapCardLinks.Request noName =
                new MapCardLinks.Request(MapCardLinks.Kind.CALLERS, root.resolve("A.java"), 0, -1, null, "class A\n");
        assertEquals(
                tr("project.map.links.noName"),
                ask(search(listed(false)), noName).getFirst().note());
        MapCardLinks.Request blankName =
                new MapCardLinks.Request(MapCardLinks.Kind.CALLERS, root.resolve("A.java"), 0, 2, " ", "x y\n");
        assertEquals(
                "y",
                ask(search(listed(false)), blankName).getFirst().name(),
                "a blank name: the word at the column instead");
    }

    @Test
    void aNameNotStandingAloneOnItsLineIsStillLookedForAndALineBeyondTheTextIsItsLast() throws Exception {
        project();
        MapCardLinks.Request request = new MapCardLinks.Request(
                MapCardLinks.Kind.CALLERS, root.resolve("src/B.java"), 99, -1, "add", "int adder;\nint xadd;");
        List<MapCardLinks.Answer> answers = ask(search(listed(false, "src/A.java", "src/B.java")), request);
        MapCardLinks.Answer answer = answers.getFirst();
        assertEquals(
                List.of("A.java:1", "A.java:1", "B.java:1"),
                answer.hits().stream()
                        .map(hit -> hit.file().getFileName() + ":" + hit.line())
                        .toList(),
                "callers: only where add( follows");
        assertEquals(tr("project.map.links.noServer"), answer.note());
    }

    @Test
    void noFileListOrABlankNameFindsNothingButSaysWhy() {
        ProjectMapLinkSearch noList = search(null);
        MapCardLinks.Answer answer = noList.scan(root, "add", "java", true, "note");
        assertTrue(answer.hits().isEmpty());
        assertEquals("note", answer.note());
        assertTrue(search(listed(false, "a.java"))
                .scan(root, " ", "java", false, "n")
                .hits()
                .isEmpty());
    }

    @Test
    void theTimeBudgetAndATruncatedListMakeTheAnswerPartial() throws Exception {
        project();
        AtomicLong now = new AtomicLong();
        ProjectMapLinkSearch slow = new ProjectMapLinkSearch(
                null,
                index(listed(false, "src/A.java", "src/B.java")),
                Runnable::run,
                Runnable::run,
                () -> now.getAndAdd(ProjectMapLinkSearch.BUDGET_MILLIS)); // each look at the clock: the budget gone
        MapCardLinks.Answer late = slow.scan(root, "add", "java", false, null);
        assertTrue(late.partial(), "stopped by the clock");
        assertTrue(late.hits().size() < 3, "it did not read every file: " + late.hits());

        MapCardLinks.Answer truncated =
                search(listed(true, "src/A.java", "src/B.java")).scan(root, "add", "java", false, null);
        assertTrue(truncated.partial(), "the list itself stopped short");
        assertEquals(3, truncated.hits().size());

        MapCardLinks.Answer whole = search(listed(false, "src/A.java", "src/B.java", "src/C.java", "notes.md"))
                .scan(root, "add", "java", false, null);
        assertFalse(whole.partial());
        assertEquals(3, whole.hits().size());
    }

    @Test
    void aFileOverAMegabyteIsNotReadForAMatch() throws Exception {
        Files.writeString(
                root.resolve("Big.java"), "add(1);\n" + "x".repeat((int) ProjectMapLinkSearch.MAX_FILE_BYTES));
        Files.writeString(root.resolve("Small.java"), "add(1);\n");
        MapCardLinks.Answer answer =
                search(listed(false, "Big.java", "Small.java")).scan(root, "add", "java", true, null);
        assertEquals(
                List.of("Small.java"),
                answer.hits().stream()
                        .map(hit -> hit.file().getFileName().toString())
                        .toList());
    }

    @Test
    void aClosedBackgroundLaneAnswersNothingAndThrowsNothing() {
        ProjectMapLinkSearch closed = new ProjectMapLinkSearch(
                null,
                index(listed(false)),
                task -> {
                    throw new RejectedExecutionException("closing");
                },
                Runnable::run,
                () -> 0L);
        List<MapCardLinks.Answer> answers = ask(
                closed, new MapCardLinks.Request(MapCardLinks.Kind.CALLERS, root.resolve("A.java"), 0, 0, null, null));
        assertTrue(answers.isEmpty(), "the window is closing: no answer");
    }

    @Test
    void manyMatchesStopAtTheCap() {
        List<MapCardLinks.Hit> hits = new ArrayList<>();
        String text = "add ".repeat(ProjectMapLinkSearch.MAX_HITS + 10);
        ProjectMapLinkSearch.matches(
                root.resolve("A.java"), text, ProjectMapLinkSearch.namePattern("add", false), hits);
        assertEquals(ProjectMapLinkSearch.MAX_HITS, hits.size());
        assertNull(hits.getFirst().name());
        assertEquals(4, hits.get(1).column());
    }

    // --- what the card menu offers (only what fits the file) ---------------------------------------------------

    @Test
    void withoutAServerOnlyWhatANameMatchAnswersIsOfferedAndOnlyForCode() {
        ProjectMapLinkSearch search = search(listed(false));
        Path java = root.resolve("src/A.java");
        for (MapCardLinks.Kind kind : MapCardLinks.Kind.values()) {
            assertEquals(ProjectMapLinkSearch.byNameKinds(kind), search.offers(java, kind), kind.toString());
            // A pom.xml has no declarations to match a name against: nothing a name match finds fits it.
            assertFalse(search.offers(root.resolve("pom.xml"), kind), "pom.xml " + kind);
        }
        assertFalse(search.documents(java), "Quick Documentation needs a server");
        assertTrue(search.editorOffers(java, "lsp.gotoDefinition"), "the editor has the index for Java");
        assertFalse(search.editorOffers(root.resolve("notes.txt"), "lsp.gotoDefinition"), "nothing for plain text");
        assertFalse(search.editorOffers(java, "lsp.callHierarchy"), "no server, no call hierarchy");
    }

    @Test
    void aServersCapabilitiesSayWhichQuestionsItAnswers() {
        org.eclipse.lsp4j.ServerCapabilities xml = new org.eclipse.lsp4j.ServerCapabilities();
        xml.setDefinitionProvider(true);
        xml.setReferencesProvider(true);
        xml.setHoverProvider(true);
        assertTrue(ProjectMapLinkSearch.provides(xml, MapCardLinks.Kind.DEFINITION));
        assertTrue(ProjectMapLinkSearch.provides(xml, MapCardLinks.Kind.REFERENCES));
        for (MapCardLinks.Kind kind : List.of(
                MapCardLinks.Kind.CALLERS,
                MapCardLinks.Kind.CALLEES,
                MapCardLinks.Kind.SUPERTYPES,
                MapCardLinks.Kind.SUBTYPES,
                MapCardLinks.Kind.IMPLEMENTATIONS,
                MapCardLinks.Kind.TYPE_DEFINITION,
                MapCardLinks.Kind.DECLARATION,
                MapCardLinks.Kind.SUPER_METHOD)) {
            assertFalse(ProjectMapLinkSearch.provides(xml, kind), kind.toString());
        }
        org.eclipse.lsp4j.ServerCapabilities java = new org.eclipse.lsp4j.ServerCapabilities();
        java.setCallHierarchyProvider(true);
        java.setTypeHierarchyProvider(org.eclipse.lsp4j.jsonrpc.messages.Either.forRight(
                new org.eclipse.lsp4j.TypeHierarchyRegistrationOptions()));
        java.setImplementationProvider(true);
        assertTrue(ProjectMapLinkSearch.provides(java, MapCardLinks.Kind.CALLEES));
        assertTrue(ProjectMapLinkSearch.provides(java, MapCardLinks.Kind.SUBTYPES), "options count as yes");
        assertTrue(ProjectMapLinkSearch.provides(java, MapCardLinks.Kind.IMPLEMENTATIONS));
        assertFalse(ProjectMapLinkSearch.provides(java, MapCardLinks.Kind.REFERENCES), "not advertised");
    }
}

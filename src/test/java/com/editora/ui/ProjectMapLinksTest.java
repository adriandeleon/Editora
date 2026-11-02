package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.editora.search.ProjectFileList;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static com.editora.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Linked reference cards (Project Map explore, position 5) without a window: places grouped by the declaration that
 * holds them, the edges' text, ends and accessible descriptions, and the name match that stands in for a language
 * server.
 */
class ProjectMapLinksTest {

    @TempDir
    Path root;

    @org.junit.jupiter.api.BeforeAll
    static void englishCatalog() {
        com.editora.i18n.Messages.init("en"); // run alone, nothing else has loaded the catalog
    }

    private static ProjectMapStructure.Node node(
            Path parent, Path file, String label, int line, int start, int end, ProjectMapStructure.Node... children) {
        return new ProjectMapStructure.Node(
                parent.resolve(ProjectMapStructure.SEGMENT_PREFIX + ProjectMapView.followName(label) + "~0"),
                new ProjectMapModel.Symbol(file, label, "method", line, start, end, null, children.length > 0),
                List.of(children));
    }

    @Test
    void placesAreGroupedByTheDeepestDeclarationThatHoldsThemInPathAndLineOrder() {
        Path service = root.resolve("src/Service.java");
        Path main = root.resolve("src/Main.java");
        ProjectMapStructure.Node run = node(service.resolve("…sService~0"), service, "run()", 4, 3, 9);
        ProjectMapStructure.Node stop = node(service.resolve("…sService~0"), service, "stop()", 11, 11, 14);
        ProjectMapStructure.Node type = node(service, service, "Service", 1, 0, 15, run, stop);
        Map<Path, List<ProjectMapStructure.Node>> outlines = Map.of(service, List.of(type));
        List<MapCardLinks.Hit> hits = List.of(
                new MapCardLinks.Hit(service, 12, 8, 1, null),
                new MapCardLinks.Hit(main, 2, 4, 1, null), // no outline given: grouped by file
                new MapCardLinks.Hit(service, 5, 8, 1, null),
                new MapCardLinks.Hit(service, 7, 8, 1, null),
                new MapCardLinks.Hit(service, 20, 0, 1, null)); // outside every declaration: the file
        MapCardLinks.Request asked =
                new MapCardLinks.Request(MapCardLinks.Kind.REFERENCES, root.resolve("src/Util.java"), 3, 4, "go", null);

        List<MapCardLinks.Group> groups =
                MapCardLinks.group(hits, MapCardLinks.Via.SERVER, asked, "go", outlines::get, root);

        assertEquals(
                List.of("Main.java", "run()", "stop()", "Service.java"),
                groups.stream().map(MapCardLinks.Group::label).toList(),
                "src/Main.java before src/Service.java; in a file, by line");
        assertEquals(
                List.of(1, 2, 1, 1),
                groups.stream().map(MapCardLinks.Group::count).toList());
        assertEquals(run.path(), groups.get(1).key(), "a declaration's card is keyed by its path");
        assertEquals(main, groups.get(0).key(), "a file without an outline groups by file");
        assertEquals(5, groups.get(1).line(), "the first place it holds");
        assertEquals(20, groups.get(3).line(), "past every declaration");
    }

    @Test
    void matchedByNameTheAskedDeclarationAndOtherDeclarationsOfTheNameAreLeftOut() {
        Path service = root.resolve("Service.java");
        Path other = root.resolve("Other.java");
        ProjectMapStructure.Node add = node(service, service, "add(int)", 2, 2, 4);
        ProjectMapStructure.Node total = node(service, service, "total()", 6, 6, 9);
        ProjectMapStructure.Node otherAdd = node(other, other, "add(String)", 3, 3, 5);
        Map<Path, List<ProjectMapStructure.Node>> outlines =
                Map.of(service, List.of(add, total), other, List.of(otherAdd));
        List<MapCardLinks.Hit> hits = List.of(
                new MapCardLinks.Hit(service, 2, 8, 1, null), // the declaration asked about
                new MapCardLinks.Hit(service, 7, 15, 1, null), // a call, in total()
                new MapCardLinks.Hit(other, 3, 9, 1, null)); // another declaration named add
        MapCardLinks.Request asked = new MapCardLinks.Request(MapCardLinks.Kind.CALLERS, service, 2, 8, "add", null);

        List<MapCardLinks.Group> byName =
                MapCardLinks.group(hits, MapCardLinks.Via.NAME, asked, "add", outlines::get, root);
        assertEquals(
                List.of("total()"),
                byName.stream().map(MapCardLinks.Group::label).toList());

        List<MapCardLinks.Group> byServer =
                MapCardLinks.group(hits, MapCardLinks.Via.SERVER, asked, "add", outlines::get, root);
        assertEquals(3, byServer.size(), "a server's answer is taken as given");
    }

    @Test
    void aCallersAnswerWithNoOutlineIsLabelledByTheCallersName() {
        Path script = root.resolve("tool.sh");
        List<MapCardLinks.Hit> hits = List.of(new MapCardLinks.Hit(script, 9, 0, 3, "deploy"));
        MapCardLinks.Request asked = new MapCardLinks.Request(MapCardLinks.Kind.CALLERS, script, 1, 0, "build", null);
        List<MapCardLinks.Group> groups =
                MapCardLinks.group(hits, MapCardLinks.Via.SERVER, asked, "build", file -> null, root);
        assertEquals("deploy", groups.getFirst().label());
        assertEquals(3, groups.getFirst().count(), "a caller's call sites");
    }

    @Test
    void enclosingIsTheDeepestDeclarationAndNullOutsideThemAll() {
        Path file = root.resolve("A.java");
        ProjectMapStructure.Node inner = node(file.resolve("…sA~0"), file, "go()", 3, 2, 5);
        ProjectMapStructure.Node outer = node(file, file, "A", 0, 0, 8, inner);
        assertEquals(inner, MapCardLinks.enclosing(List.of(outer), 4));
        assertEquals(outer, MapCardLinks.enclosing(List.of(outer), 7));
        assertNull(MapCardLinks.enclosing(List.of(outer), 12));
    }

    @Test
    void anEdgeRunsBetweenTheBoxesEdgesAlongTheLineBetweenTheirMiddles() {
        double[] left = {0, 0, 100, 50};
        double[] right = {300, 0, 100, 50};
        assertArrayEquals(new double[] {100, 25, 300, 25}, MapCardLinks.edgeEnds(left, right), 1e-9);
        double[] below = {0, 200, 100, 50};
        assertArrayEquals(new double[] {50, 50, 50, 200}, MapCardLinks.edgeEnds(left, below), 1e-9);
        double[] over = {50, 10, 100, 50};
        assertArrayEquals(new double[] {50, 25, 100, 35}, MapCardLinks.edgeEnds(left, over), 1e-9, "middles");
    }

    @Test
    void anEdgeSaysHowItWasFoundAndAScreenReaderHearsWhatItJoins() {
        assertEquals("calls", MapCardLinks.edgeText(MapCardLinks.Kind.CALLERS, MapCardLinks.Via.SERVER, 1));
        assertEquals("calls · 3", MapCardLinks.edgeText(MapCardLinks.Kind.CALLERS, MapCardLinks.Via.SERVER, 3));
        assertEquals("references · 2", MapCardLinks.edgeText(MapCardLinks.Kind.REFERENCES, MapCardLinks.Via.SERVER, 2));
        assertEquals("by name", MapCardLinks.edgeText(MapCardLinks.Kind.CALLERS, MapCardLinks.Via.NAME, 1));
        assertEquals(
                "total() calls App.java › add(int) (2 call sites)",
                MapCardLinks.edgeDescription(
                        MapCardLinks.Kind.CALLERS, MapCardLinks.Via.SERVER, "total()", "App.java › add(int)", 2));
        assertEquals(
                "run() names add, matched by name (1 place)",
                MapCardLinks.edgeDescription(MapCardLinks.Kind.REFERENCES, MapCardLinks.Via.NAME, "run()", "add", 1));
        assertEquals("add: 3 calls from 2 callers.", tr("project.map.links.foundCallersChip", 3, "add", 2, 0));
        assertEquals(
                "add: 9 references in 8 places; 2 more listed by the card’s “+2 more” button.",
                tr("project.map.links.foundReferencesChip", 9, "add", 8, 2));
    }

    @Test
    void theNameIsMatchedAsAWholeWordAndForCallersOnlyWhereItIsCalled() {
        Path file = root.resolve("A.java");
        List<MapCardLinks.Hit> hits = new ArrayList<>();
        String text = "int add(int a) {}\nadder(); add (2);\n// add\nx.add(3); $add(4);\n";
        ProjectMapLinkSearch.matches(file, text, ProjectMapLinkSearch.namePattern("add", false), hits);
        assertEquals(
                List.of("0:4", "1:9", "2:3", "3:2"),
                hits.stream().map(hit -> hit.line() + ":" + hit.column()).toList());
        hits.clear();
        ProjectMapLinkSearch.matches(file, text, ProjectMapLinkSearch.namePattern("add", true), hits);
        assertEquals(
                List.of("0:4", "1:9", "3:2"),
                hits.stream().map(hit -> hit.line() + ":" + hit.column()).toList(),
                "the comment does not call it");
        assertEquals(10, ProjectMapLinkSearch.wordAt("    void  add(int)", "add"));
        assertEquals(-1, ProjectMapLinkSearch.wordAt("adder()", "add"));
    }

    @Test
    void aNameMatchReadsTheProjectsFilesOfTheLanguageOnlyAndSaysItWasByName() throws Exception {
        Files.createDirectories(root.resolve("src"));
        Files.writeString(root.resolve("src/A.java"), "class A {\n  void go() { b.add(1); }\n}\n");
        Files.writeString(root.resolve("src/B.java"), "class B {\n  int add(int x) { return x; }\n}\n");
        Files.writeString(root.resolve("notes.md"), "call add(1) here\n");
        ProjectIndex files = new ProjectIndex() {
            @Override
            public ProjectMapSearchResult search(
                    Path at,
                    String query,
                    Kinds kinds,
                    int max,
                    Freshness freshness,
                    java.util.function.BooleanSupplier c) {
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
            public ProjectFileList.Snapshot snapshot(Path at) {
                return new ProjectFileList.Snapshot(
                        List.of("notes.md", "src/A.java", "src/B.java"), List.of("src"), false);
            }
        };
        ProjectMapLinkSearch search = new ProjectMapLinkSearch(null, files, Runnable::run, Runnable::run, () -> 0L);
        List<MapCardLinks.Answer> answers = new ArrayList<>();
        search.find(
                root,
                new MapCardLinks.Request(MapCardLinks.Kind.CALLERS, root.resolve("src/B.java"), 1, -1, "add", null),
                answers::add);

        assertEquals(1, answers.size());
        MapCardLinks.Answer answer = answers.getFirst();
        assertEquals(MapCardLinks.Via.NAME, answer.via());
        assertEquals(tr("project.map.links.noServer"), answer.note(), "it says no server answered");
        assertEquals(
                List.of("src/A.java:1", "src/B.java:1"),
                answer.hits().stream()
                        .map(hit -> root.relativize(hit.file()).toString().replace('\\', '/') + ":" + hit.line())
                        .toList(),
                "Java files only; the declaration itself is left out when grouped");
        assertFalse(answer.partial());
        assertTrue(answer.hits().stream().noneMatch(hit -> hit.file().toString().endsWith(".md")));
    }

    // --- Map review 4: links ------------------------------------------------------------------------------------

    @Test
    void aGoMethodListedUnderItsTypeIsTheDeclarationThatHoldsItsLinesThoughTheTypesLinesDoNot() {
        Path file = root.resolve("fake_runtime.go");
        // As the Go outline has it: a type whose lines are its struct's, its methods under it, further down.
        ProjectMapStructure.Node syncPod =
                node(file.resolve("…sFakeRuntime~0"), file, "(*FakeRuntime).SyncPod(…)", 261, 261, 270);
        ProjectMapStructure.Node killPod =
                node(file.resolve("…sFakeRuntime~0"), file, "(*FakeRuntime).KillPod(…)", 280, 280, 290);
        ProjectMapStructure.Node type = node(file, file, "FakeRuntime", 46, 46, 81, syncPod, killPod);
        assertEquals(syncPod, MapCardLinks.enclosing(List.of(type), 265), "review 4, C1: was the file");
        assertEquals(type, MapCardLinks.enclosing(List.of(type), 50));
        assertNull(MapCardLinks.enclosing(List.of(type), 100));

        // By name, another type's SyncPod is another declaration of the name: not a caller.
        Path kubelet = root.resolve("kubelet.go");
        MapCardLinks.Request asked =
                new MapCardLinks.Request(MapCardLinks.Kind.CALLERS, kubelet, 10, 5, "SyncPod", null);
        List<MapCardLinks.Group> groups = MapCardLinks.group(
                List.of(new MapCardLinks.Hit(file, 261, 22, 1, null), new MapCardLinks.Hit(file, 285, 4, 1, null)),
                MapCardLinks.Via.NAME,
                asked,
                "SyncPod",
                path -> path.equals(file) ? List.of(type) : null,
                root);
        assertEquals(
                List.of("(*FakeRuntime).KillPod(…)"),
                groups.stream().map(MapCardLinks.Group::label).toList());
    }

    @Test
    void groupsOpenCodeNearTheAskedFileFirstAndTestsLast() {
        Path asked = root.resolve("core/src/main/java/a/Bean.java");
        List<MapCardLinks.Group> groups = List.of(
                group(root.resolve("other/src/main/java/z/Far.java")),
                group(root.resolve("core/src/test/java/a/BeanTest.java")),
                group(root.resolve("core/src/main/java/a/Near.java")),
                group(root.resolve("core/src/main/java/b/Sibling.java")));
        assertEquals(
                List.of("Near.java", "Sibling.java", "Far.java", "BeanTest.java"),
                MapCardLinks.linksRank(groups, asked, root).stream()
                        .map(MapCardLinks.Group::label)
                        .toList());
        assertEquals(
                "BeanTest.java",
                MapCardLinks.linksRank(groups, root.resolve("core/src/test/java/a/Other.java"), root)
                        .getFirst()
                        .label(),
                "asked from a test, tests are not put last");

        assertTrue(MapCardLinks.linksLooksLikeTest("core/src/test/java/a/X.java"));
        assertTrue(MapCardLinks.linksLooksLikeTest("pkg/kubelet/kubelet_test.go"));
        assertTrue(MapCardLinks.linksLooksLikeTest("tests/test_api.py"));
        assertTrue(MapCardLinks.linksLooksLikeTest("web/app.spec.ts"));
        assertTrue(MapCardLinks.linksLooksLikeTest("a/FooTest.java"));
        assertFalse(MapCardLinks.linksLooksLikeTest("a/Test.java"));
        assertFalse(MapCardLinks.linksLooksLikeTest("a/Latest.java"));
        assertFalse(MapCardLinks.linksLooksLikeTest("pkg/kubelet/kubelet.go"));
    }

    @Test
    void aNameMatchReadsTheAskedFilesFolderAndModuleFirstAndTestsLast() {
        List<String> listed = List.of(
                "web/src/test/java/w/WebTest.java",
                "web/src/main/java/w/Web.java",
                "core/src/test/java/a/BeanTest.java",
                "core/src/main/java/b/Sibling.java",
                "core/src/main/java/a/Near.java",
                "README.md");
        assertEquals(
                List.of(
                        "core/src/main/java/a/Near.java",
                        "core/src/main/java/b/Sibling.java",
                        "web/src/main/java/w/Web.java",
                        "core/src/test/java/a/BeanTest.java",
                        "web/src/test/java/w/WebTest.java"),
                ProjectMapLinkSearch.linksScanOrder(
                        listed, "java", root, root.resolve("core/src/main/java/a/Bean.java")),
                "review 4, C9: the cap samples the code around the declaration");
        assertEquals(
                List.of(
                        "web/src/test/java/w/WebTest.java",
                        "web/src/main/java/w/Web.java",
                        "core/src/test/java/a/BeanTest.java",
                        "core/src/main/java/b/Sibling.java",
                        "core/src/main/java/a/Near.java"),
                ProjectMapLinkSearch.linksScanOrder(listed, "java", root, null),
                "with no asked file: list order");
    }

    private MapCardLinks.Group group(Path file) {
        return new MapCardLinks.Group(file, null, file.getFileName().toString(), 0, 1);
    }
}

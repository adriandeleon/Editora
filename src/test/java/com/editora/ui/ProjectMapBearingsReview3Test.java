package com.editora.ui;

import java.io.InputStream;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Project Map review 3, the bearings group: the pure parts of type-ahead, following code and persistence. */
class ProjectMapBearingsReview3Test {

    // B14

    @Test
    void typeAheadStepsToTheNextNameWithTheInitialAndKeepsARowALongerPrefixStillMatches() {
        List<String> names = List.of("api", "build", "cmd", "cluster", "kubelet.go", "kube-proxy", "Kubefile");
        assertEquals(4, ProjectMapView.typeAheadTarget(names, 0, "k", true), "a new initial steps on");
        assertEquals(5, ProjectMapView.typeAheadTarget(names, 4, "k", true), "the same initial again: the next one");
        assertEquals(6, ProjectMapView.typeAheadTarget(names, 5, "K", true), "case is ignored");
        assertEquals(4, ProjectMapView.typeAheadTarget(names, 6, "k", true), "and it wraps");
        assertEquals(4, ProjectMapView.typeAheadTarget(names, 4, "kubel", false), "a longer prefix keeps its row");
        assertEquals(5, ProjectMapView.typeAheadTarget(names, 4, "kube-", false));
        assertEquals(3, ProjectMapView.typeAheadTarget(names, -1, "cl", false), "nothing selected: from the top");
        assertEquals(-1, ProjectMapView.typeAheadTarget(names, 0, "zz", true));
        assertEquals(-1, ProjectMapView.typeAheadTarget(List.of(), 0, "a", true));
    }

    // C12

    @Test
    void theCaretLandsOnTheNameADeclarationDeclares() {
        assertEquals("Start", ProjectMapView.followName("func (s *Server) Start(ctx context.Context)"));
        assertEquals("refresh", ProjectMapView.followName("refresh()"));
        assertEquals("NewScheduler", ProjectMapView.followName("NewScheduler(client, opts)"));
        assertEquals("replicas", ProjectMapView.followName("replicas: int32"));
        assertEquals("Pod", ProjectMapView.followName("type Pod"));
        assertEquals("Main", ProjectMapView.followName("class Main"));
        assertEquals("", ProjectMapView.followName(null));

        String line = "    public void refresh() throws BeansException {";
        assertEquals(16, FileWorkflowCoordinator.followColumn(line, -1, "refresh"), "on the name");
        assertEquals(4, FileWorkflowCoordinator.followColumn(line, -1, "missing"), "else the first non-blank");
        assertEquals(4, FileWorkflowCoordinator.followColumn(line, -1, null));
        assertEquals(7, FileWorkflowCoordinator.followColumn(line, 7, "refresh"), "a column given wins");
        assertEquals(line.length(), FileWorkflowCoordinator.followColumn(line, 500, null), "clamped to the line");
        assertEquals(
                9,
                FileWorkflowCoordinator.followColumn("Started  Start(x)", -1, "Start"),
                "a whole word, not the start of a longer one");
    }

    @Test
    void aMenuLabelForEachCodeCommand() {
        assertEquals(
                List.of(
                        "lsp.gotoDefinition",
                        "lsp.findReferences",
                        "lsp.gotoImplementation",
                        "lsp.typeHierarchy",
                        "lsp.callHierarchy"),
                ProjectMapPreview.FOLLOW_COMMANDS);
        for (String command : ProjectMapPreview.FOLLOW_COMMANDS) {
            String label = ProjectMapPreview.followLabel(command);
            assertTrue(!label.isBlank() && !label.startsWith("project.map."), command + " has a label: " + label);
        }
    }

    // E10

    @Test
    void aFoldersGitHistoryIsALogSearchForItsPath() {
        assertEquals("path:pkg/kubelet", GitWindowCoordinator.folderHistoryQuery("pkg/kubelet"));
        assertEquals("path:\"docs/user guide\"", GitWindowCoordinator.folderHistoryQuery("docs/user guide"));
        assertEquals("", GitWindowCoordinator.folderHistoryQuery(""), "the repository root: its whole log");
    }

    // B6

    @Test
    void rememberedPathsAreRelativeToTheFolderAndCannotLeaveIt() {
        Path base = Path.of("/work/project").toAbsolutePath();
        assertEquals("src/main/App.java", ProjectMapView.bearingsRelative(base, base.resolve("src/main/App.java")));
        assertEquals("", ProjectMapView.bearingsRelative(base, base));
        assertNull(ProjectMapView.bearingsRelative(base, base.resolveSibling("other/x")));
        assertNull(ProjectMapView.bearingsRelative(base, null));

        assertEquals(base.resolve("src/main"), ProjectMapView.bearingsResolve(base, "src/main"));
        assertEquals(base, ProjectMapView.bearingsResolve(base, ""));
        assertNull(ProjectMapView.bearingsResolve(base, "../outside"), "a stored path cannot reach out of the folder");
        assertNull(ProjectMapView.bearingsResolve(base, null));
    }

    // E9

    @Test
    void everyKeymapOpensTheMapAndRevealsTheActiveFile() throws Exception {
        ObjectMapper json = new ObjectMapper();
        for (String name : List.of(
                "emacs",
                "cua",
                "cua.mac",
                "sublime",
                "sublime.mac",
                "vscode",
                "vscode.mac",
                "intellij",
                "intellij.mac")) {
            Map<String, String> keymap;
            try (InputStream in = getClass().getResourceAsStream("/com/editora/keymaps/" + name + ".json")) {
                assertNotNull(in, name);
                keymap = json.readValue(in, new TypeReference<Map<String, String>>() {});
            }
            assertTrue(keymap.containsValue("project.toggleMapView"), name + " opens the Map");
            assertTrue(keymap.containsValue("projectMap.revealActiveFile"), name + " reveals the active file");
        }
        Map<String, String> emacs;
        try (InputStream in = getClass().getResourceAsStream("/com/editora/keymaps/emacs.json")) {
            emacs = json.readValue(in, new TypeReference<Map<String, String>>() {});
        }
        assertEquals("project.toggleMapView", emacs.get("C-c o m"));
        assertEquals("projectMap.revealActiveFile", emacs.get("C-c o r"));
    }
}

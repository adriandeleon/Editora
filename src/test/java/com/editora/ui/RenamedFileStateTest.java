package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.editora.config.PathKeys;
import com.editora.config.WorkspaceState;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RenamedFileStateTest {

    private static Map<String, String> map(String... pairs) {
        Map<String, String> out = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            out.put(pairs[i], pairs[i + 1]);
        }
        return out;
    }

    @Test
    void aRenamedFileTakesItsEntryAlong() {
        Map<String, String> store = map("/p/a.txt", "A", "/p/b.txt", "B");
        assertTrue(RenamedFileState.rekey(store, "/p/a.txt", "/p/c.txt", "/"));
        assertEquals(map("/p/b.txt", "B", "/p/c.txt", "A"), store);
    }

    @Test
    void aRenamedFolderTakesEveryFileBelowItAlong() {
        Map<String, String> store =
                map("/p/src/a.txt", "A", "/p/src/deep/b.txt", "B", "/p/src2/c.txt", "C", "/p/src", "D");
        assertTrue(RenamedFileState.rekey(store, "/p/src", "/q/lib", "/"));
        assertEquals(
                map("/p/src2/c.txt", "C", "/q/lib/a.txt", "A", "/q/lib/deep/b.txt", "B", "/q/lib", "D"),
                store,
                "a sibling that merely starts with the same characters is another folder");
    }

    @Test
    void aLeftoverAtTheNewPathIsReplaced() {
        Map<String, String> store = map("/p/a.txt", "A", "/p/c.txt", "stale");
        RenamedFileState.rekey(store, "/p/a.txt", "/p/c.txt", "/");
        assertEquals(map("/p/c.txt", "A"), store);
    }

    @Test
    void nothingStoredMeansNothingMoved() {
        Map<String, String> store = map("/p/b.txt", "B");
        assertFalse(RenamedFileState.rekey(store, "/p/a.txt", "/p/c.txt", "/"));
        assertFalse(RenamedFileState.rekey(store, "/p/b.txt", "/p/b.txt", "/"));
        assertFalse(RenamedFileState.rekey((Map<String, String>) null, "/p/a.txt", "/p/c.txt", "/"));
        assertEquals(map("/p/b.txt", "B"), store);
        assertNull(RenamedFileState.renamed("/p/ab", "/p/a", "/p/z", "/"));
        assertNull(RenamedFileState.renamed("/p/a/", "/p/a", "/p/z", "/"), "a bare separator names nothing below");
    }

    @Test
    void aListOfPathsKeepsItsOrder() {
        List<String> pinned = new ArrayList<>(List.of("/p/x.txt", "/p/src/a.txt", "/p/y.txt"));
        assertTrue(RenamedFileState.rekey(pinned, "/p/src", "/p/lib", "/"));
        assertEquals(List.of("/p/x.txt", "/p/lib/a.txt", "/p/y.txt"), pinned);
        assertFalse(RenamedFileState.rekey(pinned, "/p/none", "/p/other", "/"));
    }

    @Test
    void windowsSeparatorsWorkTheSameWay() {
        Map<String, String> store = map("C:\\p\\src\\a.txt", "A", "C:\\p\\srcs\\b.txt", "B");
        RenamedFileState.rekey(store, "C:\\p\\src", "C:\\p\\lib", "\\");
        assertEquals(map("C:\\p\\srcs\\b.txt", "B", "C:\\p\\lib\\a.txt", "A"), store);
    }

    @Test
    void everySessionMapFollowsARenamedFolder(@TempDir Path dir) {
        Path old = dir.resolve("src");
        Path target = dir.resolve("lib");
        String a = old.resolve("a.md").toString();
        WorkspaceState ws = new WorkspaceState();
        ws.getFoldedRegions().put(a, List.of(1));
        ws.getManualFoldRegions().put(a, List.of(2, 4));
        ws.getMarkdownViewModes().put(a, "SPLIT");
        ws.getMarkwhenViews().put(a, "CALENDAR");
        ws.getSpellLanguages().put(a, "en_GB");
        ws.getProgramArgs().put(a, "--x");
        ws.getReadOnlyFiles().add(a);

        RenamedFileState.rekeyWorkspace(ws, old, target);

        String moved = target.resolve("a.md").toString();
        assertEquals(Map.of(moved, List.of(1)), ws.getFoldedRegions());
        assertEquals(Map.of(moved, List.of(2, 4)), ws.getManualFoldRegions());
        assertEquals(Map.of(moved, "SPLIT"), ws.getMarkdownViewModes());
        assertEquals(Map.of(moved, "CALENDAR"), ws.getMarkwhenViews());
        assertEquals(Map.of(moved, "en_GB"), ws.getSpellLanguages());
        assertEquals(Map.of(moved, "--x"), ws.getProgramArgs());
        assertEquals(List.of(moved), ws.getReadOnlyFiles());
    }

    @Test
    void saveAsCopiesTheViewChoicesAndLeavesTheOriginalItsOwn(@TempDir Path dir) {
        Path old = dir.resolve("a.md");
        Path target = dir.resolve("b.md");
        WorkspaceState ws = new WorkspaceState();
        ws.getMarkdownViewModes().put(old.toString(), "PREVIEW");
        ws.getSpellLanguages().put(old.toString(), "es");
        ws.getReadOnlyFiles().add(old.toString());

        RenamedFileState.copyWorkspace(ws, old, target);

        assertEquals("PREVIEW", ws.getMarkdownViewModes().get(old.toString()));
        assertEquals("PREVIEW", ws.getMarkdownViewModes().get(target.toString()));
        assertEquals("es", ws.getSpellLanguages().get(target.toString()));
        assertEquals(List.of(old.toString()), ws.getReadOnlyFiles(), "the pin belongs to the file that was pinned");
    }

    @Test
    void theKeyOfAPathThatMovedAwayIsTheOneItHadBehindASymlink(@TempDir Path dir) throws Exception {
        Path real = Files.createDirectory(dir.resolve("real"));
        Path link = dir.resolve("link");
        try {
            Files.createSymbolicLink(link, real);
        } catch (UnsupportedOperationException | java.io.IOException e) {
            Assumptions.abort("symbolic links are not available here");
        }
        Path viaLink = link.resolve("notes.txt");
        Files.writeString(viaLink, "x");
        PathKeys.invalidateCanonicalCache();
        String keyWhileThere = PathKeys.canonicalKey(viaLink);
        assertEquals(keyWhileThere, RenamedFileState.formerCanonicalKey(viaLink), "still on disk: its canonical key");

        Files.move(viaLink, link.resolve("renamed.txt"));
        PathKeys.invalidateCanonicalCache();
        assertEquals(keyWhileThere, RenamedFileState.formerCanonicalKey(viaLink), "gone: resolved through its folder");
        assertNull(RenamedFileState.formerCanonicalKey(null));
    }
}

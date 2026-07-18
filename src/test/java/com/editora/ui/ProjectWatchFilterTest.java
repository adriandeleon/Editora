package com.editora.ui;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression for #465: the project-tree filesystem watcher must ignore Editora's own throwaway typst render
 * input (created + deleted every render), so a render doesn't spuriously rebuild the tree — while any real
 * file change still triggers a refresh.
 */
class ProjectWatchFilterTest {

    @Test
    void aBatchOfOnlyEditoraTempFilesDoesNotWarrantARefresh() {
        // The typst render writes then deletes `.editora-typst-<uuid>.typ` beside the file.
        assertFalse(ProjectPanel.watchEventsWarrantRefresh(
                List.of(".editora-typst-cf099c21.typ", ".editora-typst-cf099c21.typ"), false));
    }

    @Test
    void anyRealFileChangeStillWarrantsARefresh() {
        assertTrue(ProjectPanel.watchEventsWarrantRefresh(List.of("Main.java"), false));
        // A mixed batch (temp + a real file) refreshes.
        assertTrue(ProjectPanel.watchEventsWarrantRefresh(List.of(".editora-typst-x.typ", "notes.md"), false));
    }

    @Test
    void overflowOrAnEmptyBatchForcesARefresh() {
        assertTrue(ProjectPanel.watchEventsWarrantRefresh(List.of(".editora-typst-x.typ"), true), "OVERFLOW");
        assertTrue(ProjectPanel.watchEventsWarrantRefresh(List.of(), false), "empty batch — can't tell");
    }

    @Test
    void isEditoraTempNameRecognizesTheTypstInput() {
        assertTrue(ProjectPanel.isEditoraTempName(".editora-typst-abc-123.typ"));
        assertFalse(ProjectPanel.isEditoraTempName("report.typ"));
        assertFalse(ProjectPanel.isEditoraTempName(".gitignore"));
        assertFalse(ProjectPanel.isEditoraTempName(null));
    }
}

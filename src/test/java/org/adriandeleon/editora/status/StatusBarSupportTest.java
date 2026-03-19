package org.adriandeleon.editora.status;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class StatusBarSupportTest {

    @Test
    void buildsWorkspaceRelativeBreadcrumbSegments() {
        Path workspaceRoot = Path.of("/Users/adriandeleon/git/adl/editora");
        Path filePath = workspaceRoot.resolve("src/main/java/org/adriandeleon/editora/EditorController.java");
        List<StatusBarSupport.BreadcrumbEntry> entries = StatusBarSupport.buildBreadcrumbEntries(workspaceRoot, filePath, "EditorController.java");

        assertEquals(
                List.of("editora", "src", "main", "java", "org", "adriandeleon", "editora", "EditorController.java"),
                entries.stream().map(StatusBarSupport.BreadcrumbEntry::label).toList()
        );
        assertEquals(workspaceRoot, entries.getFirst().path());
        assertEquals(filePath, entries.getLast().path());
    }

    @Test
    void buildsAbsoluteBreadcrumbSegmentsForExternalFiles() {
        Path workspaceRoot = Path.of("/Users/adriandeleon/git/adl/editora");
        Path filePath = Path.of("/tmp/notes/todo.txt");
        List<StatusBarSupport.BreadcrumbEntry> entries = StatusBarSupport.buildBreadcrumbEntries(workspaceRoot, filePath, "todo.txt");

        assertEquals(
                List.of("/", "tmp", "notes", "todo.txt"),
                entries.stream().map(StatusBarSupport.BreadcrumbEntry::label).toList()
        );
        assertEquals(Path.of("/"), entries.getFirst().path());
        assertEquals(filePath, entries.getLast().path());
    }

    @Test
    void fallsBackToDisplayNameForUnsavedDocuments() {
        List<StatusBarSupport.BreadcrumbEntry> entries = StatusBarSupport.buildBreadcrumbEntries(null, null, "*Untitled 3");

        assertEquals(
                List.of("*Untitled 3"),
                entries.stream().map(StatusBarSupport.BreadcrumbEntry::label).toList()
        );
        assertEquals(null, entries.getFirst().path());
    }

    @Test
    void formatsDocumentStatusWithUtf8Size() {
        long utf8Bytes = StatusBarSupport.utf8Size("hello é");

        assertEquals(8, utf8Bytes);
        assertEquals(
                "README.md · 2 lines · 7 chars · 8 B",
                StatusBarSupport.formatDocumentStatus("README.md", 2, 7, utf8Bytes)
        );
    }

    @Test
    void formatsLargerSizesUsingReadableUnits() {
        assertEquals("1.5 KB", StatusBarSupport.formatFileSize(1536));
        assertEquals("3.0 MB", StatusBarSupport.formatFileSize(3L * 1024 * 1024));
    }
}


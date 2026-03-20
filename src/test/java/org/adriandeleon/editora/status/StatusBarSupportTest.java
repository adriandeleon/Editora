package org.adriandeleon.editora.status;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class StatusBarSupportTest {

    @TempDir
    Path tempDir;

    @Test
    void buildsWorkspaceRelativeBreadcrumbSegments() throws Exception {
        Path workspaceRoot = Files.createDirectories(tempDir.resolve("workspace/editora"));
        Path filePath = workspaceRoot.resolve("src/main/java/org/adriandeleon/editora/EditorController.java");
        List<StatusBarSupport.BreadcrumbEntry> entries = StatusBarSupport.buildBreadcrumbEntries(workspaceRoot, filePath, "EditorController.java");

        assertEquals(
                List.of(workspaceRoot.getFileName().toString(), "src", "main", "java", "org", "adriandeleon", "editora", "EditorController.java"),
                entries.stream().map(StatusBarSupport.BreadcrumbEntry::label).toList()
        );
        assertEquals(workspaceRoot, entries.getFirst().path());
        assertEquals(filePath, entries.getLast().path());
    }

    @Test
    void buildsAbsoluteBreadcrumbSegmentsForExternalFiles() throws Exception {
        Path workspaceRoot = Files.createDirectories(tempDir.resolve("workspace/editora"));
        Path filePath = tempDir.resolve("external/notes/todo.txt").toAbsolutePath().normalize();

        List<String> expectedLabels = new ArrayList<>();
        expectedLabels.add(filePath.getRoot().toString());
        for (Path segment : filePath) {
            expectedLabels.add(segment.toString());
        }

        List<StatusBarSupport.BreadcrumbEntry> entries = StatusBarSupport.buildBreadcrumbEntries(workspaceRoot, filePath, "todo.txt");

        assertEquals(
                expectedLabels,
                entries.stream().map(StatusBarSupport.BreadcrumbEntry::label).toList()
        );
        assertEquals(filePath.getRoot().toAbsolutePath().normalize(), entries.getFirst().path());
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


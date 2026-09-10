package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;

import com.editora.editor.EditorBuffer;
import com.editora.search.SearchQuery;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("fx")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SearchReplaceSafetyFxTest {

    private static final SearchQuery QUERY = new SearchQuery("old", true, false, false);

    @BeforeAll
    void boot() throws Exception {
        FxTestSupport.bootToolkit();
    }

    @Test
    void replacingAnOpenBufferRemainsDirtyAndUndoable() throws Exception {
        EditorBuffer buffer = FxTestSupport.callOnFx(() -> {
            EditorBuffer b = new EditorBuffer();
            b.setContent("old text");
            b.getArea().getUndoManager().forgetHistory(); // loaded baseline, as seen by the editing workflow
            return b;
        });

        SearchCoordinator.ClosedReplace result =
                FxTestSupport.callOnFx(() -> SearchCoordinator.replaceOpenBuffer(buffer, QUERY, "new", false));

        assertTrue(result.changed());
        assertEquals("new text", FxTestSupport.callOnFx(buffer::getContent));
        assertTrue(FxTestSupport.callOnFx(buffer::isDirty));
        FxTestSupport.runOnFx(() -> buffer.getArea().undo());
        assertEquals("old text", FxTestSupport.callOnFx(buffer::getContent));
    }

    @Test
    void partialAndLoadingBuffersAreRejected() throws Exception {
        EditorBuffer buffer = FxTestSupport.callOnFx(() -> {
            EditorBuffer b = new EditorBuffer();
            b.setContent("old text");
            b.setTruncatedLoad(true);
            return b;
        });
        assertTrue(FxTestSupport.callOnFx(() -> SearchCoordinator.replaceOpenBuffer(buffer, QUERY, "new", false))
                .failed());
        FxTestSupport.runOnFx(() -> buffer.setTruncatedLoad(false));
        assertTrue(FxTestSupport.callOnFx(() -> SearchCoordinator.replaceOpenBuffer(buffer, QUERY, "new", true))
                .failed());
        assertEquals("old text", FxTestSupport.callOnFx(buffer::getContent));
    }

    @Test
    void closedFileReplacementRefusesAConcurrentChange(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("file.txt");
        Files.writeString(file, "old text");

        SearchCoordinator.ClosedReplace result = SearchCoordinator.replaceClosedFile(file, QUERY, "new", ignored -> {
            try {
                Files.writeString(file, "external edit");
            } catch (java.io.IOException e) {
                throw new RuntimeException(e);
            }
        });

        assertTrue(result.failed());
        assertFalse(result.changed());
        assertEquals("external edit", Files.readString(file));
    }

    @Test
    void closedFileReplacementRefusesASupersededCommit(@TempDir Path dir) throws Exception {
        Path file = Files.writeString(dir.resolve("file.txt"), "old text");

        SearchCoordinator.ClosedReplace result =
                SearchCoordinator.replaceClosedFile(file, QUERY, "new", ignored -> {}, () -> false);

        assertTrue(result.failed());
        assertFalse(result.changed());
        assertEquals("old text", Files.readString(file));
    }

    @Test
    void closedFileReplacementRechecksAfterTheCommitGuard(@TempDir Path dir) throws Exception {
        Path file = Files.writeString(dir.resolve("file.txt"), "old text");

        SearchCoordinator.ClosedReplace result =
                SearchCoordinator.replaceClosedFile(file, QUERY, "new", ignored -> {}, () -> {
                    try {
                        Files.writeString(file, "external edit");
                    } catch (java.io.IOException e) {
                        throw new RuntimeException(e);
                    }
                    return true;
                });

        assertTrue(result.failed());
        assertFalse(result.changed());
        assertEquals("external edit", Files.readString(file));
    }
}

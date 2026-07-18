package com.editora.ui;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javafx.scene.control.TextField;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;

import com.editora.config.FileIdentity;
import com.editora.config.NoteScope;
import com.editora.config.NoteStatus;
import com.editora.config.PersonalNote;
import com.editora.config.TextAnchor;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Headless-FX coverage of {@link NotesPanel#refresh}: grouping the active bucket (file → notes) into the
 * tree, skipping empty files, and the body/file filter. Uses a no-op {@link NotesPanel.Actions}.
 */
@Tag("fx")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class NotesPanelFxTest {

    private static final NotesPanel.Actions NOOP = new NotesPanel.Actions() {
        @Override
        public void openAndJump(String projectKey, String fileKey, PersonalNote note) {}

        @Override
        public void editBody(String projectKey, String fileKey, PersonalNote note) {}

        @Override
        public void setStatus(String projectKey, String fileKey, PersonalNote note, NoteStatus status) {}

        @Override
        public void delete(String projectKey, String fileKey, PersonalNote note) {}

        @Override
        public void deleteAll(String projectKey, String fileKey) {}
    };

    private final Map<String, List<PersonalNote>> source = new LinkedHashMap<>();

    @BeforeAll
    void setUp() throws Exception {
        FxTestSupport.bootToolkit();
    }

    private static PersonalNote note(String body) {
        FileIdentity fid = new FileIdentity("/x", "/x", 1, 1, "");
        TextAnchor anc = new TextAnchor(0, 0, 0, 1, "sel", "pre", "suf");
        return PersonalNote.create(fid, NoteScope.LINE, anc, body, List.of());
    }

    private NotesPanel panel() throws Exception {
        // The in-memory bucket is the General (no-project) scope; currentKey "" makes it the current group.
        return FxTestSupport.callOnFx(() ->
                new NotesPanel(() -> new NotesPanel.Scope(Map.of("", source), "", k -> "General"), NOOP));
    }

    @SuppressWarnings("unchecked")
    private static TreeView<Object> tree(NotesPanel p) {
        return (TreeView<Object>) FxTestSupport.<TreeView<?>>field(p, "tree");
    }

    @Test
    void groupsFilesAndNotesSkippingEmptyFiles() throws Exception {
        source.clear();
        source.put("/proj/Alpha.java", List.of(note("todo: fix"), note("question here")));
        source.put("/proj/Beta.java", List.of(note("single")));
        source.put("/proj/Empty.java", List.of());

        NotesPanel p = panel();
        FxTestSupport.runOnFx(p::refresh);

        TreeItem<Object> root = FxTestSupport.callOnFx(() -> tree(p).getRoot());
        assertEquals(1, root.getChildren().size(), "one project group (General)");
        TreeItem<Object> general = root.getChildren().get(0);
        assertEquals(2, general.getChildren().size(), "two non-empty file groups under General");
        int totalNotes = FxTestSupport.callOnFx(() -> general.getChildren().stream()
                .mapToInt(f -> f.getChildren().size())
                .sum());
        assertEquals(3, totalNotes, "all notes rendered under their file");
    }

    @Test
    void filterMatchesNoteBody() throws Exception {
        source.clear();
        source.put("/proj/Alpha.java", List.of(note("needle in here")));
        source.put("/proj/Beta.java", List.of(note("unrelated")));

        NotesPanel p = panel();
        TextField filter = FxTestSupport.field(p, "filterField");
        FxTestSupport.runOnFx(() -> {
            filter.setText("needle");
            p.refresh();
        });
        TreeItem<Object> root = FxTestSupport.callOnFx(() -> tree(p).getRoot());
        assertEquals(1, root.getChildren().size(), "one project group");
        assertEquals(1, root.getChildren().get(0).getChildren().size(), "only the note whose body matches survives");
    }
}

package com.editora.ui;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import javafx.scene.layout.BorderPane;

import com.editora.config.HistoryRevision;
import com.editora.config.Settings;
import com.editora.diff.DiffEngine;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

@Tag("fx")
class FileHistoryMissingContentFxTest {

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
    }

    @Test
    void missingBlobIsUnavailableAndCannotBeAppliedAsAnEmptyRevision() throws Exception {
        FxTestSupport.runOnFx(() -> {
            FileHistoryPanel panel = new FileHistoryPanel(new FileHistoryPanel.Actions() {
                @Override
                public void refresh() {}

                @Override
                public void restore(HistoryRevision revision) {}

                @Override
                public void restoreToDisk(HistoryRevision revision) {}

                @Override
                public void editLabel(HistoryRevision revision) {}
            });
            panel.setDiffSupport(new FileHistoryPanel.DiffSupport() {
                @Override
                public void fetchContent(
                        HistoryRevision revision, java.util.function.Consumer<Optional<String>> onText) {
                    onText.accept(Optional.empty());
                }

                @Override
                public void computeDiff(
                        String left,
                        String right,
                        DiffEngine.DiffOptions opts,
                        java.util.function.Consumer<com.editora.diff.DiffModels.DiffModel> onResult) {
                    fail("missing content must not be diffed as an empty document");
                }

                @Override
                public String currentText(Path target) {
                    return "current";
                }

                @Override
                public void revert(HistoryRevision revision) {}

                @Override
                public void applyToLocal(Path target, String newText) {}

                @Override
                public void undoLocal(Path target) {}

                @Override
                public void saveLocal(Path target) {}

                @Override
                public Settings settings() {
                    return new Settings();
                }
            });
            Path file = Path.of("history.txt");
            HistoryRevision revision = new HistoryRevision(file.toString(), 1, 1, "missing", "SAVE");
            panel.setRevisions(List.of(revision), file.getFileName().toString(), file);
            panel.selectRevision(revision);

            BorderPane right = FxTestSupport.field(panel, "rightPane");
            assertTrue(right.getTop().isDisabled());
        });
    }
}

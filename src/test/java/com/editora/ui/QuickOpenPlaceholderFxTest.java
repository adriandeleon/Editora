package com.editora.ui;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.TextField;
import javafx.scene.layout.StackPane;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static com.editora.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A picker never opens as a blank card: with nothing to choose from it says so, in its own words where it has
 * them, and with a query that matches nothing it says that instead. And every list choice is a picker: no
 * system choice dialog is left.
 */
@Tag("fx")
class QuickOpenPlaceholderFxTest {

    @BeforeAll
    static void toolkit() throws Exception {
        FxTestSupport.bootToolkit();
        com.editora.i18n.Messages.init("en");
    }

    private static QuickOpen<String> shown(List<String> source) {
        QuickOpen<String> picker = new QuickOpen<>("Title", "Prompt", () -> source, s -> s, s -> "", s -> {});
        OverlayHost overlay = new OverlayHost();
        overlay.install(new StackPane());
        picker.setOverlayHost(overlay);
        return picker;
    }

    private static String said(QuickOpen<String> picker) {
        ListView<String> list = FxTestSupport.field(picker, "list");
        assertTrue(list.getItems().isEmpty(), "the placeholder is what an empty list shows");
        return ((Label) list.getPlaceholder()).getText();
    }

    @Test
    void anEmptyPickerSaysThereIsNothingToChooseFrom() throws Exception {
        FxTestSupport.runOnFx(() -> {
            QuickOpen<String> picker = shown(List.of());
            picker.show(null);
            assertEquals(tr("picker.empty"), said(picker));
        });
    }

    @Test
    void aPickerWithItsOwnSentenceSaysThat() throws Exception {
        FxTestSupport.runOnFx(() -> {
            QuickOpen<String> picker = shown(List.of());
            picker.setPlaceholder(tr("bookmarks.placeholder"));
            picker.show(null);
            assertEquals(tr("bookmarks.placeholder"), said(picker));
        });
    }

    @Test
    void aQueryThatMatchesNothingSaysNoMatchesAndTheSentenceComesBackWithTheRows() throws Exception {
        FxTestSupport.runOnFx(() -> {
            List<String> source = new ArrayList<>(List.of("alpha", "beta"));
            QuickOpen<String> picker = shown(source);
            picker.setPlaceholder(tr("bookmarks.placeholder"));
            picker.show(null);
            TextField input = FxTestSupport.field(picker, "input");
            input.setText("zzz");
            assertEquals(tr("search.none"), said(picker), "there are rows, only none of them matches");

            source.clear();
            input.setText("");
            picker.reload();
            assertEquals(tr("bookmarks.placeholder"), said(picker), "now there is nothing to choose from");
        });
    }

    /** One picker serves every list choice: a {@code ChoiceDialog} is a second way to ask the same question. */
    @Test
    void noListChoiceIsAskedWithASystemChoiceDialog() throws IOException {
        List<String> found = new ArrayList<>();
        try (Stream<Path> files = Files.walk(Path.of("src/main/java"))) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                if (Files.readString(file).matches("(?s).*new\\s+(?:javafx\\.scene\\.control\\.)?ChoiceDialog\\b.*")) {
                    found.add(file.toString());
                }
            }
        }
        assertTrue(found.isEmpty(), "ask with a QuickOpen picker instead: " + found);
    }
}

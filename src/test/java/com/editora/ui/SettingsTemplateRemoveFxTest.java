package com.editora.ui;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.ListView;
import javafx.scene.layout.Region;
import javafx.stage.Stage;

import com.editora.template.Template;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static com.editora.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Settings → Templates → Remove deleted the selected user template's file at once. A multi-file project
 * template cannot be rebuilt from that form, so one stray click cost the whole template. It now asks first.
 */
@Tag("fx")
class SettingsTemplateRemoveFxTest {

    private static final String PROJECT_TEMPLATE = """
            {
              "id": "my-project",
              "name": "My Project",
              "description": "hand-written",
              "files": [
                {"path": "README.md", "body": "# ${name}"},
                {"path": "src/Main.java", "body": "class Main {}"}
              ]
            }
            """;

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
    }

    private static <T extends Node> List<T> all(Node root, Class<T> type, List<T> out) {
        if (type.isInstance(root)) {
            out.add(type.cast(root));
        }
        if (root instanceof Parent p) {
            p.getChildrenUnmodifiable().forEach(c -> all(c, type, out));
        }
        return out;
    }

    private static Region page(SettingsWindow w, String category) {
        Map<?, Region> pages = FxTestSupport.field(w, "pages");
        for (var e : pages.entrySet()) {
            if (e.getKey().toString().equals(category)) {
                return e.getValue();
            }
        }
        throw new IllegalStateException(category);
    }

    private static Button button(Region page, String text) {
        for (Button b : all(page, Button.class, new ArrayList<>())) {
            if (text.equals(b.getText())) {
                return b;
            }
        }
        throw new IllegalStateException(text);
    }

    private static void answer(SettingsWindow w, Predicate<Template> answer) throws Exception {
        Field field = SettingsWindow.class.getDeclaredField("confirmTemplateRemoval");
        field.setAccessible(true);
        field.set(w, answer);
    }

    @Test
    void removeAsksFirstAndKeepsTheFileWhenTheAnswerIsNo() throws Exception {
        Path configDir = Files.createTempDirectory("editora-template-remove");
        Path file = Files.createDirectories(configDir.resolve("templates")).resolve("my-project.json");
        Files.writeString(file, PROJECT_TEMPLATE);
        try (var fx = FxWindowFixture.create(configDir, shared -> {})) {
            FxTestSupport.runOnFx(() -> {
                try {
                    SettingsWindow w = FxTestSupport.field(fx.controller, "settingsWindow");
                    Stage owner = new Stage();
                    owner.setWidth(1400);
                    owner.setHeight(900);
                    w.show(owner);
                    Region pg = page(w, "TEMPLATES");
                    @SuppressWarnings("unchecked")
                    ListView<Template> list = (ListView<Template>)
                            all(pg, ListView.class, new ArrayList<>()).get(0);
                    Template mine = list.getItems().stream()
                            .filter(t -> t.id().equals("my-project"))
                            .findFirst()
                            .orElseThrow();
                    assertTrue(mine.isMultiFile(), "the case that cannot be rebuilt from the form");
                    list.getSelectionModel().select(mine);
                    Button remove = button(pg, tr("settings.template.remove"));
                    assertFalse(remove.isDisabled());

                    List<String> asked = new ArrayList<>();
                    answer(w, t -> {
                        asked.add(t.id());
                        return false;
                    });
                    remove.fire();
                    assertEquals(List.of("my-project"), asked, "Remove asks before deleting");
                    assertEquals(PROJECT_TEMPLATE, Files.readString(file), "declined: the file is untouched");
                    assertTrue(list.getItems().contains(mine), "declined: the template is still listed");

                    answer(w, t -> true);
                    remove.fire();
                    assertFalse(Files.exists(file), "confirmed: the template file is removed");
                    assertTrue(list.getItems().stream().noneMatch(t -> t.id().equals("my-project")));
                    FxTestSupport.<Stage>field(w, "stage").hide();
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
        }
    }
}

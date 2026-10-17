package com.editora.ui;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Spinner;
import javafx.stage.Stage;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every Settings control a user can change is written to settings.json and read back: each switch, number and
 * choice field of the window is changed, the app is "restarted" on the same config folder, and a new Settings
 * window must show every change. A control whose value is applied but never saved, or saved but never loaded
 * (a missing line in {@code load()}), shows up here by name.
 */
@Tag("fx")
class SettingsRoundTripFxTest {

    /** Controls that are not preferences, or that ask first (a modal no test answers here). */
    private static final Set<String> NOT_ROUND_TRIPPED = Set.of(
            "mcpCheck", // asks a security question before it turns on
            "languageCombo", // read before any window exists; checked by SettingsControlsFxTest
            "remoteExecutionCheck"); // FxWindowFixture turns it off on every start (no host question in tests)

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
    }

    /** The window's switch, number and choice fields, by name. */
    private static Map<String, Object> controls(SettingsWindow window) throws IllegalAccessException {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Field f : SettingsWindow.class.getDeclaredFields()) {
            Class<?> t = f.getType();
            if ((t == CheckBox.class || t == Spinner.class || t == ComboBox.class)
                    && !NOT_ROUND_TRIPPED.contains(f.getName())
                    && !java.lang.reflect.Modifier.isStatic(f.getModifiers())) {
                f.setAccessible(true);
                Object c = f.get(window);
                if (c != null) {
                    out.put(f.getName(), c);
                }
            }
        }
        return out;
    }

    private static Object valueOf(Object control) {
        return switch (control) {
            case CheckBox c -> c.isSelected();
            case Spinner<?> s -> s.getValue();
            case ComboBox<?> c -> c.getValue();
            default -> null;
        };
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void change(Object control) {
        switch (control) {
            case CheckBox c -> c.setSelected(!c.isSelected());
            case Spinner<?> s -> s.increment(1);
            case ComboBox c -> {
                List items = c.getItems();
                if (items.size() > 1) {
                    int i = items.indexOf(c.getValue());
                    c.setValue(items.get(i + 1 < items.size() ? i + 1 : 0));
                }
            }
            default -> {}
        }
    }

    private static boolean disabled(Object control) {
        return control instanceof javafx.scene.Node n && n.isDisabled();
    }

    @Test
    void everyControlIsSavedAndReadBack() throws Exception {
        Path dir = Files.createTempDirectory("editora-fx-roundtrip");
        Map<String, Object> expected = new LinkedHashMap<>();
        var first = FxWindowFixture.create(dir, shared -> {});
        first.keepConfigDir = true;
        try {
            SettingsWindow window = first.controller.settingsWindow;
            FxTestSupport.runOnFx(() -> window.show(FxTestSupport.<Stage>field(first.controller, "stage")));
            Map<String, Object> controls = FxTestSupport.callOnFx(() -> controls(window));
            for (var e : controls.entrySet()) {
                FxTestSupport.runOnFx(() -> {
                    if (!disabled(e.getValue())) {
                        change(e.getValue());
                    }
                });
            }
            FxTestSupport.drainFx();
            // What the window shows after every change (a switch can change another one, a theme an editor theme).
            FxTestSupport.runOnFx(() -> controls.forEach((name, c) -> expected.put(name, valueOf(c))));
            FxTestSupport.runOnFx(
                    () -> FxTestSupport.<Stage>field(window, "stage").close());
            first.shared.flushWrites();
        } finally {
            first.close();
        }
        try (var second = FxWindowFixture.create(dir, shared -> {})) {
            SettingsWindow window = second.controller.settingsWindow;
            FxTestSupport.runOnFx(() -> window.show(FxTestSupport.<Stage>field(second.controller, "stage")));
            List<String> lost = new ArrayList<>();
            FxTestSupport.runOnFx(() -> {
                try {
                    controls(window).forEach((name, c) -> {
                        Object was = expected.get(name);
                        if (!java.util.Objects.equals(was, valueOf(c))) {
                            lost.add(name + ": set " + was + ", read back " + valueOf(c));
                        }
                    });
                } catch (IllegalAccessException e) {
                    throw new IllegalStateException(e);
                }
                FxTestSupport.<Stage>field(window, "stage").hide();
            });
            assertTrue(lost.isEmpty(), "not saved and read back:\n" + String.join("\n", lost));
        }
    }
}

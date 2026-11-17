package com.editora.ui;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Consumer;

import javafx.event.ActionEvent;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ColorPicker;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.Slider;
import javafx.scene.control.Spinner;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.stage.Stage;

import com.editora.command.Command;
import com.editora.command.CommandRegistry;
import com.editora.config.Settings;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every setting the Settings window shows has a palette command that flips it or prompts for its value (SET-06,
 * Pauta 6.0.1). Nothing here is a list kept by hand: every control of every row is changed, and the settings it
 * wrote are what the row sets; every setting command is run, each of its pickers answered row by row and each of
 * its prompts with a new value, and the settings it wrote are what the palette sets. A row whose setting no command
 * writes fails the test, so a new row without a command cannot land.
 *
 * <p>A row that only shows something or runs an action writes no setting and needs no command (the rule says so).
 * A row that edits one item of a list (a snippet, a template, an external tool, a remote site, a macro, a keymap
 * shortcut) writes another store, and the list's own commands add to it; that store is not the settings file.
 */
@Tag("fx")
class EverySettingHasACommandFxTest {

    /**
     * Settings a row writes that take no command, and why: only what the rule itself exempts. SET-06 exempts a key
     * SET-16 keeps out of the UI; a key that switches off a question asked to protect the user is such a key, so
     * it gets no command, and its row is SET-16's to remove.
     */
    private static final Map<String, String> EXEMPT = Map.of(
            "terminalConfirmMultilinePaste",
            "SET-16: switches off the question asked before a paste of several lines runs them in the shell");

    /** Runs a command once for each AI provider: its model, endpoint and key are the chosen provider's. */
    private static List<Consumer<Settings>> perProvider() {
        return com.editora.ai.AiProvider.ids().stream()
                .map(id -> (Consumer<Settings>) s -> s.setAiProvider(id))
                .toList();
    }

    /**
     * A command whose setting depends on another one (the AI model is the chosen provider's): run once per value
     * of that other setting, so each provider's own setting is seen written.
     */
    private static final Map<String, List<Consumer<Settings>>> CONTEXTS = Map.of(
            // Picks the agent client: offered while the AI agent is on, as its own header picker is.
            "agent.selectClient",
            List.of(s -> {
                s.setAiEnabled(true);
                s.setAgentSupport(true);
            }),
            "ai.setModel",
            perProvider(),
            "ai.setCompletionModel",
            perProvider(),
            "ai.setEndpoint",
            perProvider(),
            "ai.setApiKey",
            perProvider());

    private static FxWindowFixture fx;
    private static CommandRegistry registry;
    private static SettingsWindow window;
    private static Stage main;
    private static ObjectMapper json;

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
        fx = FxWindowFixture.create();
        registry = FxTestSupport.field(fx.controller, "registry");
        window = FxTestSupport.field(fx.controller, "settingsWindow");
        main = FxTestSupport.field(fx.controller, "stage");
        json = FxTestSupport.field(FxTestSupport.<Object>field(fx.controller, "config"), "json");
        FxTestSupport.runOnFx(() -> window.show(main));
        FxTestSupport.drainFx();
    }

    @AfterAll
    static void dispose() throws Exception {
        if (fx != null) {
            FxTestSupport.runOnFx(() -> {
                javafx.stage.Window shown = window.stageIfShowing();
                if (shown != null) {
                    shown.hide();
                }
            });
            fx.dispose();
        }
    }

    private static Settings settings() {
        return fx.shared.getSettings();
    }

    /** The settings the window opened with: each command runs from them, not from what the one before left. */
    private static JsonNode baseline;

    /** Puts the settings back as they were before the first change, without telling anything that they changed. */
    private static void restore() {
        try {
            json.readerForUpdating(settings()).readValue(baseline);
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    @Test
    void everySettingARowWritesIsWrittenByAPaletteCommand() throws Exception {
        baseline = FxTestSupport.callOnFx(() -> json.valueToTree(settings()));
        Map<String, String> rowSettings = FxTestSupport.callOnFx(EverySettingHasACommandFxTest::whatTheRowsWrite);
        assertTrue(rowSettings.size() > 300, "the rows were found and changed: " + rowSettings.size());
        Set<String> commandSettings = whatTheCommandsWrite();
        assertTrue(commandSettings.size() > 200, "the commands were found and run: " + commandSettings.size());
        List<String> missing = new ArrayList<>();
        rowSettings.forEach((setting, row) -> {
            if (!commandSettings.contains(setting) && !EXEMPT.containsKey(setting)) {
                missing.add(setting + " (" + row + ")");
            }
        });
        assertEquals(List.of(), missing, "settings a Settings row writes and no palette command does");
        for (String setting : EXEMPT.keySet()) {
            assertTrue(rowSettings.containsKey(setting), setting + " is exempted but no row writes it any more");
            assertTrue(!commandSettings.contains(setting), setting + " is exempted but a command writes it");
        }
    }

    // ---- the rows ----

    /** Every setting some row of the Settings window writes, with the row that writes it (page ▸ title). */
    private static Map<String, String> whatTheRowsWrite() {
        Map<String, String> out = new TreeMap<>();
        for (SettingsSearch.Row row : List.copyOf(window.rows)) {
            List<Node> controls = new ArrayList<>();
            controlsIn(row.node(), controls);
            for (Node control : controls) {
                Set<String> wrote = new TreeSet<>();
                if (control instanceof TextField field) {
                    // A text a row refuses is not kept: try the next likely value until one is.
                    for (String candidate : candidates(field.getText())) {
                        wrote = written(() -> {
                            field.setText(candidate);
                            field.fireEvent(new ActionEvent());
                        });
                        if (!wrote.isEmpty()) {
                            break;
                        }
                    }
                } else {
                    wrote = written(() -> change(control));
                }
                for (String setting : wrote) {
                    out.putIfAbsent(setting, row.page() + " ▸ " + title(row.node()));
                }
            }
        }
        return out;
    }

    private static String title(Node row) {
        return SettingsRig.all(row, Label.class).stream()
                .filter(l -> l.getStyleClass().contains("settings-row-title"))
                .map(Label::getText)
                .findFirst()
                .orElseGet(() -> String.join(" | ", SettingsRig.texts(row)));
    }

    /** The controls a row holds that change a value; a picker's own parts are not separate controls. */
    private static void controlsIn(Node node, List<Node> out) {
        if (node instanceof SettingSwitch
                || node instanceof CheckBox
                || node instanceof ToggleButton
                || node instanceof ComboBox<?>
                || node instanceof Spinner<?>
                || node instanceof Slider
                || node instanceof ColorPicker
                || node instanceof TextField) {
            out.add(node);
            return;
        }
        if (node instanceof Parent parent) {
            for (Node child : parent.getChildrenUnmodifiable()) {
                controlsIn(child, out);
            }
        }
    }

    /** Changes {@code control}'s value as a user would, whether or not the control is enabled just now. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void change(Node control) {
        switch (control) {
            case SettingSwitch sw
            when sw.getProperties().get(SettingSwitch.CHECK) instanceof CheckBox check ->
                check.setSelected(!check.isSelected());
            case SettingSwitch sw -> sw.setSelected(!sw.isSelected());
            case CheckBox check -> check.setSelected(!check.isSelected());
            case ToggleButton toggle -> toggle.setSelected(!toggle.isSelected());
            case ComboBox combo -> {
                Object now = combo.getValue();
                for (Object item : combo.getItems()) {
                    if (item != null && !item.equals(now) && !ThemeGroups.isHeading(String.valueOf(item))) {
                        combo.setValue(item);
                        break;
                    }
                }
            }
            case Spinner spinner -> {
                Object before = spinner.getValue();
                spinner.getValueFactory().increment(1);
                if (java.util.Objects.equals(before, spinner.getValue())) {
                    spinner.getValueFactory().decrement(1);
                }
            }
            case Slider slider ->
                slider.setValue(slider.getValue() == slider.getMax() ? slider.getMin() : slider.getMax());
            case ColorPicker picker -> {
                picker.setValue(javafx.scene.paint.Color.web("#123456"));
                picker.fireEvent(new ActionEvent());
            }
            default -> throw new IllegalArgumentException(control.toString());
        }
    }

    // ---- the commands ----

    /** Whether {@code id} names a command that changes a setting: a toggle, a picker or a prompt for a value. */
    private static boolean isSettingCommand(String id) {
        return id.matches("[a-zA-Z]+\\.(toggle|set|choose|cycle|select|change)[A-Z].*")
                || id.matches(".*[zZ]oom(In|Out)")
                || id.equals("keymap.select")
                || id.equals("todo.addPattern");
    }

    /** Every setting some setting command writes. */
    private static Set<String> whatTheCommandsWrite() throws Exception {
        Set<String> out = new TreeSet<>();
        List<String> ids = registry.all().stream()
                .map(Command::id)
                .filter(EverySettingHasACommandFxTest::isSettingCommand)
                .sorted()
                .toList();
        // A command may ask later, from a worker's answer (a lower history limit deletes revisions): any dialog
        // that shows while the commands run is closed, which declines it.
        javafx.animation.AnimationTimer closer = new javafx.animation.AnimationTimer() {
            @Override
            public void handle(long now) {
                for (javafx.stage.Window w : List.copyOf(javafx.stage.Window.getWindows())) {
                    if (w.isShowing()
                            && w.getScene() != null
                            && w.getScene().getRoot() instanceof javafx.scene.control.DialogPane) {
                        w.hide();
                    }
                }
            }
        };
        FxTestSupport.runOnFx(closer::start);
        try {
            for (String id : ids) {
                for (Consumer<Settings> context : CONTEXTS.getOrDefault(id, List.of(s -> {}))) {
                    // A command that opens with a picker is run once per row of it: one row may be one setting.
                    out.addAll(runOnce(id, context, 0));
                    int rows = lastPickerRows;
                    for (int row = 1; row < Math.min(rows, 80); row++) {
                        out.addAll(runOnce(id, context, row));
                    }
                }
            }
        } finally {
            FxTestSupport.runOnFx(closer::stop);
        }
        return out;
    }

    /**
     * Runs command {@code id} from the settings the window opened with, answering its first picker with row
     * {@code pick}, and returns the settings it wrote, once what it started has had time to land. A prompt is
     * answered with a value one more, or longer, than the one it shows; when that is refused or clamped back to
     * the same value, the command is run again with one less, or another text.
     */
    private static Set<String> runOnce(String id, Consumer<Settings> context, int pick) throws Exception {
        Set<String> changed = new TreeSet<>();
        for (int attempt = 0; attempt < CANDIDATES && changed.isEmpty(); attempt++) {
            int other = attempt;
            JsonNode before = FxTestSupport.callOnFx(() -> {
                restore();
                context.accept(settings());
                return json.valueToTree(settings());
            });
            // What the command starts off the FX thread is counted while it runs, so the test can wait for it.
            java.util.concurrent.atomic.AtomicInteger working = new java.util.concurrent.atomic.AtomicInteger();
            com.editora.perf.OffFx.runnerForTest((name, task) -> {
                working.incrementAndGet();
                Thread.ofVirtual().name(name).start(() -> {
                    try {
                        task.run();
                    } finally {
                        working.decrementAndGet();
                    }
                });
            });
            try {
                FxTestSupport.runOnFx(() -> runAnswering(id, pick, other));
                FxTestSupport.drainFx();
                changed.addAll(diff(before, FxTestSupport.callOnFx(() -> json.valueToTree(settings()))));
                if (changed.isEmpty()) {
                    // Nothing yet: what it started on a worker may still be on its way back.
                    com.editora.testkit.Await.until(
                            () -> working.get() == 0, id + "'s work off the FX thread", Duration.ofSeconds(10));
                    FxTestSupport.drainFx();
                    changed.addAll(diff(before, FxTestSupport.callOnFx(() -> json.valueToTree(settings()))));
                }
            } finally {
                com.editora.perf.OffFx.runnerForTest(null);
            }
            if (!answeredAForm) {
                break;
            }
        }
        return changed;
    }

    /** How many rows the first picker of the last command run had (0 when it opened none). */
    private static int lastPickerRows;

    /** Whether the last command run asked for a value. */
    private static boolean answeredAForm;

    /**
     * Runs command {@code id}: its first picker is answered with row {@code firstPick}, a later picker with a row
     * other than the one it opens on, a prompt with a value other than the one it shows ({@code other}: another
     * one), a dialog by closing it.
     */
    private static void runAnswering(String id, int firstPick, int other) {
        Scene scene = main.getScene();
        lastPickerRows = 0;
        answeredAForm = false;
        SettingsRig.answering(shown -> null, () -> registry.run(id));
        for (int step = 0; step < 4; step++) {
            Node picker = OverlayTestKit.picker(scene);
            if (picker != null) {
                ListView<?> list = (ListView<?>) picker.lookup(".list-view");
                int count = list.getItems().size();
                int row;
                if (step == 0) {
                    lastPickerRows = count;
                    row = firstPick;
                } else {
                    int at = list.getSelectionModel().getSelectedIndex();
                    row = count <= 1 ? 0 : (Math.max(at, 0) + 1) % count;
                }
                SettingsRig.answering(shown -> null, () -> OverlayTestKit.pickRow(scene, row));
            } else if (OverlayTestKit.form(scene) != null) {
                List<TextField> fields = OverlayTestKit.formFields(scene);
                String now = fields.isEmpty() ? "" : fields.get(0).getText();
                answeredAForm = true;
                SettingsRig.answering(
                        shown -> null,
                        () -> OverlayTestKit.submitForm(scene, candidates(now).get(other)));
            } else {
                return;
            }
        }
        OverlayTestKit.cancelPicker(scene);
    }

    // ---- helpers ----

    /** How many values {@link #candidates} offers. */
    private static final int CANDIDATES = 4;

    /**
     * Values other than {@code text} to type into a field or a prompt, most likely first: a number one more and one
     * less; a colour another colour; else the text with a letter added, a word, a local address and a file pattern
     * (the shapes a field that checks what is typed takes).
     */
    private static List<String> candidates(String text) {
        String now = text == null ? "" : text.strip();
        if (now.matches("-?\\d{1,9}")) {
            long n = Long.parseLong(now);
            return List.of(Long.toString(n + 1), Long.toString(n - 1), Long.toString(n + 10), "1");
        }
        if (now.matches("#[0-9a-fA-F]{6}")) {
            return List.of(now.equalsIgnoreCase("#123456") ? "#654321" : "#123456", "#abcdef", "#000000", "#ffffff");
        }
        return List.of(
                now + "x",
                now.equals("editora") ? "other" : "editora",
                "http://localhost:5001",
                "*.editora.xml=editora.xsd");
    }

    /** The top-level settings {@code action} changed, compared as they would be written to the settings file. */
    private static Set<String> written(Runnable action) {
        JsonNode before = json.valueToTree(settings());
        try {
            SettingsRig.answering(shown -> null, action);
        } catch (RuntimeException e) {
            throw new AssertionError("changing a setting failed", e);
        }
        return diff(before, json.valueToTree(settings()));
    }

    /** The top-level settings that differ between two copies of them. */
    private static Set<String> diff(JsonNode before, JsonNode after) {
        Set<String> changed = new TreeSet<>();
        Set<String> names = new TreeSet<>();
        before.fieldNames().forEachRemaining(names::add);
        after.fieldNames().forEachRemaining(names::add);
        for (String name : names) {
            if (!java.util.Objects.equals(before.get(name), after.get(name))) {
                changed.add(name);
            }
        }
        return changed;
    }
}

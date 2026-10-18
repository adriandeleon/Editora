package com.editora.ui;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Spinner;
import javafx.scene.control.TextInputControl;

import com.editora.config.Settings;
import com.editora.config.SettingsFileCheck;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Which Settings rows show a value other than the default, and putting one row back to its default.
 *
 * <p>Each control of the window edits one {@code settings.json} key; the table {@code settings-controls.properties}
 * names it, by the control's field (or {@code mapField[key]} for a control kept in a map). A row's keys are those
 * of the controls inside it, and the row is modified when any of them differs from a fresh {@link Settings}.
 * {@code SettingsModifiedFxTest} changes every control and checks the table against the key the change wrote, so
 * a control added without an entry fails there.
 */
final class SettingsModified {

    /** Keys that are never marked or reset row by row: credentials, and limits whose reset could delete history. */
    static boolean marked(String key) {
        return !key.toLowerCase(java.util.Locale.ROOT).contains("apikey") && !key.startsWith("historyMax");
    }

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final JsonNode DEFAULTS = JSON.valueToTree(new Settings());
    private static final Map<String, String> TABLE = table();

    private final Map<Node, String> controlKeys = new IdentityHashMap<>();
    private final Map<Node, Set<String>> rowKeys = new IdentityHashMap<>();

    /** Reads the controls of {@code window} (a {@link SettingsWindow}) and the keys the table gives them. */
    SettingsModified(Object window) {
        for (Field f : window.getClass().getDeclaredFields()) {
            if (Modifier.isStatic(f.getModifiers())) {
                continue;
            }
            Object value;
            try {
                f.setAccessible(true);
                value = f.get(window);
            } catch (ReflectiveOperationException | RuntimeException e) {
                continue;
            }
            if (value instanceof Node n && isControl(n)) {
                put(n, f.getName());
            } else if (value instanceof Map<?, ?> map) {
                for (var e : map.entrySet()) {
                    if (e.getValue() instanceof Node n && isControl(n)) {
                        put(n, f.getName() + "[" + e.getKey() + "]");
                    }
                }
            }
        }
    }

    private void put(Node control, String name) {
        String key = TABLE.get(name);
        if (key != null) {
            controlKeys.put(control, key);
        }
    }

    private static boolean isControl(Node n) {
        return n instanceof CheckBox || n instanceof Spinner || n instanceof ComboBox || n instanceof TextInputControl;
    }

    /** The settings keys the controls inside {@code row} edit (a switch counts as its checkbox). */
    Set<String> keysOf(Node row) {
        return rowKeys.computeIfAbsent(row, r -> {
            Set<String> keys = new LinkedHashSet<>();
            collect(r, keys);
            return keys;
        });
    }

    private void collect(Node node, Set<String> keys) {
        Node control = node.getProperties().get(SettingSwitch.CHECK) instanceof CheckBox check ? check : node;
        String key = controlKeys.get(control);
        if (key != null && marked(key)) {
            keys.add(key);
        }
        if (node instanceof Parent p && !(node instanceof javafx.scene.control.Control)) {
            for (Node child : p.getChildrenUnmodifiable()) {
                collect(child, keys);
            }
        }
    }

    /** The keys whose value in {@code settings} differs from the default. Pure apart from the shared defaults. */
    static Set<String> modifiedKeys(Settings settings) {
        JsonNode now = JSON.valueToTree(settings);
        Set<String> out = new LinkedHashSet<>();
        for (Iterator<String> it = now.fieldNames(); it.hasNext(); ) {
            String key = it.next();
            if (marked(key) && !SettingsFileCheck.sameValue(now.get(key), DEFAULTS.path(key))) {
                out.add(key);
            }
        }
        // The editor theme follows the app theme until one is picked: a followed theme is not a change.
        if (!settings.isEditorThemeUserSet()) {
            out.remove("editorTheme");
        }
        out.remove("editorThemeUserSet");
        return out;
    }

    /** Puts {@code keys} of {@code settings} back to their defaults. */
    static void reset(Settings settings, Set<String> keys) {
        ObjectNode defaults = JSON.createObjectNode();
        for (String key : keys) {
            switch (key) {
                case "keymap" ->
                    KeymapLayers.switchKeymap(settings, DEFAULTS.path("keymap").asText());
                case "editorTheme" -> {
                    settings.setEditorThemeUserSet(false);
                    settings.setEditorTheme(EditorThemes.defaultFor(settings.getTheme()));
                }
                default -> {
                    if (marked(key) && DEFAULTS.has(key)) {
                        defaults.set(key, DEFAULTS.get(key));
                    }
                }
            }
        }
        try {
            JSON.readerForUpdating(settings).readValue((JsonNode) defaults);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Marks every row of {@code rows} modified or not for {@code settings}, gives each marked row its reset
     * ({@code reset} receives the row's keys), and returns how many modified rows each page has.
     */
    Map<Object, Integer> refresh(
            List<SettingsSearch.Row> rows, Settings settings, java.util.function.Consumer<Set<String>> reset) {
        Set<String> changed = modifiedKeys(settings);
        Map<Object, Integer> perPage = new LinkedHashMap<>();
        for (SettingsSearch.Row row : rows) {
            Set<String> keys = keysOf(row.node());
            SettingRowState state = keys.isEmpty() ? SettingRowState.peek(row.node()) : SettingRowState.of(row.node());
            if (state == null) {
                continue;
            }
            boolean on = keys.stream().anyMatch(changed::contains);
            state.setModified(on);
            state.setResetAction(keys.isEmpty() ? null : () -> reset.accept(keys));
            if (on) {
                perPage.merge(row.page(), 1, Integer::sum);
            }
        }
        return perPage;
    }

    /** Every row's keys, for the rows with at least one. */
    Map<Node, Set<String>> rowsWithKeys(List<Node> rows) {
        Map<Node, Set<String>> out = new LinkedHashMap<>();
        for (Node row : rows) {
            Set<String> keys = keysOf(row);
            if (!keys.isEmpty()) {
                out.put(row, keys);
            }
        }
        return out;
    }

    /** The table's entries (control name → key), for the test that checks them. */
    static Map<String, String> table() {
        Properties p = new Properties();
        try (InputStream in = SettingsModified.class.getResourceAsStream("settings-controls.properties")) {
            if (in != null) {
                p.load(in);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        Map<String, String> out = new LinkedHashMap<>();
        for (String name : new ArrayList<>(p.stringPropertyNames())) {
            out.put(name, p.getProperty(name));
        }
        return out;
    }
}

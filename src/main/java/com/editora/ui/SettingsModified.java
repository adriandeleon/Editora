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
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.introspect.BeanPropertyDefinition;
import org.jspecify.annotations.Nullable;

/**
 * Which Settings rows show a value other than the default, and putting one row back to its default.
 *
 * <p>Each control of the window edits one {@code settings.json} key; the table {@code settings-controls.properties}
 * names it, by the control's field (or {@code mapField[key]} for a control kept in a map) in the window or the page
 * class that holds it; the names are unique across them. A row's keys are those
 * of the controls inside it, and the row is modified when any of them differs from a fresh {@link Settings}.
 * {@code SettingsModifiedFxTest} changes every control and checks the table against the key the change wrote, so
 * a control added without an entry fails there.
 */
final class SettingsModified {

    /** Keys that are never marked or reset row by row: credentials, and limits whose reset could delete history. */
    static boolean marked(String key) {
        return !key.toLowerCase(java.util.Locale.ROOT).contains("apikey") && !key.startsWith("historyMax");
    }

    /** Only introspects {@link Settings}' properties: no value is serialized or read as JSON here. */
    private static final ObjectMapper JSON = new ObjectMapper();

    private static final Map<String, String> TABLE = table();

    private final Map<Node, String> controlKeys = new IdentityHashMap<>();
    private final Map<Node, Set<String>> rowKeys = new IdentityHashMap<>();

    /**
     * Reads the controls of {@code owners} (a {@link SettingsWindow} and its page classes, see {@link
     * SettingsWindow#controlOwners}) and the keys the table gives them.
     */
    SettingsModified(List<Object> owners) {
        for (Object owner : owners) {
            read(owner);
        }
    }

    private void read(Object owner) {
        for (Field f : owner.getClass().getDeclaredFields()) {
            if (Modifier.isStatic(f.getModifiers())) {
                continue;
            }
            Object value;
            try {
                f.setAccessible(true);
                value = f.get(owner);
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

    /**
     * The keys whose value in {@code settings} differs from the default. Pure apart from the shared defaults.
     *
     * <p>Compares the properties' values as {@code settings.json} would write them, read through their accessors,
     * with no JSON made (PERF-01, Pauta 6.0.1: this runs on the FX thread on every change in Settings). Numbers
     * compare by value, and a map or an object of the app's own matches when each of its entries matches the
     * default's, as {@link SettingsFileCheck#sameValue} judges two written values.
     */
    static Set<String> modifiedKeys(Settings settings) {
        Set<String> out = new LinkedHashSet<>();
        for (Map.Entry<String, Prop> e : PROPERTIES.entrySet()) {
            String key = e.getKey();
            if (marked(key) && !same(read(e.getValue(), settings), DEFAULT_VALUES.get(key))) {
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

    /**
     * The keys whose value differs between {@code before} and {@code now}, compared as {@link #modifiedKeys} does but
     * both ways (a map entry added or removed counts). No JSON is made.
     */
    static Set<String> changedKeys(Settings before, Settings now) {
        Set<String> out = new LinkedHashSet<>();
        for (Map.Entry<String, Prop> e : PROPERTIES.entrySet()) {
            Object a = read(e.getValue(), before);
            Object b = read(e.getValue(), now);
            if (!same(a, b) || !same(b, a)) {
                out.add(e.getKey());
            }
        }
        return out;
    }

    /** Puts {@code keys} of {@code settings} back to their defaults. */
    static void reset(Settings settings, Set<String> keys) {
        for (String key : keys) {
            switch (key) {
                case "keymap" -> KeymapLayers.switchKeymap(settings, DEFAULTS.getKeymap());
                case "editorTheme" -> {
                    settings.setEditorThemeUserSet(false);
                    settings.setEditorTheme(EditorThemes.defaultFor(settings.getTheme()));
                }
                default -> {
                    Prop property = PROPERTIES.get(key);
                    if (marked(key) && property != null) {
                        write(property, settings, copyOf(DEFAULT_VALUES.get(key)));
                    }
                }
            }
        }
    }

    /** The values {@code keys} have in {@code settings} now, for {@link #restore} to put back after a {@link #reset}. */
    static Map<String, @Nullable Object> values(Settings settings, Set<String> keys) {
        Map<String, @Nullable Object> out = new LinkedHashMap<>();
        for (String key : keys) {
            Prop property = PROPERTIES.get(key);
            if (property != null) {
                out.put(key, copyOf(read(property, settings)));
            }
            if (key.equals("editorTheme")) {
                out.put("editorThemeUserSet", settings.isEditorThemeUserSet()); // whether the theme was picked
            }
        }
        return out;
    }

    /** Puts {@code values} (from {@link #values}) back into {@code settings}. */
    static void restore(Settings settings, Map<String, @Nullable Object> values) {
        Map<String, @Nullable Object> rest = new LinkedHashMap<>(values);
        Object keymap = rest.remove("keymap");
        if (keymap != null) {
            // as reset does: the keymap's own bindings follow
            KeymapLayers.switchKeymap(settings, String.valueOf(keymap));
        }
        for (Map.Entry<String, @Nullable Object> e : rest.entrySet()) {
            Prop property = PROPERTIES.get(e.getKey());
            if (property != null) {
                write(property, settings, copyOf(e.getValue()));
            }
        }
    }

    private static @Nullable Object copyOf(@Nullable Object value) {
        return value == null ? null : com.editora.config.Snapshots.copy(value);
    }

    /** The defaults, a fresh {@link Settings}. */
    private static final Settings DEFAULTS = new Settings();

    /** Each property {@code settings.json} writes, by its key: how it is read and written. */
    private static final Map<String, Prop> PROPERTIES = properties(Settings.class);

    /** Each property's default value. */
    private static final Map<String, @Nullable Object> DEFAULT_VALUES = defaultValues();

    private static final Map<Class<?>, Map<String, Prop>> OWN_PROPERTIES =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** A property as {@code settings.json} reads and writes it: its accessor, and its mutator when it has one. */
    private record Prop(
            com.fasterxml.jackson.databind.introspect.AnnotatedMember read,
            com.fasterxml.jackson.databind.introspect.@Nullable AnnotatedMember write) {}

    private static Map<String, Prop> properties(Class<?> type) {
        Map<String, com.fasterxml.jackson.databind.introspect.AnnotatedMember> writes = new LinkedHashMap<>();
        for (BeanPropertyDefinition p : JSON.getDeserializationConfig()
                .introspect(JSON.constructType(type))
                .findProperties()) {
            com.fasterxml.jackson.databind.introspect.AnnotatedMember m = p.getNonConstructorMutator();
            if (m != null) {
                m.fixAccess(true);
                writes.put(p.getName(), m);
            }
        }
        Map<String, Prop> out = new LinkedHashMap<>();
        for (BeanPropertyDefinition p : JSON.getSerializationConfig()
                .introspect(JSON.constructType(type))
                .findProperties()) {
            com.fasterxml.jackson.databind.introspect.AnnotatedMember m = p.getAccessor();
            if (m != null) {
                m.fixAccess(true);
                out.put(p.getName(), new Prop(m, writes.get(p.getName())));
            }
        }
        return out;
    }

    private static Map<String, @Nullable Object> defaultValues() {
        Map<String, @Nullable Object> out = new LinkedHashMap<>();
        PROPERTIES.forEach((key, property) -> out.put(key, read(property, DEFAULTS)));
        return out;
    }

    private static @Nullable Object read(Prop property, Object bean) {
        return property.read().getValue(bean);
    }

    private static void write(Prop property, Object bean, @Nullable Object value) {
        com.fasterxml.jackson.databind.introspect.AnnotatedMember mutator = property.write();
        if (mutator == null) {
            return; // read-only: nothing writes it back from settings.json either
        }
        try {
            if (mutator instanceof com.fasterxml.jackson.databind.introspect.AnnotatedMethod setter) {
                setter.callOnWith(bean, value);
            } else {
                mutator.setValue(bean, value);
            }
        } catch (Exception e) {
            throw new IllegalStateException("Cannot reset a setting", e);
        }
    }

    /**
     * Whether {@code now} and {@code used} would be written as the same value (see {@link
     * SettingsFileCheck#sameValue}): numbers by value, a map or an object of the app's own entry by entry (each of
     * {@code now}'s found alike in {@code used}), a list or an array item by item, anything else by {@code equals}.
     */
    static boolean same(@Nullable Object now, @Nullable Object used) {
        if (now == used) {
            return true;
        }
        if (now == null || used == null) {
            return false;
        }
        if (now instanceof Number a && used instanceof Number b) {
            try {
                return new java.math.BigDecimal(a.toString()).compareTo(new java.math.BigDecimal(b.toString())) == 0;
            } catch (NumberFormatException e) {
                return a.equals(b);
            }
        }
        if (now instanceof Map<?, ?> a && used instanceof Map<?, ?> b) {
            for (Map.Entry<?, ?> e : a.entrySet()) {
                Object key = e.getKey();
                if (!b.containsKey(key) || !same(e.getValue(), b.get(key))) {
                    return false;
                }
            }
            return true;
        }
        if (now instanceof Iterable<?> a && used instanceof Iterable<?> b) {
            Iterator<?> x = a.iterator();
            Iterator<?> y = b.iterator();
            while (x.hasNext() && y.hasNext()) {
                if (!same(x.next(), y.next())) {
                    return false;
                }
            }
            return !x.hasNext() && !y.hasNext();
        }
        if (now.getClass().isArray() && used.getClass().isArray()) {
            int n = java.lang.reflect.Array.getLength(now);
            if (n != java.lang.reflect.Array.getLength(used)) {
                return false;
            }
            for (int i = 0; i < n; i++) {
                if (!same(java.lang.reflect.Array.get(now, i), java.lang.reflect.Array.get(used, i))) {
                    return false;
                }
            }
            return true;
        }
        Class<?> type = now.getClass();
        if (type == used.getClass()
                && !type.isEnum()
                && type.getName().startsWith("com.editora.")
                && !now.equals(used)) {
            for (Prop p : OWN_PROPERTIES
                    .computeIfAbsent(type, SettingsModified::properties)
                    .values()) {
                if (!same(read(p, now), read(p, used))) {
                    return false;
                }
            }
            return true;
        }
        return now.equals(used);
    }

    /**
     * Marks every row of {@code rows} modified or not for {@code settings}, gives each marked row its reset
     * ({@code reset} receives the row's keys), and returns how many modified rows each page has.
     */
    Map<Object, Integer> refresh(
            List<SettingsSearch.Row> rows, Settings settings, java.util.function.Consumer<Set<String>> reset) {
        return refresh(rows, settings, reset, row -> false);
    }

    /**
     * As above, leaving alone the rows {@code lent} names: Settings' Project scope marks those itself (the
     * project's value, "Use my setting") while the window edits the project.
     */
    Map<Object, Integer> refresh(
            List<SettingsSearch.Row> rows,
            Settings settings,
            java.util.function.Consumer<Set<String>> reset,
            java.util.function.Predicate<Node> lent) {
        Set<String> changed = modifiedKeys(settings);
        Map<Object, Integer> perPage = new LinkedHashMap<>();
        for (SettingsSearch.Row row : rows) {
            if (lent.test(row.node())) {
                continue;
            }
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

    /** Loads the class, and with it {@link #TABLE}: called off the FX thread before the first reset asks. */
    static void warm() {
        // nothing more: the class's initialization reads the bundled table
    }

    /** The table's entries (control name → key), for the test that checks them. */
    static Map<String, String> table() {
        com.editora.perf.BlockingSites.enter("SettingsModified.table");
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

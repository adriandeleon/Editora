package com.editora.config;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The copies the FX thread hands the config writer ({@link Snapshots}): each serializes to exactly what the live
 * store did when it was taken, and nothing the FX thread does to the live store afterwards reaches it.
 */
class SnapshotsTest {

    private final ObjectMapper json = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    @Test
    void aCopyOfTheSettingsSerializesAsTheLiveOnesAndDoesNotFollowThem() throws Exception {
        Settings live = new Settings();
        live.setTabSize(7);
        live.setFontFamily("Guard Mono");
        live.setRulers(new ArrayList<>(List.of(80, 120)));
        live.getKeyBindings().put("emacs", new LinkedHashMap<>(Map.of("file.save", new ArrayList<>(List.of("C-s")))));
        byte[] before = json.writeValueAsBytes(live);

        Settings copy = Snapshots.copy(live);
        assertNotSame(live, copy);
        assertArrayEquals(before, json.writeValueAsBytes(copy), "the copy writes what the live settings wrote");

        live.setTabSize(2);
        live.getRulers().add(100);
        live.getKeyBindings().get("emacs").get("file.save").add("C-x C-s");
        assertArrayEquals(before, json.writeValueAsBytes(copy), "the FX thread's later changes do not reach it");
    }

    @Test
    void aSessionAndTheBucketedStoresCopyDeep() throws Exception {
        WorkspaceState state = new WorkspaceState();
        WorkspaceState.OpenFile open = new WorkspaceState.OpenFile();
        open.setPath("/x/a.txt");
        state.setOpenFiles(new ArrayList<>(List.of(open)));
        byte[] stateBefore = json.writeValueAsBytes(state);
        WorkspaceState stateCopy = Snapshots.copy(state);
        open.setPath("/x/b.txt");
        state.getOpenFiles().add(new WorkspaceState.OpenFile());
        assertArrayEquals(stateBefore, json.writeValueAsBytes(stateCopy));

        BookmarkStore bookmarks = new BookmarkStore();
        bookmarks
                .bucket("p")
                .computeIfAbsent("/x/a.txt", k -> new ArrayList<>())
                .add(new Bookmark(3, "n", "l"));
        byte[] bookmarksBefore = json.writeValueAsBytes(bookmarks);
        BookmarkStore bookmarksCopy = Snapshots.copy(bookmarks);
        bookmarks.bucket("p").get("/x/a.txt").add(new Bookmark(4, "m", "k"));
        bookmarks.bucket("q");
        assertArrayEquals(bookmarksBefore, json.writeValueAsBytes(bookmarksCopy));

        BreakpointStore breakpoints = new BreakpointStore();
        breakpoints
                .bucket("")
                .computeIfAbsent("/x/A.java", k -> new ArrayList<>())
                .add(Breakpoint.plain(1, "a();"));
        breakpoints.pinsBucket("").put("/x/A.java", new ArrayList<>(List.of(new PinnedLine(1, List.of("x"), "a();"))));
        byte[] breakpointsBefore = json.writeValueAsBytes(breakpoints);
        BreakpointStore breakpointsCopy = Snapshots.copy(breakpoints);
        breakpoints.bucket("").clear();
        assertArrayEquals(breakpointsBefore, json.writeValueAsBytes(breakpointsCopy));
    }

    /** A record whose components change is copied through its constructor; one whose components do not is kept. */
    record Holder(List<String> names, String label) {}

    @Test
    void recordsCollectionsAndTreesAreCopiedAndValuesThatCannotChangeAreShared() {
        Holder fixed = new Holder(List.of(), "a");
        assertSame(fixed.label(), Snapshots.copy(fixed).label());
        Holder mutable = new Holder(new ArrayList<>(List.of("a")), "b");
        Holder copied = Snapshots.copy(mutable);
        mutable.names().add("c");
        assertEquals(List.of("a"), copied.names());

        TreeMap<String, LinkedHashSet<String>> sorted = new TreeMap<>(java.util.Comparator.reverseOrder());
        sorted.put("a", new LinkedHashSet<>(List.of("1")));
        sorted.put("b", new LinkedHashSet<>(List.of("2")));
        TreeMap<String, LinkedHashSet<String>> sortedCopy = Snapshots.copy(sorted);
        assertEquals(List.of("b", "a"), List.copyOf(sortedCopy.keySet()), "the comparator comes along");
        sorted.get("a").add("3");
        assertEquals(java.util.Set.of("1"), sortedCopy.get("a"));

        ObjectNode tree = json.createObjectNode().put("k", 1);
        ObjectNode treeCopy = Snapshots.copy(tree);
        tree.put("k", 2);
        assertEquals(1, treeCopy.get("k").asInt());

        int[] numbers = {1, 2};
        int[] numbersCopy = Snapshots.copy(numbers);
        numbers[0] = 9;
        assertEquals(1, numbersCopy[0]);

        java.nio.file.Path path = java.nio.file.Path.of("/x");
        assertSame(path, Snapshots.copy(path));
        List<Object> twice = new ArrayList<>();
        List<String> shared = new ArrayList<>(List.of("s"));
        twice.add(shared);
        twice.add(shared);
        List<Object> twiceCopy = Snapshots.copy(twice);
        assertSame(twiceCopy.get(0), twiceCopy.get(1), "what was reachable twice is copied once");
    }

    @Test
    void assignFieldsMovesEveryWrittenFieldAndKeepsTheRuntimeOnes() throws Exception {
        Settings live = new Settings();
        Settings read = new Settings();
        read.setTabSize(9);
        read.setFontFamily("Read Mono");
        Snapshots.assignFields(read, live);
        assertEquals(json.writeValueAsString(read), json.writeValueAsString(live));
        assertThrows(IllegalArgumentException.class, () -> Snapshots.assignFields((Object) read, (Object) "x"));
    }

    // --- nothing that can change is shared --------------------------------------------------------------------

    @Test
    void noMutableObjectOfAnyStoreIsSharedWithItsCopy() throws Exception {
        List<Object> stores = new ArrayList<>(List.of(
                new Settings(),
                new WorkspaceState(),
                new BookmarkStore(),
                new NoteStore(),
                new BreakpointStore(),
                new ConnectionStore(),
                new PluginStore(),
                new TrustStore(),
                new MacroStore(),
                new AbbrevStore(),
                new ProjectManager.Index()));
        for (Object store : stores) {
            populate(store, 0);
            java.util.Set<Object> original = mutableReachable(store);
            assertTrue(original.size() > 1, store.getClass().getSimpleName() + " was populated: " + original.size());
            Object copy = Snapshots.copy(store);
            java.util.Set<Object> copied = mutableReachable(copy);
            copied.retainAll(original);
            assertEquals(
                    java.util.Set.of(),
                    copied,
                    store.getClass().getSimpleName() + " shares what can change with its copy");
            assertEquals(
                    json.writeValueAsString(store),
                    json.writeValueAsString(copy),
                    store.getClass().getSimpleName() + " copy serializes as the original");
        }
    }

    /** An application class with no no-argument constructor. */
    static final class NoDefault {
        final List<String> items;

        NoDefault(List<String> items) {
            this.items = items;
        }
    }

    @Test
    void whatCannotBeCopiedIsRefusedNotShared() {
        assertThrows(RuntimeException.class, () -> Snapshots.copy(new NoDefault(new ArrayList<>())));
        assertThrows(RuntimeException.class, () -> Snapshots.copy(new java.util.concurrent.atomic.AtomicInteger()));
        assertThrows(RuntimeException.class, () -> Snapshots.copy(List.of(new StringBuilder("a library mutable"))));
    }

    /**
     * Every object reachable from {@code root} that could change: everything but strings, boxed numbers, booleans,
     * characters, enums, classes, paths, URIs, UUIDs, locales, patterns, {@code java.time} values and records
     * (whose components are walked in turn). Transient
     * fields are skipped: they hold runtime attachments, shared on purpose.
     */
    private static java.util.Set<Object> mutableReachable(Object root) throws IllegalAccessException {
        java.util.Set<Object> seen = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        java.util.Set<Object> visited = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        java.util.ArrayDeque<Object> todo = new java.util.ArrayDeque<>(List.of(root));
        while (!todo.isEmpty()) {
            Object o = todo.pop();
            if (o == null || immutable(o.getClass()) || !visited.add(o)) {
                continue;
            }
            if (o.getClass().isRecord()) {
                // A record cannot change; what it holds may: walked, not counted.
                for (java.lang.reflect.RecordComponent part : o.getClass().getRecordComponents()) {
                    try {
                        java.lang.reflect.Method get = part.getAccessor();
                        get.setAccessible(true);
                        addIfPresent(todo, get.invoke(o));
                    } catch (ReflectiveOperationException e) {
                        throw new IllegalStateException(e);
                    }
                }
                continue;
            }
            seen.add(o);
            if (o instanceof Map<?, ?> m) {
                m.forEach((k, v) -> {
                    addIfPresent(todo, k);
                    addIfPresent(todo, v);
                });
            } else if (o instanceof java.util.Collection<?> c) {
                c.forEach(e -> addIfPresent(todo, e));
            } else if (o.getClass().isArray()
                    && !o.getClass().getComponentType().isPrimitive()) {
                for (Object e : (Object[]) o) {
                    addIfPresent(todo, e);
                }
            } else if (o instanceof com.fasterxml.jackson.databind.JsonNode) {
                continue;
            } else if (!o.getClass().isArray()) {
                for (Class<?> c = o.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
                    for (java.lang.reflect.Field f : c.getDeclaredFields()) {
                        int mod = f.getModifiers();
                        if (java.lang.reflect.Modifier.isStatic(mod) || java.lang.reflect.Modifier.isTransient(mod)) {
                            continue;
                        }
                        f.setAccessible(true);
                        addIfPresent(todo, f.get(o));
                    }
                }
            }
        }
        return seen;
    }

    private static void addIfPresent(java.util.ArrayDeque<Object> todo, Object o) {
        if (o != null) {
            todo.push(o);
        }
    }

    private static boolean immutable(Class<?> t) {
        return t.isPrimitive()
                || t.isEnum()
                || (t.getSuperclass() != null && t.getSuperclass().isEnum())
                || t == String.class
                || t == Boolean.class
                || t == Character.class
                || t == Class.class
                || t == Integer.class
                || t == Long.class
                || t == Double.class
                || t == Float.class
                || t == Short.class
                || t == Byte.class
                || t.getName().startsWith("java.time.")
                || java.nio.file.Path.class.isAssignableFrom(t)
                || t == java.net.URI.class
                || t == java.util.UUID.class
                || t == java.util.Locale.class
                || t == java.util.regex.Pattern.class;
    }

    /**
     * Fills every empty collection, empty map and null field of {@code o} (an application object) with one value of
     * its declared type, made the same way, a few levels deep: the store as a user's would be, every kind of entry
     * present.
     */
    private static void populate(Object o, int depth) throws Exception {
        if (depth > 5
                || o == null
                || !o.getClass().getName().startsWith("com.editora.")
                || o.getClass().isRecord()) {
            return;
        }
        for (Class<?> c = o.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            for (java.lang.reflect.Field f : c.getDeclaredFields()) {
                int mod = f.getModifiers();
                if (java.lang.reflect.Modifier.isStatic(mod) || java.lang.reflect.Modifier.isTransient(mod)) {
                    continue;
                }
                f.setAccessible(true);
                Object v = f.get(o);
                java.lang.reflect.Type g = f.getGenericType();
                if (v == null && !java.lang.reflect.Modifier.isFinal(mod)) {
                    Object made = make(g, depth + 1);
                    if (made != null) {
                        f.set(o, made);
                    }
                } else if (v instanceof java.util.Collection<?> col && col.isEmpty()) {
                    fillCollection(col, g, depth + 1);
                } else if (v instanceof Map<?, ?> map && map.isEmpty()) {
                    fillMap(map, g, depth + 1);
                } else if (v != null) {
                    populate(v, depth + 1);
                }
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static void fillCollection(java.util.Collection<?> col, java.lang.reflect.Type g, int depth) {
        if (g instanceof java.lang.reflect.ParameterizedType p) {
            Object e = make(p.getActualTypeArguments()[0], depth);
            if (e != null) {
                try {
                    ((java.util.Collection<Object>) col).add(e);
                } catch (UnsupportedOperationException fixed) {
                    // an unmodifiable default: left as it is
                }
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static void fillMap(Map<?, ?> map, java.lang.reflect.Type g, int depth) {
        if (g instanceof java.lang.reflect.ParameterizedType p) {
            Object k = make(p.getActualTypeArguments()[0], depth);
            Object v = make(p.getActualTypeArguments()[1], depth);
            if (k != null && v != null) {
                try {
                    ((Map<Object, Object>) map).put(k, v);
                } catch (UnsupportedOperationException fixed) {
                    // an unmodifiable default: left as it is
                }
            }
        }
    }

    /** One value of type {@code t}, populated; null when the test does not know how to make one. */
    private static Object make(java.lang.reflect.Type t, int depth) {
        if (depth > 5) {
            return null;
        }
        Class<?> raw = t instanceof java.lang.reflect.ParameterizedType p
                ? (Class<?>) p.getRawType()
                : t instanceof Class<?> c ? c : null;
        if (raw == null) {
            return null;
        }
        try {
            if (raw == String.class) {
                return "v" + depth;
            }
            if (raw == Integer.class || raw == int.class) {
                return 1;
            }
            if (raw == Long.class || raw == long.class) {
                return 1L;
            }
            if (raw == Double.class || raw == double.class) {
                return 1.0;
            }
            if (raw == Boolean.class || raw == boolean.class) {
                return Boolean.TRUE;
            }
            if (raw.isEnum()) {
                return raw.getEnumConstants()[0];
            }
            if (java.util.List.class.isAssignableFrom(raw) || raw == java.util.Collection.class) {
                ArrayList<Object> l = new ArrayList<>();
                fillCollection(l, t, depth + 1);
                return l;
            }
            if (java.util.Set.class.isAssignableFrom(raw)) {
                LinkedHashSet<Object> set = new LinkedHashSet<>();
                fillCollection(set, t, depth + 1);
                return set;
            }
            if (Map.class.isAssignableFrom(raw)) {
                LinkedHashMap<Object, Object> m = new LinkedHashMap<>();
                fillMap(m, t, depth + 1);
                return m;
            }
            if (raw.isRecord()) {
                java.lang.reflect.RecordComponent[] parts = raw.getRecordComponents();
                Object[] args = new Object[parts.length];
                Class<?>[] types = new Class<?>[parts.length];
                for (int i = 0; i < parts.length; i++) {
                    types[i] = parts[i].getType();
                    Object a = make(parts[i].getGenericType(), depth + 1);
                    args[i] = a != null ? a : types[i].isPrimitive() ? primitiveZero(types[i]) : null;
                }
                java.lang.reflect.Constructor<?> k = raw.getDeclaredConstructor(types);
                k.setAccessible(true);
                return k.newInstance(args);
            }
            if (raw.getName().startsWith("com.editora.")) {
                java.lang.reflect.Constructor<?> k = raw.getDeclaredConstructor();
                k.setAccessible(true);
                Object o = k.newInstance();
                populate(o, depth + 1);
                return o;
            }
        } catch (Exception cannot) {
            return null;
        }
        return null;
    }

    private static Object primitiveZero(Class<?> t) {
        if (t == boolean.class) {
            return false;
        }
        if (t == long.class) {
            return 0L;
        }
        if (t == double.class) {
            return 0.0;
        }
        if (t == float.class) {
            return 0f;
        }
        if (t == char.class) {
            return 'a';
        }
        if (t == byte.class) {
            return (byte) 0;
        }
        if (t == short.class) {
            return (short) 0;
        }
        return 0;
    }
}

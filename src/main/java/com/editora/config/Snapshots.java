package com.editora.config;

import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Modifier;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;

import com.fasterxml.jackson.databind.JsonNode;
import org.jspecify.annotations.Nullable;

/**
 * Deep copies of the config stores, made on the FX thread so the {@code config-writer} thread can serialize a
 * store while the FX thread goes on changing the live one (PERF-01, Pauta 6.0.1: no JSON on the FX thread). A
 * copy is a walk of the object graph, with no parsing and no text: the store's own classes are copied field by
 * field, collections and maps into new ones of the same kind, records through their canonical constructor, and
 * a JSON tree with {@link JsonNode#deepCopy()}. What cannot change (strings, numbers, enums, paths, time values)
 * is shared. Anything else — a library object of another kind, a class of this application without a
 * no-argument constructor — cannot be copied safely and fails the copy with {@link IllegalArgumentException}
 * rather than be shared with a thread that would read it while the FX thread changes it
 * ({@code SnapshotsTest} walks every store's graph).
 *
 * <p>Transient fields are shared, not copied: they are runtime attachments (the API key vault the settings hold),
 * which serialization skips.
 */
public final class Snapshots {

    private Snapshots() {}

    /** The fields an object of each application class is copied by: every instance field up the hierarchy. */
    private static final ClassValue<Field[]> FIELDS = new ClassValue<>() {
        @Override
        protected Field[] computeValue(Class<?> type) {
            List<Field> fields = new ArrayList<>();
            for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
                for (Field f : c.getDeclaredFields()) {
                    if (!Modifier.isStatic(f.getModifiers())) {
                        f.setAccessible(true);
                        fields.add(f);
                    }
                }
            }
            return fields.toArray(Field[]::new);
        }
    };

    /** The no-argument constructor of an application class, or {@code null} when it has none. */
    private static final ClassValue<Constructor<?>> NO_ARG = new ClassValue<>() {
        @Override
        protected @Nullable Constructor<?> computeValue(Class<?> type) {
            try {
                Constructor<?> c = type.getDeclaredConstructor();
                c.setAccessible(true);
                return c;
            } catch (NoSuchMethodException none) {
                return null;
            }
        }
    };

    /**
     * A deep copy of {@code value}: nothing reachable from the copy that can change is shared with {@code value}.
     * {@code null} gives {@code null}.
     */
    public static <T> T copy(T value) {
        return new Copier().copy(value);
    }

    /**
     * Sets every field of {@code target} that serialization writes (not static, not transient) to the value it
     * has in {@code source}, which must be of the same class. For a freshly read object that nobody else holds:
     * its values are moved over as they are, not copied. Used to apply a re-read {@code settings.json} to the
     * live settings that every window holds, without serializing on the FX thread.
     */
    public static <T> void assignFields(T source, T target) {
        if (source.getClass() != target.getClass()) {
            throw new IllegalArgumentException(
                    "not the same class: " + source.getClass() + " and " + target.getClass());
        }
        try {
            for (Field f : FIELDS.get(source.getClass())) {
                if (!Modifier.isTransient(f.getModifiers())) {
                    f.set(target, f.get(source));
                }
            }
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("Cannot assign " + source.getClass().getName(), e);
        }
    }

    /** Whether values of {@code type} cannot change once made, so a copy may share them. */
    private static boolean immutable(Class<?> type) {
        if (type.isPrimitive()
                || type.isEnum()
                || (type.getSuperclass() != null && type.getSuperclass().isEnum())
                || type == String.class
                || type == Boolean.class
                || type == Character.class
                || type == Class.class
                || type == Integer.class
                || type == Long.class
                || type == Double.class
                || type == Float.class
                || type == Short.class
                || type == Byte.class
                || type == java.math.BigDecimal.class
                || type == java.math.BigInteger.class) {
            return true;
        }
        String name = type.getName();
        return name.startsWith("java.time.")
                || java.nio.file.Path.class.isAssignableFrom(type)
                || type == java.net.URI.class
                || type == java.util.UUID.class
                || type == java.util.Locale.class
                || type == java.util.regex.Pattern.class;
    }

    /** Whether {@code type} is one of this application's own classes, copied field by field. */
    private static boolean ownClass(Class<?> type) {
        return type.getModule() == Snapshots.class.getModule() && type.getName().startsWith("com.editora.");
    }

    /** One copy: remembers what it copied, so an object reachable twice is copied once (and a cycle ends). */
    private static final class Copier {
        private final Map<Object, Object> done = new IdentityHashMap<>();

        @SuppressWarnings("unchecked")
        <T> T copy(T value) {
            return (T) java.util.Objects.requireNonNull(copyAny(value));
        }

        private @Nullable Object copyAny(@Nullable Object value) {
            if (value == null) {
                return null;
            }
            Class<?> type = value.getClass();
            if (immutable(type)) {
                return value;
            }
            Object seen = done.get(value);
            if (seen != null) {
                return seen;
            }
            if (value instanceof JsonNode node) {
                return remember(value, node.deepCopy());
            }
            if (type.isArray()) {
                return copyArray(value, type);
            }
            if (value instanceof Map<?, ?> map) {
                return copyMap(map);
            }
            if (value instanceof Collection<?> collection) {
                return copyCollection(collection);
            }
            if (type.isRecord()) {
                return copyRecord(value, type);
            }
            if (ownClass(type)) {
                return copyObject(value, type);
            }
            // A library object nothing here knows how to copy: shared, it would be read by the writer while the FX
            // thread changes it. A store that holds one says so now, rather than be torn later.
            throw new IllegalArgumentException("Cannot copy " + type.getName()
                    + ": not a collection, map, array, JSON tree, record, value or class of this application"
                    + " (a runtime attachment belongs in a transient field)");
        }

        private Object remember(Object original, Object copy) {
            done.put(original, copy);
            return copy;
        }

        private Object copyArray(Object array, Class<?> type) {
            int length = Array.getLength(array);
            Object copy = Array.newInstance(type.getComponentType(), length);
            remember(array, copy);
            if (type.getComponentType().isPrimitive()) {
                System.arraycopy(array, 0, copy, 0, length);
            } else {
                for (int i = 0; i < length; i++) {
                    Array.set(copy, i, copyAny(Array.get(array, i)));
                }
            }
            return copy;
        }

        @SuppressWarnings({"unchecked", "rawtypes"})
        private Object copyMap(Map<?, ?> map) {
            Map copy;
            if (map instanceof SortedMap<?, ?> sorted) {
                copy = new TreeMap(sorted.comparator());
            } else if (map instanceof EnumMap<?, ?> enumMap) {
                copy = new EnumMap(enumMap); // keys are enums; the values are replaced below
                copy.clear();
            } else if (map instanceof LinkedHashMap<?, ?>) {
                copy = new LinkedHashMap<>(Math.max(16, map.size() * 2));
            } else if (map instanceof HashMap<?, ?>) {
                copy = new HashMap<>(Math.max(16, map.size() * 2));
            } else if (map instanceof java.util.concurrent.ConcurrentHashMap<?, ?>) {
                copy = new java.util.concurrent.ConcurrentHashMap<>();
            } else {
                copy = new LinkedHashMap<>(Math.max(16, map.size() * 2)); // keeps the order serialization saw
            }
            remember(map, copy);
            for (Map.Entry<?, ?> e : map.entrySet()) {
                copy.put(copyAny(e.getKey()), copyAny(e.getValue()));
            }
            return copy;
        }

        @SuppressWarnings({"unchecked", "rawtypes"})
        private Object copyCollection(Collection<?> collection) {
            Collection copy;
            if (collection instanceof SortedSet<?> sorted) {
                copy = new TreeSet(sorted.comparator());
            } else if (collection instanceof EnumSet<?> enumSet) {
                copy = EnumSet.copyOf((EnumSet) enumSet); // enums are shared anyway
                return remember(collection, copy);
            } else if (collection instanceof LinkedHashSet<?>) {
                copy = new LinkedHashSet<>(Math.max(16, collection.size() * 2));
            } else if (collection instanceof HashSet<?>) {
                copy = new HashSet<>(Math.max(16, collection.size() * 2));
            } else if (collection instanceof Set<?>) {
                copy = new LinkedHashSet<>(Math.max(16, collection.size() * 2));
            } else if (collection instanceof LinkedList<?>) {
                copy = new LinkedList<>();
            } else {
                copy = new ArrayList<>(collection.size());
            }
            remember(collection, copy);
            for (Object element : collection) {
                copy.add(copyAny(element));
            }
            return copy;
        }

        private Object copyRecord(Object record, Class<?> type) {
            RecordComponent[] components = type.getRecordComponents();
            @Nullable Object[] values = new Object[components.length];
            Class<?>[] types = new Class<?>[components.length];
            boolean same = true;
            try {
                for (int i = 0; i < components.length; i++) {
                    java.lang.reflect.Method accessor = components[i].getAccessor();
                    accessor.setAccessible(true);
                    Object original = accessor.invoke(record);
                    values[i] = copyAny(original);
                    types[i] = components[i].getType();
                    same &= values[i] == original;
                }
                if (same) {
                    return remember(record, record); // nothing in it can change
                }
                Constructor<?> canonical = type.getDeclaredConstructor(types);
                canonical.setAccessible(true);
                return remember(record, canonical.newInstance(values));
            } catch (ReflectiveOperationException | RuntimeException e) {
                throw new IllegalStateException("Cannot copy " + type.getName(), unwrap(e));
            }
        }

        private Object copyObject(Object value, Class<?> type) {
            Constructor<?> make = NO_ARG.get(type);
            if (make == null) {
                throw new IllegalArgumentException("Cannot copy " + type.getName()
                        + ": no no-argument constructor (a runtime attachment belongs in a transient field)");
            }
            try {
                Object copy = make.newInstance();
                remember(value, copy);
                for (Field f : FIELDS.get(type)) {
                    Object original = f.get(value);
                    f.set(copy, Modifier.isTransient(f.getModifiers()) ? original : copyAny(original));
                }
                return copy;
            } catch (ReflectiveOperationException | RuntimeException e) {
                throw new IllegalStateException("Cannot copy " + type.getName(), unwrap(e));
            }
        }

        private static Throwable unwrap(Throwable e) {
            return e instanceof InvocationTargetException ite && ite.getCause() != null ? ite.getCause() : e;
        }
    }
}

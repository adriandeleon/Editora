package com.editora.config;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import com.editora.config.migration.ConfigSchema;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.introspect.BeanPropertyDefinition;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * A store's on-disk shape may not change without its schema version changing (data-loss review C10).
 *
 * <p>Every store type ignores unknown properties, so a build from before a field existed reads the file
 * without complaint and writes it back <em>without that field</em>. The only thing that stops it is the
 * schema version: a file with a newer one is set aside, not rewritten. {@code Bookmark.mnemonic} was added
 * with {@code BookmarkStore.SCHEMA_VERSION} left at 1, and an older build dropped every mnemonic on its first
 * bookmark toggle.
 *
 * <p>So each store's shape — the serialized property names of its type and of every Editora type nested in it,
 * with enum constants — is pinned here together with the version it belongs to. <b>When this test fails</b>
 * you changed what a store file contains: bump that store's {@code SCHEMA_VERSION}, register the
 * {@code v → v+1} step in {@link ConfigSchema} (identity for an additive field), and replace the store's line
 * in {@link #PINNED} with the one the failure message prints.
 */
class StoreShapeGuardTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** Each store's root type. */
    private static final Map<ConfigSchema, Class<?>> ROOTS = new EnumMap<>(ConfigSchema.class);

    /** {@code schema → "<schema version>:<shape fingerprint>"}. */
    private static final Map<ConfigSchema, String> PINNED = new EnumMap<>(ConfigSchema.class);

    static {
        ROOTS.put(ConfigSchema.SETTINGS, Settings.class);
        ROOTS.put(ConfigSchema.WORKSPACE, WorkspaceState.class);
        ROOTS.put(ConfigSchema.BOOKMARKS, BookmarkStore.class);
        ROOTS.put(ConfigSchema.BREAKPOINTS, BreakpointStore.class);
        ROOTS.put(ConfigSchema.PROJECTS, ProjectManager.Index.class);
        ROOTS.put(ConfigSchema.RECENT, RecentFiles.Stored.class);
        ROOTS.put(ConfigSchema.NOTES, NoteStore.class);
        ROOTS.put(ConfigSchema.CONNECTIONS, ConnectionStore.class);
        ROOTS.put(ConfigSchema.PLUGINS, PluginStore.class);
        ROOTS.put(ConfigSchema.HISTORY, HistoryStore.class);
        ROOTS.put(ConfigSchema.SEARCH_HISTORY, SearchHistory.Stored.class);
        ROOTS.put(ConfigSchema.AGENT_SESSIONS, AgentSessionHistory.Stored.class);
        ROOTS.put(ConfigSchema.MACROS, MacroStore.class);
        ROOTS.put(ConfigSchema.ABBREVIATIONS, AbbrevStore.class);
        ROOTS.put(ConfigSchema.TRUST, TrustStore.class);

        PINNED.put(ConfigSchema.SETTINGS, "113:551b214dd3558ff2");
        PINNED.put(ConfigSchema.WORKSPACE, "12:27696c0b0b47eb37");
        PINNED.put(ConfigSchema.BOOKMARKS, "2:c932fc9b729bab63");
        PINNED.put(ConfigSchema.BREAKPOINTS, "1:618a4b785df0ae84");
        PINNED.put(ConfigSchema.PROJECTS, "2:a373bb1579a65406");
        PINNED.put(ConfigSchema.RECENT, "1:d784f4ce61c5c2a8");
        PINNED.put(ConfigSchema.NOTES, "2:f89de7207bb41420");
        PINNED.put(ConfigSchema.CONNECTIONS, "1:308f177f817c47fe");
        PINNED.put(ConfigSchema.PLUGINS, "1:44ba1632b30d33b8");
        PINNED.put(ConfigSchema.HISTORY, "3:d48f2b71de7c05ee");
        PINNED.put(ConfigSchema.SEARCH_HISTORY, "1:5b92e22ca7e96bc2");
        PINNED.put(ConfigSchema.AGENT_SESSIONS, "2:3d4af4d26bc417e4");
        PINNED.put(ConfigSchema.MACROS, "1:2895c8d90802b2e0");
        PINNED.put(ConfigSchema.ABBREVIATIONS, "1:37b204a82fd27de2");
        PINNED.put(ConfigSchema.TRUST, "1:97df8267c8a7cf79");
    }

    @Test
    void everyStoreIsCovered() {
        for (ConfigSchema schema : ConfigSchema.values()) {
            assertTrue(ROOTS.containsKey(schema), schema + " has no root type here: add it to ROOTS and PINNED");
        }
    }

    @Test
    void noStoreChangedShapeWithoutASchemaVersionBump() throws Exception {
        List<String> stale = new ArrayList<>();
        List<String> unbumped = new ArrayList<>();
        for (ConfigSchema schema : ConfigSchema.values()) {
            String shape = fingerprint(ROOTS.get(schema));
            String now = schema.currentVersion() + ":" + shape;
            String pinned = PINNED.get(schema);
            if (now.equals(pinned)) {
                continue;
            }
            String line = "        PINNED.put(ConfigSchema." + schema + ", \"" + now + "\");";
            if (pinned != null && pinned.startsWith(schema.currentVersion() + ":")) {
                unbumped.add(schema + " (" + ROOTS.get(schema).getSimpleName() + "), then pin:\n" + line);
            } else {
                stale.add(line);
            }
        }
        if (!unbumped.isEmpty()) {
            fail("A store's on-disk shape changed but its SCHEMA_VERSION did not. An older build will read the"
                    + " new file and write it back without what it does not know. Bump the version and register"
                    + " the step in ConfigSchema for:\n" + String.join("\n", unbumped));
        }
        assertEquals(
                List.of(),
                stale,
                "A store's schema version changed; pin its new shape in StoreShapeGuardTest.PINNED with these lines");
    }

    @Test
    void theFingerprintSeesANestedRecordGainingAField() throws Exception {
        record Inner(int line, String note) {}
        record InnerWithMore(int line, String note, String mnemonic) {}
        record Outer(Map<String, List<Inner>> byFile) {}
        record OuterWithMore(Map<String, List<InnerWithMore>> byFile) {}

        String before = describe(JSON.constructType(Outer.class), new HashSet<>());
        String after = describe(JSON.constructType(OuterWithMore.class), new HashSet<>());

        assertEquals("Outer{byFile=map<String,list<Inner{line=int, note=String}>>}", before);
        assertEquals(
                "OuterWithMore{byFile=map<String,list<InnerWithMore{line=int, mnemonic=String, note=String}>>}", after);
    }

    private static String fingerprint(Class<?> root) throws Exception {
        String shape = describe(JSON.constructType(root), new HashSet<>());
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(shape.getBytes(StandardCharsets.UTF_8));
        return HexFormat.of().formatHex(digest, 0, 8);
    }

    /** The serialized shape of {@code type}: property names, recursively through Editora's own types. */
    private static String describe(JavaType type, Set<Class<?>> open) {
        if (type.isContainerType()) {
            StringBuilder sb = new StringBuilder(type.isMapLikeType() ? "map<" : "list<");
            if (type.getKeyType() != null) {
                sb.append(describe(type.getKeyType(), open)).append(',');
            }
            return sb.append(describe(type.getContentType(), open)).append('>').toString();
        }
        Class<?> raw = type.getRawClass();
        if (raw.isEnum()) {
            List<String> names = new ArrayList<>();
            for (Object constant : raw.getEnumConstants()) {
                names.add(((Enum<?>) constant).name());
            }
            return "enum" + names;
        }
        if (!raw.getName().startsWith("com.editora.")) {
            return raw.getSimpleName();
        }
        if (!open.add(raw)) {
            return raw.getSimpleName() + "(recursive)";
        }
        Map<String, String> properties = new TreeMap<>();
        for (BeanPropertyDefinition property :
                JSON.getSerializationConfig().introspect(type).findProperties()) {
            if (property.couldSerialize()) {
                properties.put(property.getName(), describe(property.getPrimaryType(), open));
            }
        }
        open.remove(raw);
        return raw.getSimpleName() + properties;
    }
}

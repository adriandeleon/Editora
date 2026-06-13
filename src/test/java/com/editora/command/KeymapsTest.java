package com.editora.command;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Guards the bundled keymaps without a GUI: every keymap (incl. the macOS {@code .mac} variants) must
 * parse, reference only real command ids (cross-checked against the {@code command.<id>} i18n keys, the
 * registry-independent source of truth), use canonically-ordered chord tokens, and — for each GUI keymap
 * — bind the same command-id set on both platforms so no accelerator is silently dropped on one OS.
 */
class KeymapsTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Base + macOS-overlay file suffixes for the GUI keymaps (Emacs is single-file). */
    private static final List<String> GUI = List.of("cua", "sublime", "vscode", "intellij");

    /** Modifier prefixes in the exact order {@code KeyDispatcher.chord()} emits them. */
    private static final String[] MOD_ORDER = {"C-", "M-", "Cmd-", "S-"};

    private static Map<String, String> load(String resource) {
        try (InputStream in = KeymapsTest.class.getResourceAsStream(resource)) {
            assertNotNull(in, "missing keymap resource: " + resource);
            return MAPPER.readValue(in, new TypeReference<Map<String, String>>() {});
        } catch (Exception e) {
            throw new RuntimeException("failed to read " + resource, e);
        }
    }

    /** The command ids the app actually defines, derived from the {@code command.*} i18n keys. */
    private static Set<String> validCommandIds() {
        Properties props = new Properties();
        try (InputStream in = KeymapsTest.class.getResourceAsStream("/com/editora/i18n/messages.properties")) {
            assertNotNull(in, "missing base messages.properties");
            props.load(new InputStreamReader(in, StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        Set<String> ids = new HashSet<>();
        for (String key : props.stringPropertyNames()) {
            if (key.startsWith("command.")) {
                ids.add(key.substring("command.".length()));
            }
        }
        assertFalse(ids.isEmpty(), "no command.* i18n keys found");
        return ids;
    }

    private static List<String> allKeymapResources() {
        List<String> r = new java.util.ArrayList<>();
        r.add("/com/editora/keymaps/emacs.json");
        for (String id : GUI) {
            r.add("/com/editora/keymaps/" + id + ".json");
            r.add("/com/editora/keymaps/" + id + ".mac.json");
        }
        return r;
    }

    @Test
    void everyKeymapParsesAndBindsOnlyRealCommandIds() {
        Set<String> valid = validCommandIds();
        for (String resource : allKeymapResources()) {
            Map<String, String> map = load(resource);
            assertFalse(map.isEmpty(), resource + " is empty");
            map.forEach((chord, id) -> {
                assertFalse(id == null || id.isBlank(), resource + ": blank command id for " + chord);
                assertTrue(valid.contains(id), resource + ": unknown command id '" + id + "' (chord " + chord + ")");
            });
        }
    }

    @Test
    void chordTokensAreCanonicallyOrdered() {
        for (String resource : allKeymapResources()) {
            for (String sequence : load(resource).keySet()) {
                for (String token : sequence.split(" ")) {
                    assertFalse(token.isBlank(), resource + ": empty token in '" + sequence + "'");
                    String rest = token;
                    for (String mod : MOD_ORDER) {
                        if (rest.startsWith(mod)) {
                            rest = rest.substring(mod.length());
                        }
                    }
                    // After consuming modifiers in canonical order, no stray/out-of-order modifier may remain.
                    for (String mod : MOD_ORDER) {
                        if (rest.startsWith(mod)) {
                            fail(resource + ": token '" + token + "' has out-of-order/duplicate modifier; "
                                    + "expected order C- M- Cmd- S-");
                        }
                    }
                    assertFalse(rest.isEmpty(), resource + ": token '" + token + "' has no key");
                }
            }
        }
    }

    @Test
    void guiKeymapsBindSameCommandSetOnBothPlatforms() {
        for (String id : GUI) {
            Set<String> base =
                    new TreeSet<>(load("/com/editora/keymaps/" + id + ".json").values());
            Set<String> mac = new TreeSet<>(
                    load("/com/editora/keymaps/" + id + ".mac.json").values());
            assertTrue(
                    base.equals(mac),
                    id + ": base vs .mac command-id set drift\n  base-only=" + minus(base, mac) + "\n  mac-only="
                            + minus(mac, base));
        }
    }

    @Test
    void availableRegistryResolvesToLoadableKeymaps() {
        // Every advertised keymap id must load on both platform paths (mac path falls back to the base file).
        for (String id : KeymapManager.AVAILABLE.keySet()) {
            KeymapManager win = new KeymapManager();
            win.loadNamed(id, false);
            assertFalse(win.bindings().isEmpty(), id + ": empty on non-mac");
            KeymapManager mac = new KeymapManager();
            mac.loadNamed(id, true);
            assertFalse(mac.bindings().isEmpty(), id + ": empty on mac");
        }
    }

    private static Set<String> minus(Set<String> a, Set<String> b) {
        Set<String> r = new TreeSet<>(a);
        r.removeAll(b);
        return r;
    }
}

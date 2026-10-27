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

import static org.junit.jupiter.api.Assertions.assertEquals;
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
    void emacsToolWindowSizingBindingsStayOnTheirRequestedChords() {
        Map<String, String> emacs = load("/com/editora/keymaps/emacs.json");
        assertEquals("view.closeFocusedToolWindow", emacs.get("C-x 0"));
        assertEquals("view.resizeBottomToolWindow", emacs.get("C-x S-6"));
        assertEquals("view.resizeHorizontalToolWindowGreater", emacs.get("C-x S-."));
        assertEquals("view.resizeHorizontalToolWindowLess", emacs.get("C-x S-,"));
        assertEquals("view.maximizeToolWindow", emacs.get("C-x f"));
        assertFalse(emacs.containsKey("C-x S-f"));
    }

    /**
     * Chords are matched on the key code plus Shift, so a binding on US punctuation is unreachable where
     * that character sits elsewhere: on ES/DE/IT/PT keyboards "/" is Shift+7, which made {@code C-/} — the
     * Emacs keymap's only undo — impossible to type. Essential commands keep a layout-independent alias.
     */
    @Test
    void emacsUndoAndRedoHaveLayoutIndependentAliases() {
        KeymapManager km = new KeymapManager();
        km.loadNamed("emacs", false);
        assertEquals("edit.undo", km.commandFor("C-/"), "the original binding stays");
        assertEquals("edit.undo", km.commandFor("C-x u"));
        assertEquals("edit.undo", km.commandFor("C-S--"), "C-_ : Shift+minus is the underscore on every one");
        assertEquals("edit.undo", km.commandFor("C-S-7"), "what Ctrl+/ physically is where / is Shift+7");
        assertEquals("edit.redo", km.commandFor("C-M-S--"), "C-M-_");
        // The aliases must not shadow, or be shadowed by, a longer sequence.
        for (String alias : List.of("C-x u", "C-S--", "C-S-7", "C-M-S--")) {
            assertFalse(km.isPrefix(alias), alias + " must not also be a prefix");
        }
        assertTrue(km.isPrefix("C-x"), "C-x u continues the existing C-x prefix");
        assertEquals("edit.upcaseRegion", km.commandFor("C-x C-u"), "C-x u does not disturb C-x C-u");
        assertEquals("view.textZoomOut", km.commandFor("C--"), "C-_ does not disturb C-- (zoom out)");
    }

    @Test
    void guiKeymapsCanToggleCommentWithoutUsPunctuation() {
        for (String id : GUI) {
            Map<String, String> base = load("/com/editora/keymaps/" + id + ".json");
            assertEquals("edit.toggleComment", base.get("C-/"), id);
            assertEquals("edit.toggleComment", base.get("C-S-7"), id + ": Ctrl+/ where / is Shift+7");
            assertEquals("edit.toggleComment", base.get("C-divide"), id + ": the numpad slash");
            Map<String, String> mac = load("/com/editora/keymaps/" + id + ".mac.json");
            assertEquals("edit.toggleComment", mac.get("Cmd-S-7"), id + ".mac");
            assertEquals("edit.toggleComment", mac.get("Cmd-divide"), id + ".mac");
            // Undo and redo in these keymaps are letter chords, which every layout can type.
            for (Map<String, String> map : List.of(base, mac)) {
                for (String command : List.of("edit.undo", "edit.redo")) {
                    assertTrue(
                            map.entrySet().stream()
                                    .anyMatch(e -> e.getValue().equals(command)
                                            && e.getKey().matches("(C-|M-|Cmd-|S-)*[a-z]")),
                            id + ": " + command + " needs a letter chord");
                }
            }
        }
    }

    @Test
    void theNumpadSlashTokenIsWhatTheDispatcherEmits() {
        assertEquals(
                "C-divide",
                KeyDispatcher.chord(new javafx.scene.input.KeyEvent(
                        javafx.scene.input.KeyEvent.KEY_PRESSED,
                        "",
                        "",
                        javafx.scene.input.KeyCode.DIVIDE,
                        false,
                        true,
                        false,
                        false)));
        assertEquals(
                "C-S-7",
                KeyDispatcher.chord(new javafx.scene.input.KeyEvent(
                        javafx.scene.input.KeyEvent.KEY_PRESSED,
                        "",
                        "",
                        javafx.scene.input.KeyCode.DIGIT7,
                        true,
                        true,
                        false,
                        false)));
        assertEquals(
                "C-S--",
                KeyDispatcher.chord(new javafx.scene.input.KeyEvent(
                        javafx.scene.input.KeyEvent.KEY_PRESSED,
                        "",
                        "",
                        javafx.scene.input.KeyCode.MINUS,
                        true,
                        true,
                        false,
                        false)));
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

    /**
     * Every token's key-part must be something {@code KeyDispatcher.chord()} can actually emit — else the
     * binding is dead (nothing ever produces that token). {@code chord()} lowercases letters and expresses
     * Shift as a separate {@code S-} prefix, so a bare uppercase letter (e.g. {@code "L"}, should be
     * {@code "S-l"}) or a shifted-punctuation glyph (e.g. {@code "?"}, should be {@code "S-/"}) is never
     * produced. Guards the {@code M-?}/{@code M-g L} class of dead binding.
     */
    @Test
    void everyChordKeyPartIsProducibleByTheDispatcher() {
        String shiftGlyphs = "?!@#$%^&*()_+{}|:\"<>~";
        for (String resource : allKeymapResources()) {
            for (String sequence : load(resource).keySet()) {
                for (String token : sequence.split(" ")) {
                    String key = token;
                    for (String mod : MOD_ORDER) {
                        if (key.startsWith(mod)) {
                            key = key.substring(mod.length());
                        }
                    }
                    if (key.length() == 1) {
                        char c = key.charAt(0);
                        assertFalse(
                                c >= 'A' && c <= 'Z',
                                resource + ": token '" + token + "' uses uppercase '" + c
                                        + "' — chord() lowercases letters; use S-" + Character.toLowerCase(c));
                        assertFalse(
                                shiftGlyphs.indexOf(c) >= 0,
                                resource + ": token '" + token + "' uses shift-glyph '" + c
                                        + "' which chord() never emits (use S-<unshifted key>)");
                    }
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

    /**
     * Git chords are the ones each editor's users already know, and only those: Emacs' {@code vc} prefix
     * ({@code C-x v …}) and IntelliJ's commit / push / update / branches. VS Code, Sublime Text and plain CUA
     * define no chord for push, pull, fetch or switching branch, so none is invented for them.
     */
    @Test
    void gitChordsFollowEachEditorsOwnConvention() {
        Map<String, String> emacs = load("/com/editora/keymaps/emacs.json");
        assertEquals("git.commit", emacs.get("C-x v v")); // vc-next-action
        assertEquals("git.push", emacs.get("C-x v S-p")); // vc-push (C-x v P)
        assertEquals("git.pull", emacs.get("C-x v S-=")); // vc-update (C-x v +)
        assertEquals("git.switchBranch", emacs.get("C-x v b s")); // vc-switch-branch
        assertEquals("git.newBranch", emacs.get("C-x v b c")); // vc-create-branch
        assertEquals("git.fileHistory", emacs.get("C-x v l")); // vc-print-log
        assertEquals("tool.gitLog", emacs.get("C-x v S-l")); // vc-print-root-log (C-x v L)
        assertEquals("git.toggleBlame", emacs.get("C-x v g")); // vc-annotate
        assertEquals("diff.vsHead", emacs.get("C-x v ="));

        Map<String, String> idea = load("/com/editora/keymaps/intellij.json");
        assertEquals("tool.commit", idea.get("C-k"));
        assertEquals("git.push", idea.get("C-S-k"));
        assertEquals("git.pull", idea.get("C-t")); // Update Project
        assertEquals("git.switchBranch", idea.get("C-S-back-quote")); // Branches…
        Map<String, String> ideaMac = load("/com/editora/keymaps/intellij.mac.json");
        assertEquals("tool.commit", ideaMac.get("Cmd-k"));
        assertEquals("git.push", ideaMac.get("Cmd-S-k"));
        assertEquals("git.pull", ideaMac.get("Cmd-t"));
        assertEquals("git.switchBranch", ideaMac.get("C-S-back-quote"));
        // IntelliJ's macOS Generate is ⌘N in the editor; New File moves to ⌃⌘N (re-review EN7)
        assertEquals("lsp.generate", ideaMac.get("Cmd-n"));
        assertEquals("file.new", ideaMac.get("C-Cmd-n"));
        assertEquals("nav.back", ideaMac.get("Cmd-["));
        // Insert Live Template's chord gives Insert Snippet its own key back (third review ET5)
        assertEquals("snippets.insert", ideaMac.get("Cmd-j"));
        assertEquals(
                "snippets.insert", load("/com/editora/keymaps/intellij.json").get("C-j"));

        for (String id : List.of("cua", "sublime", "vscode")) {
            for (String suffix : List.of(".json", ".mac.json")) {
                Map<String, String> map = load("/com/editora/keymaps/" + id + suffix);
                for (String command : List.of("git.push", "git.pull", "git.fetch", "git.switchBranch")) {
                    assertFalse(map.containsValue(command), id + suffix + " invents a chord for " + command);
                }
            }
        }
    }

    /** A chord may not also be the prefix of a longer one — the longer binding could never be typed. */
    @Test
    void noChordIsAPrefixOfAnother() {
        for (String resource : allKeymapResources()) {
            Set<String> chords = load(resource).keySet();
            for (String chord : chords) {
                for (String other : chords) {
                    assertFalse(
                            other.startsWith(chord + " "),
                            resource + ": '" + chord + "' is bound and is also the prefix of '" + other + "'");
                }
            }
        }
    }

    @Test
    void applyOverridesBlankValueUnbinds() {
        KeymapManager km = new KeymapManager();
        km.loadNamed("emacs", false);
        assertTrue(km.commandFor("C-x C-s") != null, "precondition: emacs binds C-x C-s");
        km.applyOverrides(java.util.Map.of("C-x C-s", KeymapManager.UNBIND));
        assertTrue(km.commandFor("C-x C-s") == null, "blank override should unbind the chord");
        km.applyOverrides(java.util.Map.of("Cmd-s", "file.save"));
        assertTrue("file.save".equals(km.commandFor("Cmd-s")), "non-blank override should bind");
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

    /**
     * Both histories of a file have a chord in every bundled keymap, not only in Emacs: Local History's tool
     * window and Git's history of the file. No editor ships a default for either, so the GUI keymaps share one
     * mnemonic pair (H for history, one more modifier for Git's) that none of them uses for anything else.
     */
    @Test
    void everyKeymapBindsBothHistoriesOfAFile() {
        Map<String, String> emacs = load("/com/editora/keymaps/emacs.json");
        assertEquals("tool.fileHistory", emacs.get("M-g l"));
        assertEquals("git.fileHistory", emacs.get("C-x v l"));
        for (String id : GUI) {
            Map<String, String> base = load("/com/editora/keymaps/" + id + ".json");
            assertEquals("tool.fileHistory", base.get("M-S-h"), id);
            assertEquals("git.fileHistory", base.get("C-M-S-h"), id);
            Map<String, String> mac = load("/com/editora/keymaps/" + id + ".mac.json");
            assertEquals("tool.fileHistory", mac.get("C-Cmd-h"), id + ".mac");
            assertEquals("git.fileHistory", mac.get("C-Cmd-S-h"), id + ".mac");
        }
        for (String resource : allKeymapResources()) {
            Map<String, String> map = load(resource);
            for (String command : List.of("tool.fileHistory", "git.fileHistory")) {
                assertEquals(
                        1,
                        map.values().stream().filter(command::equals).count(),
                        resource + " binds " + command + " exactly once");
            }
        }
    }

    /**
     * Review C8: the CUA and Sublime keymaps had no debugging key at all. They now carry VS Code's F-keys (F11 is
     * Step Into while paused and Full Screen otherwise, as in VS Code), and every GUI keymap has the core set.
     */
    @Test
    void everyGuiKeymapCanStartStepAndToggleABreakpoint() {
        for (String id : List.of("cua", "sublime", "vscode")) {
            for (String variant : List.of(".json", ".mac.json")) {
                Map<String, String> km = load("/com/editora/keymaps/" + id + variant);
                assertEquals("debug.start", km.get("f5"), id + variant);
                assertEquals("debug.stop", km.get("S-f5"), id + variant);
                assertEquals("debug.toggleBreakpoint", km.get("f9"), id + variant);
                assertEquals("debug.stepOver", km.get("f10"), id + variant);
                assertEquals("debug.stepOut", km.get("S-f11"), id + variant);
                assertTrue(km.get("f11").startsWith("debug.stepInto"), id + variant);
            }
        }
        assertEquals(
                "debug.stepIntoOrFullScreen",
                load("/com/editora/keymaps/cua.json").get("f11"));
        assertEquals(
                "window.fullScreen", load("/com/editora/keymaps/cua.json").get("C-M-f"), "full screen keeps a key");
        assertEquals("debug.pause", load("/com/editora/keymaps/vscode.json").get("f6"));
        assertEquals("debug.restart", load("/com/editora/keymaps/intellij.json").get("C-f5"));
        assertEquals(
                "debug.evaluate", load("/com/editora/keymaps/intellij.json").get("M-f8"));
        assertEquals("debug.restart", load("/com/editora/keymaps/emacs.json").get("C-c C-d r"));
    }

    /**
     * The "IntelliJ IDEA" keymap binds IntelliJ's own Java chords (its {@code $default.xml}), the keys an IntelliJ
     * user tries first (Java review E1): Alt+Enter was dead, F2 renamed and Alt+Insert made a new file.
     */
    @org.junit.jupiter.api.Test
    void theIntelliJKeymapHasIntelliJsJavaChords() {
        KeymapManager km = new KeymapManager();
        km.loadNamed("intellij", false);
        java.util.Map<String, String> expected = new java.util.LinkedHashMap<>();
        expected.put("M-enter", "lsp.codeActions");
        expected.put("S-f6", "lsp.rename");
        expected.put("f2", "lsp.nextProblem");
        expected.put("S-f2", "lsp.previousProblem");
        expected.put("M-insert", "lsp.generate");
        expected.put("C-M-l", "lsp.formatDocument");
        expected.put("C-M-o", "lsp.organizeImports");
        expected.put("C-n", "lsp.gotoSymbol");
        expected.put("C-M-b", "lsp.gotoImplementation");
        expected.put("C-h", "lsp.typeHierarchy");
        expected.put("C-M-h", "lsp.callHierarchy");
        expected.put("C-p", "lsp.signatureHelp");
        expected.put("C-M-left", "nav.back");
        expected.put("C-M-v", "lsp.extractVariable");
        expected.put("C-M-m", "lsp.extractMethod");
        expected.put("f6", "lsp.move");
        expected.put("C-f6", "lsp.changeSignature");
        expected.put("C-M-S-t", "lsp.refactorThis");
        expected.forEach((chord, command) -> org.junit.jupiter.api.Assertions.assertEquals(
                command, km.commandFor(chord), "IntelliJ binds " + chord + " to " + command));
    }

    /** Back and Forward have a chord in every keymap, so Go to Definition has a way back (E2). */
    @org.junit.jupiter.api.Test
    void backAndForwardAreBoundEverywhere() {
        for (String name : java.util.List.of("emacs", "cua", "vscode", "intellij", "sublime")) {
            for (boolean mac : new boolean[] {false, true}) {
                KeymapManager km = new KeymapManager();
                km.loadNamed(name, mac);
                org.junit.jupiter.api.Assertions.assertNotNull(km.chordFor("nav.back"), name + (mac ? " mac" : ""));
                org.junit.jupiter.api.Assertions.assertNotNull(km.chordFor("nav.forward"), name + (mac ? " mac" : ""));
            }
        }
    }

    /** VS Code formats a document with Shift+Alt+F (Shift+Option+F on macOS); its keymap used to have no chord (XML C16). */
    @org.junit.jupiter.api.Test
    void vsCodeFormatsWithShiftAltF() {
        for (boolean mac : new boolean[] {false, true}) {
            KeymapManager km = new KeymapManager();
            km.loadNamed("vscode", mac);
            org.junit.jupiter.api.Assertions.assertEquals(
                    "lsp.formatDocument", km.commandFor("M-S-f"), "vscode" + (mac ? " mac" : ""));
        }
    }
}

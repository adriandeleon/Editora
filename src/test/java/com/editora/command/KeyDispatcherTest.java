package com.editora.command;

import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KeyDispatcherTest {

    private static KeyEvent press(KeyCode code, boolean shift, boolean ctrl, boolean alt, boolean meta) {
        return new KeyEvent(KeyEvent.KEY_PRESSED, "", "", code, shift, ctrl, alt, meta);
    }

    @Test
    void controlLetterChord() {
        assertEquals("C-x", KeyDispatcher.chord(press(KeyCode.X, false, true, false, false)));
    }

    @Test
    void altLetterIsMeta() {
        assertEquals("M-x", KeyDispatcher.chord(press(KeyCode.X, false, false, true, false)));
    }

    @Test
    void modifierOrderIsControlMetaShift() {
        assertEquals("C-S-p", KeyDispatcher.chord(press(KeyCode.P, true, true, false, false)));
    }

    @Test
    void specialKeyName() {
        assertEquals("C-/", KeyDispatcher.chord(press(KeyCode.SLASH, false, true, false, false)));
    }

    @Test
    void shiftedPunctuationIsSBaseKeyNotTheGlyph() {
        // "?" is Shift+SLASH → "S-/", never "?" (so the Emacs find-references binding must be "M-S-/").
        assertEquals("M-S-/", KeyDispatcher.chord(press(KeyCode.SLASH, true, false, true, false)));
    }

    @Test
    void numpadDigitProducesThePlainDigitToken() {
        // A numpad digit must map to "6" (matching the M-1…M-9 chords), not "Numpad 6" (unmatchable + a space).
        assertEquals("M-6", KeyDispatcher.chord(press(KeyCode.NUMPAD6, false, false, true, false)));
        assertEquals("M-6", KeyDispatcher.chord(press(KeyCode.DIGIT6, false, false, true, false)));
    }

    // --- KEY_TYPED swallow rule (a bound chord's char is eaten; an unbound Option glyph is not) ---

    private static KeyDispatcher dispatcher() {
        KeymapManager km = new KeymapManager();
        km.loadNamed("emacs", false); // C-t is bound to edit.transposeChars
        return new KeyDispatcher(new CommandRegistry(), km, s -> {});
    }

    private static KeyEvent typed(String ch, boolean alt) {
        return new KeyEvent(KeyEvent.KEY_TYPED, ch, "", KeyCode.UNDEFINED, false, false, alt, false);
    }

    @Test
    void pairedKeyTypedIsSwallowedAfterAConsumedChord() {
        KeyDispatcher d = dispatcher();
        KeyEvent p = press(KeyCode.T, false, true, false, false); // C-t
        d.handle(p);
        assertTrue(p.isConsumed(), "a bound chord consumes its press");
        KeyEvent t = typed("", false);
        d.handleTyped(t);
        assertTrue(t.isConsumed(), "the paired KEY_TYPED must still be swallowed so C-t doesn't also type");
    }

    @Test
    void releaseClearsTheSwallowWhenACommandChordHasNoTypedEvent() {
        KeyDispatcher d = dispatcher();
        KeyEvent p = press(KeyCode.T, false, true, false, false); // C-t
        d.handle(p);
        d.handleReleased(new KeyEvent(KeyEvent.KEY_RELEASED, "", "", KeyCode.T, false, false, false, false));
        KeyEvent next = typed("a", false);
        d.handleTyped(next);
        assertFalse(next.isConsumed(), "the first real character after a Command/Ctrl shortcut must survive");
    }

    @Test
    void normalTypedCharIsNotSwallowed() {
        KeyDispatcher d = dispatcher();
        KeyEvent p = press(KeyCode.A, false, false, false, false); // lone unbound 'a'
        d.handle(p);
        assertFalse(p.isConsumed(), "a lone unbound key falls through");
        KeyEvent t = typed("a", false);
        d.handleTyped(t);
        assertFalse(t.isConsumed(), "normal typing is not swallowed");
    }

    @Test
    void unboundOptionCharIsNotSwallowed() {
        // No consumed press → an Alt/Option-produced glyph (macOS accented/symbol input) must pass through.
        // The old code unconditionally ate it on macOS, breaking Option input.
        KeyDispatcher d = dispatcher();
        KeyEvent t = typed("ƒ", true); // e.g. Option+f producing "ƒ", but unbound
        d.handleTyped(t);
        assertFalse(t.isConsumed(), "an unbound Option/Alt character must reach the editor");
    }

    @Test
    void modifierOnlyEventHasNoChord() {
        assertNull(KeyDispatcher.chord(press(KeyCode.CONTROL, false, true, false, false)));
    }

    @Test
    void editorContextCommandsAreDeferredToFocusedToolWindows() {
        // Only caret/text commands are swallowed by a focused key-owning window…
        assertTrue(KeyDispatcher.isEditorContext("nav.lineDown"));
        assertTrue(KeyDispatcher.isEditorContext("edit.killLine"));
        // …jump/window/view commands stay global so they work while a tool window is focused.
        assertFalse(KeyDispatcher.isEditorContext("palette.show"));
        assertFalse(KeyDispatcher.isEditorContext("tool.project"));
        assertFalse(KeyDispatcher.isEditorContext("tool.jump"));
        assertFalse(KeyDispatcher.isEditorContext("view.toggleZen"));
        assertFalse(KeyDispatcher.isEditorContext(null));
    }

    // --- Windows/Linux Alt menu-mode guard (plainAltActive) ---

    @Test
    void plainAltOnWindowsIsGuarded() {
        // Left-Alt (Meta) held, no Ctrl, non-mac → consumed so Windows can't enter menu mode (which
        // otherwise freezes the keyboard on a bare or unbound Alt key).
        assertTrue(KeyDispatcher.plainAltActive(false, true, false));
    }

    @Test
    void macIsNeverGuarded() {
        // macOS uses Option as Meta and has no menu-activation problem.
        assertFalse(KeyDispatcher.plainAltActive(true, true, false));
    }

    @Test
    void altGrIsNotGuarded() {
        // AltGr is reported as Ctrl+Alt; guarding it would break international character composition,
        // so Alt-down WITH Ctrl-down must NOT be guarded.
        assertFalse(KeyDispatcher.plainAltActive(false, true, true));
    }

    @Test
    void noAltIsNotGuarded() {
        assertFalse(KeyDispatcher.plainAltActive(false, false, false)); // plain key
        assertFalse(KeyDispatcher.plainAltActive(false, false, true)); // Ctrl-only chord
    }

    // --- a focused text field keeps editor-context chords (the Find-bar C-k defect) ---

    @Test
    void aTextFieldKeepsEditorContextChordsButNotCancel() {
        // In a plain text field (not inside a key-owning window) caret/edit chords belong to the field…
        assertTrue(KeyDispatcher.leftToFocusOwner("edit.killLine", false, true));
        assertTrue(KeyDispatcher.leftToFocusOwner("edit.paste", false, true));
        assertTrue(KeyDispatcher.leftToFocusOwner("nav.lineStart", false, true));
        assertTrue(KeyDispatcher.leftToFocusOwner("edit.universalArgument", false, true));
        // …but cancel stays global, so C-g still closes the find bar / palette from inside their fields…
        assertFalse(KeyDispatcher.leftToFocusOwner(KeyDispatcher.CANCEL, false, true));
        // …and so does every non-editor command (find next, palette, tool windows).
        assertFalse(KeyDispatcher.leftToFocusOwner("find.show", false, true));
        assertFalse(KeyDispatcher.leftToFocusOwner("palette.show", false, true));
        assertFalse(KeyDispatcher.leftToFocusOwner(null, false, true));
    }

    @Test
    void aKeyOwningWindowKeepsEveryEditorContextChordIncludingCancel() {
        assertTrue(KeyDispatcher.leftToFocusOwner("nav.lineDown", true, false));
        assertTrue(KeyDispatcher.leftToFocusOwner(KeyDispatcher.CANCEL, true, false));
        assertTrue(KeyDispatcher.leftToFocusOwner(KeyDispatcher.CANCEL, true, true), "owning wins over the field rule");
        assertFalse(KeyDispatcher.leftToFocusOwner("palette.show", true, true));
    }

    @Test
    void theEditorItselfKeepsNothing() {
        assertFalse(KeyDispatcher.leftToFocusOwner("edit.killLine", false, false));
        assertFalse(KeyDispatcher.inTextInput(null));
        assertFalse(KeyDispatcher.inTextInput(new javafx.scene.layout.Region()), "a non-text node is not a field");
    }

    // --- AltGr (reported as Ctrl+Alt outside macOS) is typing, not a C-M- chord ---

    @Test
    void altGrTextNeedsTheAltGrKeyAndBothModifiers() {
        assertTrue(KeyDispatcher.altGrText(false, true, true, true));
        // Ctrl+LEFT Alt+letter (US layout, no AltGr key held) stays a chord.
        assertFalse(KeyDispatcher.altGrText(false, true, true, false));
        // A stale AltGr flag never turns a plain Alt or plain Ctrl chord into text.
        assertFalse(KeyDispatcher.altGrText(false, false, true, true));
        assertFalse(KeyDispatcher.altGrText(false, true, false, true));
        // macOS: Option is Meta; its glyphs are handled by the KEY_TYPED rule instead.
        assertFalse(KeyDispatcher.altGrText(true, true, true, true));
    }

    private static final class Recorder {
        final java.util.List<String> ran = new java.util.ArrayList<>();
        final KeyDispatcher dispatcher;

        Recorder(boolean mac) {
            KeymapManager km = new KeymapManager();
            km.loadNamed("emacs", mac); // C-M-e is nav.endOfDefun
            CommandRegistry registry = new CommandRegistry();
            registry.register(Command.of("nav.endOfDefun", "End of Defun", () -> ran.add("nav.endOfDefun")));
            dispatcher = new KeyDispatcher(registry, km, s -> {}, mac);
        }
    }

    @Test
    void altGrPlusLetterIsLeftToTextInputOnWindows() {
        Recorder r = new Recorder(false);
        // AltGr down: Windows sends a synthetic Ctrl and then the AltGr key itself.
        r.dispatcher.handle(press(KeyCode.CONTROL, false, true, false, false));
        r.dispatcher.handle(press(KeyCode.ALT_GRAPH, false, true, true, false));
        KeyEvent e = press(KeyCode.E, false, true, true, false); // AltGr+E: the euro sign on DE/ES/IT layouts
        r.dispatcher.handle(e);
        assertFalse(e.isConsumed(), "the press must fall through so its KEY_TYPED can type the character");
        assertTrue(r.ran.isEmpty(), "C-M-e (nav.endOfDefun) must not run");
        KeyEvent typed = new KeyEvent(KeyEvent.KEY_TYPED, "€", "", KeyCode.UNDEFINED, false, true, true, false);
        r.dispatcher.handleTyped(typed);
        assertFalse(typed.isConsumed(), "the composed character must reach the focused control");
    }

    @Test
    void controlLeftAltLetterStillDispatchesItsChord() {
        Recorder r = new Recorder(false);
        r.dispatcher.handle(press(KeyCode.CONTROL, false, true, false, false));
        r.dispatcher.handle(press(KeyCode.ALT, false, true, true, false)); // Left Alt, not AltGr
        KeyEvent e = press(KeyCode.E, false, true, true, false);
        r.dispatcher.handle(e);
        assertTrue(e.isConsumed());
        assertEquals(java.util.List.of("nav.endOfDefun"), r.ran, "C-M-e on a US layout is unchanged");
    }

    @Test
    void releasingAltGrRestoresControlAltChords() {
        Recorder r = new Recorder(false);
        r.dispatcher.handle(press(KeyCode.ALT_GRAPH, false, true, true, false));
        r.dispatcher.handleReleased(
                new KeyEvent(KeyEvent.KEY_RELEASED, "", "", KeyCode.ALT_GRAPH, false, false, false, false));
        r.dispatcher.handle(press(KeyCode.E, false, true, true, false));
        assertEquals(java.util.List.of("nav.endOfDefun"), r.ran);
    }

    @Test
    void aMissedAltGrReleaseDoesNotOutliveTheNextPlainKey() {
        Recorder r = new Recorder(false);
        r.dispatcher.handle(press(KeyCode.ALT_GRAPH, false, true, true, false));
        // The release went to another window. Any key pressed without Alt proves AltGr is no longer held.
        r.dispatcher.handle(press(KeyCode.A, false, false, false, false));
        r.dispatcher.handle(press(KeyCode.E, false, true, true, false));
        assertEquals(java.util.List.of("nav.endOfDefun"), r.ran);
    }

    @Test
    void onMacOptionControlChordsAreNeverMistakenForAltGr() {
        Recorder r = new Recorder(true);
        r.dispatcher.handle(press(KeyCode.ALT_GRAPH, false, true, true, false));
        r.dispatcher.handle(press(KeyCode.E, false, true, true, false));
        assertEquals(java.util.List.of("nav.endOfDefun"), r.ran);
    }

    // --- a component can claim a bare key over a global binding (the Project tree's F2 = rename file) ---

    private static KeyEvent pressAt(javafx.scene.Node target, KeyCode code) {
        return new KeyEvent(target, target, KeyEvent.KEY_PRESSED, "", "", code, false, false, false, false);
    }

    @Test
    void aClaimedKeyIsLeftToItsComponentEvenWhenBoundGlobally() {
        KeymapManager km = new KeymapManager();
        km.loadNamed("vscode", false); // F2 is lsp.rename — a global, non-editor-context command
        assertEquals("lsp.rename", km.commandFor("f2"));
        java.util.List<String> ran = new java.util.ArrayList<>();
        CommandRegistry registry = new CommandRegistry();
        registry.register(Command.of("lsp.rename", "Rename Symbol", () -> ran.add("lsp.rename")));
        KeyDispatcher d = new KeyDispatcher(registry, km, s -> {}, false);

        javafx.scene.layout.Region tree = new javafx.scene.layout.Region();
        tree.getProperties().put(KeyDispatcher.CLAIMED_KEYS, java.util.Set.of("f2", "delete"));
        javafx.scene.layout.Region cell = new javafx.scene.layout.Region();
        javafx.scene.layout.Pane holder = new javafx.scene.layout.Pane(cell);
        tree.getProperties().put("holder", holder); // unrelated properties do not confuse the lookup

        KeyEvent inTree = pressAt(tree, KeyCode.F2);
        d.handle(inTree);
        assertFalse(inTree.isConsumed(), "F2 in the tree is the tree's key");
        assertTrue(ran.isEmpty(), "…so the global lsp.rename must not run");

        KeyEvent elsewhere = pressAt(cell, KeyCode.F2); // a node with no claiming ancestor
        d.handle(elsewhere);
        assertTrue(elsewhere.isConsumed());
        assertEquals(java.util.List.of("lsp.rename"), ran, "everywhere else F2 is still the keymap's command");
    }

    @Test
    void aClaimOnlyCoversTheKeysItNames() {
        KeymapManager km = new KeymapManager();
        km.loadNamed("vscode", false);
        java.util.List<String> ran = new java.util.ArrayList<>();
        CommandRegistry registry = new CommandRegistry();
        registry.register(Command.of("palette.show", "Palette", () -> ran.add("palette.show")));
        KeyDispatcher d = new KeyDispatcher(registry, km, s -> {}, false);
        javafx.scene.layout.Region tree = new javafx.scene.layout.Region();
        tree.getProperties().put(KeyDispatcher.CLAIMED_KEYS, java.util.Set.of("delete"));
        d.handle(pressAt(tree, KeyCode.F1)); // F1 = palette.show in the VS Code keymap, not claimed
        assertEquals(java.util.List.of("palette.show"), ran);
    }

    /** A transient list over the editor takes a chord only while the keymap binds it to the command it stands in for. */
    @Test
    void ownedChordsYieldOnlyTheNamedChordBoundToTheNamedCommand() {
        var chords = java.util.Map.of("C-n", "nav.lineDown", "C-g", "edit.cancel");
        assertTrue(KeyDispatcher.chordOwned(chords, "C-n", "nav.lineDown"));
        assertTrue(KeyDispatcher.chordOwned(chords, "C-g", "edit.cancel"));
        assertFalse(KeyDispatcher.chordOwned(chords, "C-a", "nav.lineStart"), "every other chord stays on the keymap");
        assertFalse(KeyDispatcher.chordOwned(chords, "C-g", "nav.goToLine"), "same chord, another keymap's meaning");
        assertFalse(KeyDispatcher.chordOwned(chords, "C-n", null));
        assertFalse(KeyDispatcher.chordOwned(null, "C-n", "nav.lineDown"));
        assertFalse(KeyDispatcher.chordOwned(Boolean.TRUE, "C-n", "nav.lineDown"));
    }
}

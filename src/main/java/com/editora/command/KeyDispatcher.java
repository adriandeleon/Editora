package com.editora.command;

import java.util.Locale;
import java.util.function.Consumer;

import javafx.event.EventTarget;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.ComboBoxBase;
import javafx.scene.control.Spinner;
import javafx.scene.control.TextInputControl;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;

/**
 * Translates key presses into chord tokens and dispatches them to commands via the keymap.
 * Supports multi-key Emacs chords (e.g. {@code C-x C-s}) using a pending-prefix buffer.
 */
public class KeyDispatcher {

    /** Whether this dispatcher runs on macOS (Option is Meta there; no Alt menu mode, no AltGr-as-Ctrl+Alt). */
    private final boolean mac;

    private final CommandRegistry registry;
    private final KeymapManager keymap;
    private final Consumer<String> statusListener;
    /** The keyboard-macro machinery, if any: told what to record, asked when to stand aside. */
    private MacroCapture capture;

    private String pending = "";
    /** True when the last KEY_PRESSED was consumed, so its paired KEY_TYPED is swallowed too. */
    private boolean consumedPress;
    /** True while the AltGr key itself is held (it arrives as {@link KeyCode#ALT_GRAPH}); see {@link #altGrText}. */
    private boolean altGrHeld;
    /** True while an unbound plain-Alt press is on its way to the component that claimed it; see {@link #consumeClaimedAlt}. */
    private boolean claimedAltPress;

    /** The Emacs prefix (universal) argument being entered, if any. See {@link #handle}. */
    private final PrefixArg prefixArg = new PrefixArg();
    /** How many times the next {@code KEY_TYPED} self-insert character is repeated ({@code C-u 40 -}), or 0. */
    private int pendingSelfInsert;
    /** Which command ids read the argument themselves rather than being repeated (e.g. {@code C-u C-SPC}). */
    private java.util.function.Predicate<String> countAware = id -> false;
    /** Stashes the numeric argument for a count-aware command to read (null clears it). */
    private Consumer<Integer> prefixSink = n -> {};
    /** Inserts a self-inserted character N times ({@code C-u 40 -}); null disables self-insert repeat. */
    private java.util.function.ObjIntConsumer<Character> selfInsert;
    /** A runaway {@code C-u 9999999} can't hang the UI: repeats and self-inserts are clamped to this. */
    static final int MAX_REPEAT = 100_000;

    /** The command id {@code C-u} maps to (bound per keymap, so only Emacs starts a prefix argument). */
    public static final String UNIVERSAL_ARGUMENT = "edit.universalArgument";
    /** Optional first-look hook: given the chord token + event target, returns true if it handled the
     *  key (then the event is consumed and dispatch stops). Used e.g. to let {@code M-g} close a
     *  focused tool window. Only consulted when no multi-key prefix is pending. */
    private java.util.function.BiPredicate<String, EventTarget> preDispatch;

    public KeyDispatcher(CommandRegistry registry, KeymapManager keymap, Consumer<String> statusListener) {
        this(registry, keymap, statusListener, KeymapManager.isMac());
    }

    /** Package-visible variant with an explicit platform flag so tests don't depend on the host OS. */
    KeyDispatcher(CommandRegistry registry, KeymapManager keymap, Consumer<String> statusListener, boolean mac) {
        this.mac = mac;
        this.registry = registry;
        this.keymap = keymap;
        this.statusListener = statusListener != null ? statusListener : s -> {};
    }

    /** Installs a first-look hook (see {@link #preDispatch}); may be null to clear. */
    public void setPreDispatch(java.util.function.BiPredicate<String, EventTarget> hook) {
        this.preDispatch = hook;
    }

    /**
     * Wires the prefix-argument ({@code C-u}) support. {@code countAware} names the command ids that read
     * the numeric argument themselves rather than being repeated (via {@code sink}); every other command is
     * simply run |value| times. {@code selfInsert} inserts a self-inserting character N times ({@code C-u 40
     * -}). Any may be null to disable that part. The trigger chord is the keymap binding for
     * {@link #UNIVERSAL_ARGUMENT}, so a keymap that does not bind it has no prefix argument at all.
     */
    public void setPrefixArgumentSupport(
            java.util.function.Predicate<String> countAware,
            Consumer<Integer> sink,
            java.util.function.ObjIntConsumer<Character> selfInsert) {
        this.countAware = countAware != null ? countAware : id -> false;
        this.prefixSink = sink != null ? sink : n -> {};
        this.selfInsert = selfInsert;
    }

    /**
     * Installs the keyboard-macro hooks (see {@link MacroCapture}); may be null to clear. While recording,
     * the dispatcher reports typed text and every key press it leaves to the focused control — with the
     * event's target, so the recorder can tell the document from a prompt. While a replay delivers its own
     * key events, the dispatcher ignores them entirely.
     */
    public void setMacroCapture(MacroCapture capture) {
        this.capture = capture;
    }

    public void install(Scene scene) {
        scene.addEventFilter(KeyEvent.KEY_PRESSED, this::handle);
        scene.addEventHandler(KeyEvent.KEY_PRESSED, this::consumeClaimedAlt);
        scene.addEventFilter(KeyEvent.KEY_TYPED, this::handleTyped);
        scene.addEventFilter(KeyEvent.KEY_RELEASED, this::handleReleased);
        scene.addEventFilter(javafx.scene.input.InputMethodEvent.INPUT_METHOD_TEXT_CHANGED, this::handleInputMethod);
    }

    /** Text committed by an input method never arrives as KEY_TYPED, so a macro has to be told separately. */
    void handleInputMethod(javafx.scene.input.InputMethodEvent event) {
        MacroCapture cap = capture;
        if (cap != null && cap.mode() == MacroCapture.RECORDING) {
            String committed = event.getCommitted();
            if (committed != null && !committed.isEmpty()) {
                cap.text(committed, event.getTarget());
            }
        }
    }

    /**
     * The other half of the Windows menu-mode guard for a <em>claimed</em> plain-Alt chord (see
     * {@link #CLAIMED_KEYS}): the press was left to the component that claimed it, which normally consumes
     * it. If it comes back up to the scene unconsumed (the component had nothing to do with it just now), it
     * is consumed here, before the scene's mnemonic handling and the native menu can see it.
     */
    void consumeClaimedAlt(KeyEvent event) {
        if (claimedAltPress) {
            claimedAltPress = false;
            event.consume();
        }
    }

    /** The macro mode for this event; {@link MacroCapture#IDLE} without a capture. */
    private int macroMode() {
        MacroCapture cap = capture;
        return cap == null ? MacroCapture.IDLE : cap.mode();
    }

    /**
     * Drops a stale typed-event swallow once the handled physical key is released. Control/Command
     * shortcuts commonly emit no {@code KEY_TYPED} at all (notably on macOS); without this reset the first
     * ordinary character typed into a picker opened by such a shortcut is mistaken for the missing paired
     * event and disappears. An Option chord that does produce a glyph delivers KEY_TYPED before release,
     * so it is still swallowed by {@link #handleTyped} as intended.
     */
    void handleReleased(KeyEvent event) {
        int macro = macroMode();
        if (macro == MacroCapture.SYNTHETIC) {
            return; // a replayed key: none of the dispatcher's state is about it
        }
        if (macro == MacroCapture.REPLAYING) {
            event.consume();
            return;
        }
        consumedPress = false;
        if (event.getCode() == KeyCode.ALT_GRAPH) {
            altGrHeld = false;
        }
        if (!mac) {
            suppressMenuAlt(event);
        }
    }

    /** Consumes a bare {@code Alt} release so Windows doesn't enter system-menu mode on an Alt tap. */
    private static void suppressMenuAlt(KeyEvent event) {
        if (event.getCode() == KeyCode.ALT) {
            event.consume();
        }
    }

    /**
     * Whether <em>plain</em> Alt (Left-Alt as Meta, not AltGr) is active on a non-macOS platform — the
     * case where an otherwise-unhandled key must be consumed to avoid Windows menu activation. AltGr is
     * reported as Ctrl+Alt, so requiring Alt down <em>and</em> Ctrl up excludes it (international AltGr
     * typing and Ctrl+Alt chords are unaffected). macOS is never affected (Option = Meta). Pure — tested.
     */
    static boolean plainAltActive(boolean isMac, boolean altDown, boolean controlDown) {
        return !isMac && altDown && !controlDown;
    }

    /**
     * Whether a key press with Ctrl+Alt down is <em>AltGr typing a character</em> rather than a {@code C-M-}
     * chord. Windows reports AltGr as Ctrl+Alt, so on a German or Spanish layout AltGr+E (the euro sign)
     * looked exactly like {@code C-M-e}: the command ran and the character was thrown away.
     *
     * <p>The modifier flags cannot tell the two apart, but the key can: AltGr is reported as its own key
     * code ({@link KeyCode#ALT_GRAPH}), Left Alt as {@link KeyCode#ALT}. So while the AltGr key is held, a
     * Ctrl+Alt press is text input; Ctrl+<b>Left</b>Alt+letter still dispatches its chord. The character
     * the press carries is deliberately not consulted — it is the key's unmodified character on Windows and
     * a different script's letter on a Cyrillic or Greek layout, so it would misfire both ways. macOS never
     * qualifies: Option is Meta there, and its glyphs are handled in {@link #handleTyped}. Pure — tested.
     */
    static boolean altGrText(boolean isMac, boolean controlDown, boolean altDown, boolean altGrHeld) {
        return !isMac && controlDown && altDown && altGrHeld;
    }

    /**
     * Swallows the character event that pairs with a key press we already handled, so a bound key
     * never also types a character. This matters when the command opened a modal dialog
     * ({@code showAndWait}): the press is consumed, but its KEY_TYPED is queued and delivered to the
     * editor after the dialog closes (e.g. the trailing {@code g} of {@code M-g g}). A bound Option/Meta
     * chord on macOS is already covered: {@code handle()} consumes it and sets {@code consumedPress}, so its
     * glyph ({@code M-f} => "ƒ") is swallowed here too. We must NOT swallow an <em>unbound</em> Option
     * character, though — that would break macOS Option-based accented/symbol input (é, ç, ∞, dead keys).
     */
    void handleTyped(KeyEvent event) {
        int macro = macroMode();
        if (macro == MacroCapture.SYNTHETIC) {
            return; // a replayed character: leave consumedPress for the replay chord's own KEY_TYPED
        }
        if (macro == MacroCapture.REPLAYING) {
            event.consume(); // typing must not interleave with a replay that is still running
            return;
        }
        if (consumedPress) {
            consumedPress = false;
            event.consume();
            return;
        }
        // Prefix-argument self-insert (C-u 40 -): the press left a repeat count for this character. Insert it
        // that many times and swallow the event so the area does not also type a single copy.
        if (pendingSelfInsert > 0) {
            int count = pendingSelfInsert;
            pendingSelfInsert = 0;
            event.consume();
            String s = event.getCharacter();
            if (selfInsert != null && s != null && !s.isEmpty()) {
                selfInsert.accept(s.charAt(0), count);
                if (macro == MacroCapture.RECORDING && isRecordableText(s)) {
                    // The repeat is typing like any other: without this, C-u 3 x vanished from the macro.
                    capture.text(s.substring(0, 1).repeat(count), event.getTarget());
                }
            }
            return;
        }
        // A genuine character on its way to the focused control — feed it to the macro recorder (if any),
        // which decides from the target whether it is the document's, a prompt's, or nobody's business.
        // A character that arrives with Control or Command held (and is not AltGr, which sets Alt too) is a
        // shortcut's by-product, not typing: the focused control ignores it, so the macro must as well.
        if (macro == MacroCapture.RECORDING && !event.isMetaDown() && (!event.isControlDown() || event.isAltDown())) {
            String s = event.getCharacter();
            if (isRecordableText(s)) {
                capture.text(s, event.getTarget());
            }
        }
    }

    /**
     * Typed text worth recording in a macro: printable characters. Enter, Tab, Backspace and Escape also
     * deliver a control character here, but those are recorded as the <em>key</em> (see
     * {@link #isActionKey}) — a tab character cannot say whether it was Tab or Shift+Tab, nor replay a
     * snippet expansion.
     */
    static boolean isRecordableText(String s) {
        if (s == null || s.isEmpty()) {
            return false;
        }
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < 0x20 || c == 0x7F) {
                return false;
            }
        }
        return true;
    }

    void handle(KeyEvent event) {
        int macro = macroMode();
        if (macro == MacroCapture.SYNTHETIC) {
            return; // a replayed key goes straight to its target; see MacroCapture.SYNTHETIC
        }
        if (macro == MacroCapture.REPLAYING) {
            // A replay is running in slices: keep real keys out of it, except the one that stops it.
            event.consume();
            consumedPress = true;
            String cancel = chord(event);
            if (event.getCode() == KeyCode.ESCAPE || (cancel != null && CANCEL.equals(keymap.commandFor(cancel)))) {
                capture.cancelReplay();
            }
            return;
        }
        if (macro == MacroCapture.RECORDING) {
            capture.keySeen();
        }
        consumedPress = false;
        claimedAltPress = false;
        // Bare Alt: consume so Windows can't enter menu mode (which freezes the keyboard). Plain Alt
        // only — AltGr (Ctrl+Alt) is left alone (see plainAltActive).
        if (event.getCode() == KeyCode.ALT && plainAltActive(mac, event.isAltDown(), event.isControlDown())) {
            event.consume();
            return;
        }
        if (event.getCode() == KeyCode.ALT_GRAPH) {
            altGrHeld = true;
        } else if (!event.isAltDown()) {
            altGrHeld = false; // a missed release (focus left the window) must not outlive the next plain key
        }
        String token = chord(event);
        if (token == null) {
            return; // a modifier key on its own
        }
        // AltGr composing a character (reported as Ctrl+Alt outside macOS): this is typing, not a C-M- chord.
        // Leave the press alone so its KEY_TYPED delivers the character to whatever is focused.
        if (pending.isEmpty() && altGrText(mac, event.isControlDown(), event.isAltDown(), altGrHeld)) {
            if (prefixArg.isActive()) {
                prefixArg.reset();
                statusListener.accept("");
            }
            return;
        }
        // While a prefix argument is being entered (C-u …), intercept its continuation keys — digits and a
        // leading minus accumulate, C-g/Escape cancels. Anything else falls through to be the command (or
        // self-inserted char) the argument applies to. C-u itself is resolved via the keymap below.
        if (prefixArg.isActive()) {
            if (token.equals("C-g") || token.equals("escape")) {
                cancelPrefixArgument();
                consumeAsArg(event);
                return;
            }
            Integer d = plainDigit(token);
            if (d != null) {
                prefixArg.digit(d);
                statusListener.accept(prefixArg.describe());
                consumeAsArg(event);
                return;
            }
            if (token.equals("-") && !prefixArg.hasDigits()) {
                prefixArg.negate();
                statusListener.accept(prefixArg.describe());
                consumeAsArg(event);
                return;
            }
        }
        if (pending.isEmpty() && preDispatch != null && preDispatch.test(token, event.getTarget())) {
            event.consume();
            consumedPress = true; // swallow the paired KEY_TYPED
            return;
        }
        String sequence = pending.isEmpty() ? token : pending + " " + token;

        String commandId = keymap.commandFor(sequence);
        boolean prefix = keymap.isPrefix(sequence);

        // A window that owns its keys (e.g. a tool window) handles only the editor navigation/edit
        // chords it repurposes for local navigation (C-n/C-p, C-f/C-b, …) — those are left to it.
        // Everything else stays global so jump/window commands (M-x, M-1, M-g …) and prefixes (C-x …)
        // work even while a tool window is focused. A focused text field gets the same treatment without
        // having to opt in: its caret is not the document's, so C-k / Ctrl+V / Ctrl+Z typed there must edit
        // the field, never the buffer behind it.
        if (pending.isEmpty()
                && !prefix
                && isEditorContext(commandId) // checked first: plain typing must not walk the ancestor chain
                && (leftToFocusOwner(commandId, ownsKeys(event.getTarget()), inTextInput(event.getTarget()))
                        || ownsChord(event.getTarget(), token, commandId))) {
            if (macro == MacroCapture.RECORDING) {
                capture.key(event, event.getTarget()); // the focus owner acts on it, so only the key replays it
            }
            return; // let the focused window or field handle this editor-context key
        }
        // A key the focused component declared its own (the Project tree's F2 = rename file) wins over a
        // global binding of the same key (F2 = rename symbol in the VS Code/Sublime/IntelliJ keymaps).
        if (pending.isEmpty() && (commandId != null || prefix) && claimsKey(event.getTarget(), token)) {
            if (macro == MacroCapture.RECORDING) {
                capture.key(event, event.getTarget());
            }
            return;
        }

        // C-u (universal-argument): start or extend the prefix argument instead of running a command.
        if (UNIVERSAL_ARGUMENT.equals(commandId)) {
            prefixArg.universal();
            statusListener.accept(prefixArg.describe());
            consumeAsArg(event);
            return;
        }

        if (commandId != null) {
            event.consume();
            consumedPress = true; // set before run(): a modal command defers the paired KEY_TYPED
            reset();
            dispatch(commandId);
            return;
        }

        if (prefix) {
            event.consume();
            consumedPress = true;
            pending = sequence;
            statusListener.accept(keymap.display(sequence) + " -");
            return;
        }

        if (!pending.isEmpty()) {
            // Mid-chord but no continuation matched: cancel the chord and swallow the key.
            event.consume();
            consumedPress = true;
            statusListener.accept(keymap.display(sequence) + " is undefined");
            reset();
            prefixArg.reset();
            return;
        }
        // Prefix argument + a self-inserting character (C-u 40 -): let the paired KEY_TYPED do the insert N
        // times. We do NOT consume the press — the character arrives on KEY_TYPED, which handleTyped()
        // repeats and swallows. Digits/minus were already intercepted above, so they can't reach here.
        if (prefixArg.isActive() && selfInsert != null && selfInsertCandidate(event)) {
            pendingSelfInsert = clampRepeat(prefixArg.value());
            prefixArg.reset();
            statusListener.accept("");
            return;
        }
        // Windows/Linux: an UNBOUND plain-Alt chord (e.g. M-n with no binding) must still be consumed.
        // If it falls through, Windows treats the Alt+<key> as a menu mnemonic, enters native menu mode,
        // and the keyboard freezes app-wide (mouse still works) until restart — the reported bug. AltGr
        // (Ctrl+Alt) is excluded by plainAltActive, so international layouts keep composing characters.
        if (plainAltActive(mac, event.isAltDown(), event.isControlDown())) {
            prefixArg.reset();
            // …unless the focused component claimed this very chord (the Project Map's Alt+Left/Right
            // history). It gets the press and consumes it; consumeClaimedAlt() does so if it does not.
            if (claimsKey(event.getTarget(), token)) {
                claimedAltPress = true;
                if (macro == MacroCapture.RECORDING) {
                    capture.key(event, event.getTarget());
                }
                return;
            }
            event.consume();
            consumedPress = true;
            return;
        }
        // A stray unbound key (not a self-insert candidate) ends any pending argument rather than leaving it
        // to apply to some later, unrelated command.
        if (prefixArg.isActive()) {
            prefixArg.reset();
            statusListener.accept("");
        }
        // A lone, unbound key: let it fall through so normal text input works. If it's a key that acts
        // rather than types, hand it to the macro recorder first — the focused control handles these itself,
        // so this is the only place they can be captured.
        if (macro == MacroCapture.RECORDING && isActionKey(event)) {
            capture.key(event, event.getTarget());
        }
    }

    /** Consumes a key event that was absorbed into the prefix argument (C-u, a digit, a minus, a cancel). */
    private void consumeAsArg(KeyEvent event) {
        event.consume();
        consumedPress = true; // swallow the paired KEY_TYPED so the digit/char is not also inserted
    }

    private void cancelPrefixArgument() {
        prefixArg.reset();
        reset(); // also drop any half-entered multi-key chord (C-u C-x C-g)
        statusListener.accept("");
    }

    /**
     * Runs {@code commandId}, applying any prefix argument: a count-aware command reads the numeric value
     * (via the sink) and runs once; every other command is repeated |value| times. Without an argument it
     * runs exactly once — the normal path.
     */
    private void dispatch(String commandId) {
        if (!prefixArg.isActive()) {
            registry.run(commandId);
            return;
        }
        int value = prefixArg.value();
        prefixArg.reset(); // clear before running so a nested dispatch starts clean
        statusListener.accept("");
        if (countAware.test(commandId)) {
            prefixSink.accept(value);
            try {
                registry.run(commandId);
            } finally {
                prefixSink.accept(null);
            }
        } else {
            int n = clampRepeat(value);
            for (int i = 0; i < n; i++) {
                registry.run(commandId);
            }
        }
    }

    /** The bounded, non-negative repeat count for a raw argument value (0 stays 0 → a no-op). */
    static int clampRepeat(int value) {
        return Math.min(Math.abs(value), MAX_REPEAT);
    }

    /** A bare single digit token ({@code "0"}…{@code "9"}, no modifiers), else null. */
    static Integer plainDigit(String token) {
        if (token.length() == 1) {
            char c = token.charAt(0);
            if (c >= '0' && c <= '9') {
                return c - '0';
            }
        }
        return null;
    }

    /** Whether this key would type a character (so a prefix argument repeats it): no C-/M-/Cmd- modifier. */
    private static boolean selfInsertCandidate(KeyEvent event) {
        if (event.isControlDown() || event.isAltDown() || event.isMetaDown()) {
            return false;
        }
        KeyCode c = event.getCode();
        return c == KeyCode.SPACE || c.isLetterKey() || c.isDigitKey() || SELF_INSERT_PUNCT.contains(c);
    }

    private static final java.util.Set<KeyCode> SELF_INSERT_PUNCT = java.util.Set.of(
            KeyCode.SLASH,
            KeyCode.BACK_SLASH,
            KeyCode.PERIOD,
            KeyCode.COMMA,
            KeyCode.SEMICOLON,
            KeyCode.MINUS,
            KeyCode.EQUALS,
            KeyCode.OPEN_BRACKET,
            KeyCode.CLOSE_BRACKET,
            KeyCode.QUOTE,
            KeyCode.BACK_QUOTE);

    /**
     * Whether an unbound key press is one a macro must record as a <em>key</em>: it acts on the focused
     * control instead of typing a character there, and no bundled keymap binds it — so neither the command
     * hook nor the typed-text hook ever sees it. Backspace, Delete, the arrows and Home/End edit and move;
     * Enter, Tab and Escape accept, indent, traverse and dismiss (and expand a snippet, pick a completion,
     * move between table cells — which is why they are keys and not the characters they also deliver);
     * Insert is Shift+Insert paste and Ctrl+Insert copy. With Control or Command held, any key counts: the
     * control's own shortcut (Ctrl+A in a text field) is not a command either. Pure.
     */
    static boolean isActionKey(KeyEvent event) {
        KeyCode code = event.getCode();
        if (code == null || code.isModifierKey()) {
            return false;
        }
        return ACTION_KEYS.contains(code) || event.isControlDown() || event.isMetaDown();
    }

    private static final java.util.Set<KeyCode> ACTION_KEYS = java.util.EnumSet.of(
            KeyCode.BACK_SPACE,
            KeyCode.DELETE,
            KeyCode.INSERT,
            KeyCode.ENTER,
            KeyCode.TAB,
            KeyCode.ESCAPE,
            KeyCode.LEFT,
            KeyCode.RIGHT,
            KeyCode.UP,
            KeyCode.DOWN,
            KeyCode.KP_LEFT,
            KeyCode.KP_RIGHT,
            KeyCode.KP_UP,
            KeyCode.KP_DOWN,
            KeyCode.HOME,
            KeyCode.END,
            KeyCode.PAGE_UP,
            KeyCode.PAGE_DOWN);

    /**
     * True if the event's target (or any ancestor) opts out of global key dispatch via the
     * {@code editora.ownsKeys} node property. Such components (e.g. the Structure tool window)
     * implement their own keyboard handling and must receive raw key events, including bound chords.
     */
    /**
     * Editor-context commands — caret movement and text manipulation — are the only bound chords a
     * focused key-owning window swallows (it reuses them for its own navigation). Jump/window/view
     * commands stay global. Keyed by id prefix: {@code nav.*} (caret) and {@code edit.*} (text).
     */
    static boolean isEditorContext(String commandId) {
        return commandId != null && (commandId.startsWith("nav.") || commandId.startsWith("edit."));
    }

    /** The one editor-context command a bare text field does not keep: it dismisses chrome, it edits nothing. */
    public static final String CANCEL = "edit.cancel";

    /**
     * Whether a bound single chord is left to whatever is focused instead of being run as a command. A
     * key-owning window keeps every editor-context chord. A text field outside such a window keeps them too
     * — except {@link #CANCEL}, which stays global so {@code C-g} still closes the find bar or the palette
     * from inside their fields. Pure — tested.
     */
    static boolean leftToFocusOwner(String commandId, boolean ownsKeys, boolean textInput) {
        if (!isEditorContext(commandId)) {
            return false;
        }
        return ownsKeys || (textInput && !CANCEL.equals(commandId));
    }

    /**
     * True if the event is aimed at a text-entry control — a {@link TextInputControl}, or an editable combo
     * box / spinner, whose inner field receives the keys the control is sent. The editor itself is a
     * RichTextFX area, not a {@code TextInputControl}, so it never matches. Decided here rather than by an
     * opt-in property so a text field added to the main scene later cannot forget it.
     */
    static boolean inTextInput(EventTarget target) {
        Node node = target instanceof Node n ? n : null;
        while (node != null) {
            if (node instanceof TextInputControl
                    || (node instanceof ComboBoxBase<?> combo && combo.isEditable())
                    || (node instanceof Spinner<?> spinner && spinner.isEditable())) {
                return true;
            }
            node = node.getParent();
        }
        return false;
    }

    private static boolean ownsKeys(EventTarget target) {
        Node node = target instanceof Node n ? n : null;
        while (node != null) {
            if (node.hasProperties() && Boolean.TRUE.equals(node.getProperties().get("editora.ownsKeys"))) {
                return true;
            }
            node = node.getParent();
        }
        return false;
    }

    /**
     * Node property for a component that takes over only a <em>few</em> editor chords while something
     * transient is up over the editor (the completion popup, the quick-fix list): a
     * {@code Map<String, String>} of chord token → the command id the component stands in for
     * ({@code "C-n" -> "nav.lineDown"}: the list moves its selection instead of the caret). A chord is left
     * to the component only while the keymap binds it to exactly that command, so every other binding —
     * and the same chord under a keymap that gives it another meaning — is dispatched as usual.
     * {@code editora.ownsKeys} is the wrong tool there: it yields <em>every</em> {@code nav.*}/{@code edit.*}
     * chord, which then falls through to the text area's built-in bindings or types a character.
     */
    public static final String OWNED_CHORDS = "editora.ownsChords";

    private static boolean ownsChord(EventTarget target, String token, String commandId) {
        Node node = target instanceof Node n ? n : null;
        while (node != null) {
            if (node.hasProperties() && chordOwned(node.getProperties().get(OWNED_CHORDS), token, commandId)) {
                return true;
            }
            node = node.getParent();
        }
        return false;
    }

    /** Whether an {@link #OWNED_CHORDS} property value hands {@code token} (bound to {@code commandId}) over. Pure. */
    static boolean chordOwned(Object property, String token, String commandId) {
        return property instanceof java.util.Map<?, ?> chords
                && commandId != null
                && commandId.equals(chords.get(token));
    }

    /**
     * Node property naming the bare keys a component handles itself even when the keymap binds them to a
     * <em>global</em> command: a {@code Set<String>} of chord tokens (e.g. {@code "f2"}, {@code "delete"}).
     * {@code editora.ownsKeys} only yields editor-context chords, which is not enough for a key like F2 —
     * "rename the selected file" in a file tree, but bound to {@code lsp.rename} in three keymaps. Set it on
     * the node that should have the key (the tree, not its whole panel), and keep the set small.
     *
     * <p>A claim also lets an <em>unbound</em> plain-Alt chord through ({@code "M-left"}), which is otherwise
     * consumed on Windows and Linux so it cannot open the native menu. The claiming component must consume
     * such a press; {@link #consumeClaimedAlt} is the backstop.
     */
    public static final String CLAIMED_KEYS = "editora.claimsKeys";

    private static boolean claimsKey(EventTarget target, String token) {
        Node node = target instanceof Node n ? n : null;
        while (node != null) {
            if (node.hasProperties()
                    && node.getProperties().get(CLAIMED_KEYS) instanceof java.util.Set<?> keys
                    && keys.contains(token)) {
                return true;
            }
            node = node.getParent();
        }
        return false;
    }

    private void reset() {
        if (!pending.isEmpty()) {
            pending = "";
        }
    }

    /** Builds a chord token like {@code C-x}, {@code M-w}, {@code C-S-p}; null for modifier-only events. */
    public static String chord(KeyEvent event) {
        KeyCode code = event.getCode();
        if (code == null || code == KeyCode.UNDEFINED || code.isModifierKey()) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        if (event.isControlDown()) {
            sb.append("C-");
        }
        if (event.isAltDown()) {
            sb.append("M-");
        }
        if (event.isMetaDown()) {
            sb.append("Cmd-");
        }
        if (event.isShiftDown()) {
            sb.append("S-");
        }
        sb.append(keyName(code));
        return sb.toString();
    }

    private static String keyName(KeyCode code) {
        if (code.isLetterKey()) {
            return code.getName().toLowerCase(Locale.ROOT);
        }
        if (code.isDigitKey()) {
            // Main-row digits: getName() is "0".."9". Numpad digits: "Numpad 6" — take the trailing digit so
            // it maps to "6" (matches the M-1…M-9 chords) instead of an unmatchable, space-containing token.
            String name = code.getName();
            return name.length() == 1 ? name : name.substring(name.length() - 1);
        }
        return switch (code) {
            case SPACE -> "space";
            case SLASH -> "/";
            case BACK_SLASH -> "\\";
            case PERIOD -> ".";
            case COMMA -> ",";
            case SEMICOLON -> ";";
            case MINUS -> "-";
            case EQUALS -> "=";
            case OPEN_BRACKET -> "[";
            case CLOSE_BRACKET -> "]";
            case ENTER -> "enter";
            case TAB -> "tab";
            case BACK_SPACE -> "backspace";
            case DELETE -> "delete";
            case ESCAPE -> "escape";
            case LEFT -> "left";
            case RIGHT -> "right";
            case UP -> "up";
            case DOWN -> "down";
            case HOME -> "home";
            case END -> "end";
            case PAGE_UP -> "pageup";
            case PAGE_DOWN -> "pagedown";
            default -> code.getName().toLowerCase(Locale.ROOT).replace(' ', '-');
        };
    }
}

package com.editora.ui;

import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.function.Supplier;

import javafx.scene.control.Control;

import com.editora.command.KeymapManager;
import com.editora.command.TextInputKeymap;
import org.jspecify.annotations.Nullable;

import static com.editora.i18n.Messages.tr;

/**
 * Names a button after the command it runs: its tooltip and its accessible text are one string, the command's
 * title (without the palette category) and the chord the active keymap binds to it — "Save (C-x C-s)". The
 * chord is read from the keymap, never typed into a string, and {@link #refresh} reads it again after a keymap
 * switch, so the name a screen reader says stays the one a sighted user reads.
 */
final class CommandTips {

    /** What a named control shows: its title (which may change, for a toggle) and its command. */
    private record Tip(
            Supplier<String> title,
            String commandId,
            @Nullable Supplier<String> detail) {}

    /** The {@link Control#getProperties()} key a control's {@link Tip} is kept under. */
    private static final String KEY = "editora.commandTip";

    /**
     * The controls to name again on a keymap switch. Weak, and it holds the controls alone: a control's
     * {@link Tip} lives in the control's own properties, because a title supplier usually captures the view the
     * button sits in, and a map value that reaches its own weak key keeps the whole view alive for good.
     */
    private static final Set<Control> NAMED = Collections.newSetFromMap(new WeakHashMap<>());

    private CommandTips() {}

    /** Names {@code control} after command {@code commandId}: the command's own title and chord. */
    static <C extends Control> C name(C control, String commandId) {
        return name(control, commandId, () -> title(commandId));
    }

    /**
     * Names {@code control} with {@code title} and the chord of {@code commandId}: for a button whose words
     * say more than the command's title does where it stands (a toggle that reads "Show" or "Hide", a tool
     * window's own name), while the chord is still the command's.
     */
    static <C extends Control> C name(C control, String commandId, Supplier<String> title) {
        return name(control, commandId, title, null);
    }

    /**
     * As {@link #name(Control, String, Supplier)}, with a line of {@code detail} under the name: what a button
     * does that its title cannot say ("Copy as cURL (C-c u)", then which shells the command is written for).
     */
    static <C extends Control> C name(
            C control, String commandId, Supplier<String> title, @Nullable Supplier<String> detail) {
        Tip tip = new Tip(title, commandId, detail);
        control.getProperties().put(KEY, tip);
        NAMED.add(control);
        apply(control, tip);
        return control;
    }

    /** Names every control again: the keymap changed, or a title a supplier answers did. */
    static void refresh() {
        // A copy: Icons.name replaces a tooltip, which must not disturb the set while it is walked.
        for (Control control : List.copyOf(NAMED)) {
            refresh(control);
        }
    }

    /** Names {@code control} again from what it was named with; a control never named is left alone. */
    static void refresh(Control control) {
        if (control.getProperties().get(KEY) instanceof Tip tip) {
            apply(control, tip);
        } else if (control.getProperties().get(FOLLOWER) instanceof Runnable again) {
            again.run();
        }
    }

    /** Property key of a {@link #follow} control: what names it (and its neighbors) again. */
    private static final Object FOLLOWER = new Object();

    /**
     * Runs {@code again} on every {@link #refresh()}, for the life of {@code anchor}: for a toolbar that builds
     * names of a form of its own (the debugger's, which adds the panel's letter to the chord) and so has to be
     * told when the keymap changed. {@code again} must not capture more of the view than {@code anchor} does:
     * it lives in the control's own properties.
     */
    static void follow(Control anchor, Runnable again) {
        anchor.getProperties().put(FOLLOWER, again);
        NAMED.add(anchor);
    }

    /** A command's title without its palette category: "File: Save" is "Save". */
    static String title(String commandId) {
        return DebugPanelText.bareTitle(tr("command." + commandId));
    }

    /** {@code title} followed by the chord of {@code commandId} in parentheses, or alone when it is unbound. */
    static String text(String title, String commandId) {
        return withChord(title, chord(commandId));
    }

    /** Pure: the name for a title and a display chord (null or blank when the command is unbound). */
    static String withChord(String title, @Nullable String chord) {
        return chord == null || chord.isBlank() ? title : title + " (" + chord + ")";
    }

    private static @Nullable String chord(String commandId) {
        KeymapManager keymap = TextInputKeymap.sharedKeymap();
        return keymap == null ? null : keymap.displayChord(commandId);
    }

    private static void apply(Control control, Tip tip) {
        String name = text(tip.title().get(), tip.commandId());
        String detail = tip.detail() == null ? null : tip.detail().get();
        if (detail != null && !detail.isBlank()) {
            name = name + "\n" + detail;
        }
        if (!name.equals(control.getAccessibleText())
                || control.getTooltip() == null
                || !name.equals(control.getTooltip().getText())) {
            Icons.name(control, name);
        }
    }
}

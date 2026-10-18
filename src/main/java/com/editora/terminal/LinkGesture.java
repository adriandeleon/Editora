package com.editora.terminal;

/**
 * Which gesture opens a link.
 *
 * <p><b>Never a plain click.</b> A plain click in a terminal is the start of a selection, and once
 * a program has turned mouse reporting on it is that program's click — vim and tmux both act on it.
 * A modifier is the only press that is unambiguously the terminal's own, which is why every
 * terminal that has this feature uses one.
 *
 * <p>Command on macOS, Control everywhere else, matching Terminal.app, iTerm2, GNOME Terminal,
 * Konsole and Windows Terminal. Control is deliberately <em>not</em> the macOS gesture: there
 * Control-click is how a one-button mouse raises a context menu, and taking it would break that.
 */
public final class LinkGesture {

    private LinkGesture() {}

    /** Whether the modifiers held mean "open the link", rather than "select" or "menu". */
    public static boolean opensLink(boolean mac, boolean metaDown, boolean controlDown) {
        return mac ? metaDown && !controlDown : controlDown && !metaDown;
    }

    /** The name of that key, for a tooltip or a menu. Not translated — these are key legends. */
    public static String modifierName(boolean mac) {
        return mac ? "Cmd" : "Ctrl";
    }
}

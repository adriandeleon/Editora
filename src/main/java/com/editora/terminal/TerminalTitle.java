package com.editora.terminal;

/**
 * What a tab and a window are called.
 *
 * <p>Two audiences with different amounts of room, so two answers from the same inputs. The window
 * title bar can hold a path, and a path is what tells you which checkout you are looking at. A tab
 * is a hundred-odd pixels wide, and JavaFX ellipsises from the <em>end</em> — so putting a path
 * there keeps {@code ~/src/adl/Edi…}, throwing away the only part that distinguishes it from its
 * neighbours. The tab gets the last segment alone, which is what macOS Terminal shows and what a
 * directory is generally called in conversation.
 *
 * <p>A title the shell set always wins, because it was an explicit instruction: {@code vim} naming
 * the file being edited, or a long build announcing its progress, is more useful than the directory
 * both of them are obviously in. The directory is the answer for the overwhelmingly common case
 * where nothing has an opinion — an idle prompt.
 *
 * <p>Pure, and tested: every branch here is a rule about what the user reads, and the failure mode
 * of getting one wrong is a label that looks plausible and says the wrong thing.
 */
final class TerminalTitle {

    private TerminalTitle() {}

    /** Nothing to say yet — no title from the shell and no directory: the window shows its own label. */
    static final String UNTITLED = "";

    /** The title bar: the full path, with the home directory abbreviated the way a shell does. */
    static String window(String shellTitle, String cwd, String home) {
        if (isSet(shellTitle)) return shellTitle;
        String path = collapseHome(cwd, home);
        return isSet(path) ? path : UNTITLED;
    }

    /** The tab: the directory's own name, which is as much as fits and as much as identifies it. */
    static String tab(String shellTitle, String cwd, String home) {
        if (isSet(shellTitle)) return shellTitle;
        String collapsed = collapseHome(cwd, home);
        if (!isSet(collapsed)) return UNTITLED;
        // "~" is a name in its own right; reducing it to its last segment would print the user's
        // login name, which says nothing about where they are.
        if (collapsed.equals("~")) return collapsed;
        return baseName(collapsed);
    }

    /**
     * The tab's hover: the directory, in full, whatever the shell has called itself.
     *
     * <p>The one place that deliberately ignores {@code shellTitle}, and the reason is that the tab
     * beside it does not. A tab reading {@code vim} has already spent its width saying what is
     * running, so a hover repeating it adds nothing, while the directory it is running in has
     * nowhere else to appear. Empty when the OS will not say where the shell is, which is a state
     * the caller shows nothing for rather than an empty box.
     */
    static String tooltip(String shellTitle, String cwd, String home) {
        return collapseHome(cwd, home);
    }

    /**
     * {@code /home/adl/src} becomes {@code ~/src}. Whole segments only — a sibling directory named
     * {@code /home/adliterally} must not be rewritten into {@code ~iterally}.
     */
    static String collapseHome(String path, String home) {
        if (!isSet(path) || !isSet(home)) return path == null ? "" : path;
        String trimmedHome = stripTrailingSlash(home);
        if (path.equals(trimmedHome)) return "~";
        if (path.startsWith(trimmedHome + "/")) return "~" + path.substring(trimmedHome.length());
        return path;
    }

    /** The last segment: {@code ~/src/adl/Editora} becomes {@code Editora}, and {@code /} stays. */
    static String baseName(String path) {
        if (!isSet(path)) return "";
        String trimmed = stripTrailingSlash(path);
        // Root has no last segment, and stripping its only slash leaves nothing to show.
        if (trimmed.isEmpty()) return "/";
        int slash = trimmed.lastIndexOf('/');
        return slash < 0 ? trimmed : trimmed.substring(slash + 1);
    }

    private static String stripTrailingSlash(String path) {
        int end = path.length();
        while (end > 0 && path.charAt(end - 1) == '/') end--;
        return path.substring(0, end);
    }

    private static boolean isSet(String value) {
        return value != null && !value.isBlank();
    }
}

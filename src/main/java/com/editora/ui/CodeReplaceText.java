package com.editora.ui;

import java.util.List;

import static com.editora.i18n.Messages.tr;

/** Pure: the words Editora uses for a hot code replace (see {@code DebugCoordinator.applyCodeChanges}). */
final class CodeReplaceText {

    /** At most this many class names are listed; the rest are counted. */
    static final int MAX_NAMED = 4;

    private CodeReplaceText() {}

    /**
     * The replaced classes by simple name ({@code com.acme.Main$Inner} → {@code Main$Inner}), in their order,
     * the first {@link #MAX_NAMED} named and the rest counted.
     */
    static String classList(List<String> classes) {
        if (classes == null || classes.isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        int shown = Math.min(MAX_NAMED, classes.size());
        for (int i = 0; i < shown; i++) {
            String name = classes.get(i) == null ? "" : classes.get(i).strip();
            int dot = name.lastIndexOf('.');
            if (i > 0) {
                out.append(", ");
            }
            out.append(dot >= 0 ? name.substring(dot + 1) : name);
        }
        if (classes.size() > shown) {
            out.append(' ').append(tr("debug.hcr.andMore", classes.size() - shown));
        }
        return out.toString();
    }

    /**
     * What to print in the Debug console for one of java-debug's {@code hotcodereplace} events, or {@code null}
     * for one that needs no words. Progress ({@code STARTING}, {@code END}, {@code BUILD_COMPLETE}) is not
     * printed: Editora reports the outcome itself. The one warning java-debug words for its own developers —
     * frames it could not drop still run the replaced methods — is said plainly.
     */
    static String notice(String changeType, String message) {
        String text = message == null ? "" : message.strip();
        if (!"ERROR".equals(changeType) && !"WARNING".equals(changeType)) {
            return null;
        }
        if (text.toLowerCase(java.util.Locale.ROOT).contains("obsolete methods")) {
            return tr("debug.hcr.obsoleteMethods");
        }
        return text.isEmpty() ? null : text;
    }

    /**
     * Whether a failed replace's {@code reason} adds nothing to the {@code ERROR} event java-debug already sent
     * ({@code noticed}, printed when it arrived): java-debug words both from the same failure, the answer often
     * as "Failed to redefine classes: " plus the event's text. Also true when the answer gives no reason at all.
     */
    static boolean saidAlready(String reason, String noticed) {
        String r = reason == null ? "" : reason.strip();
        String n = noticed == null ? "" : noticed.strip();
        return r.isEmpty() || (!n.isEmpty() && r.contains(n));
    }
}

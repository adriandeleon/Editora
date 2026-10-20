package com.editora.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.editora.plugin.PluginManifest;
import org.jspecify.annotations.Nullable;

import static com.editora.i18n.Messages.tr;

/**
 * What the consent dialog says a plugin can do, built from its manifest: the Java code it ships (main class and
 * every jar its loader reads), each external command with its title, its exact argv and where it runs, each
 * chord in the user's notation with what it replaces, and the snippets and templates it adds. Pure aside from
 * {@code tr(...)}, so the wording is unit-tested.
 *
 * <p>Every manifest string is {@linkplain #clean escaped and capped} first. They used to go into the dialog
 * as they were: a name with newlines printed a fake "snippets only" disclosure at the top and pushed the real
 * one, and the OK button, a screen height down; a right-to-left override reordered an argv.
 */
final class PluginDisclosure {

    private PluginDisclosure() {}

    /** The longest a name, title or command part may be shown before it is cut. */
    static final int MAX_FIELD = 160;

    /** How the dialog names chords and commands in this window. */
    interface Keys {
        /** {@code chord} in the active keymap's notation. */
        String display(String chord);

        /** The title of the command {@code chord} runs now, or null when it is free. */
        @Nullable
        String boundTitle(String chord);

        /** The title of command {@code id} (a built-in's, a declared one's), or the id when it has none. */
        String commandTitle(String id);
    }

    /** Raw notation and ids: for tests and for callers without a window. */
    static final Keys RAW = new Keys() {
        @Override
        public String display(String chord) {
            return chord;
        }

        @Override
        public @Nullable String boundTitle(String chord) {
            return null;
        }

        @Override
        public String commandTitle(String id) {
            return id;
        }
    };

    /**
     * {@code s} safe to show on one line: line breaks, tabs and other control characters become visible escapes
     * ({@code \n}, {@code \t}, {@code <U+0007>}), and so do invisible format characters — bidi overrides and
     * isolates, zero-width joiners — which can reorder or hide text. Longer than {@code max}: cut with "…".
     */
    static String clean(String s, int max) {
        if (s == null) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        int count = 0;
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            i += Character.charCount(cp);
            if (count >= max) {
                out.append('…');
                break;
            }
            String piece;
            if (cp == '\n') {
                piece = "\\n";
            } else if (cp == '\r') {
                piece = "\\r";
            } else if (cp == '\t') {
                piece = "\\t";
            } else if (Character.isISOControl(cp)
                    || Character.getType(cp) == Character.FORMAT
                    || Character.getType(cp) == Character.LINE_SEPARATOR
                    || Character.getType(cp) == Character.PARAGRAPH_SEPARATOR) {
                piece = String.format("<U+%04X>", cp);
            } else {
                piece = new String(Character.toChars(cp));
            }
            out.append(piece);
            count++;
        }
        return out.toString();
    }

    /** {@code argv} as a JSON-style list, each part quoted, so {@code ["a b"]} and {@code ["a", "b"]} differ. */
    static String quoted(List<String> argv) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; argv != null && i < argv.size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            String part = argv.get(i) == null ? "" : argv.get(i);
            sb.append('"')
                    .append(clean(part.replace("\\", "\\\\").replace("\"", "\\\""), MAX_FIELD))
                    .append('"');
        }
        return sb.append(']').toString();
    }

    /**
     * The capability list: one bullet per kind, details indented below it.
     *
     * @param m            the manifest
     * @param jars         the jars its loader would read ({@code x.jar}, {@code lib/y.jar})
     * @param hasSnippets  whether it ships a {@code snippets/} folder
     * @param hasTemplates whether it ships a {@code templates/} folder
     * @param keymapId     the active keymap, whose chords are listed first
     * @param keys         names chords and commands in this window
     */
    static String capabilities(
            PluginManifest m,
            List<String> jars,
            boolean hasSnippets,
            boolean hasTemplates,
            String keymapId,
            Keys keys) {
        List<String> lines = new ArrayList<>();
        if (m.main != null && !m.main.isBlank()) {
            lines.add(tr("plugins.cap.code"));
            lines.add("    " + tr("plugins.cap.mainClass", clean(m.main.strip(), MAX_FIELD)));
            lines.add("    "
                    + tr(
                            "plugins.cap.jars",
                            jars == null || jars.isEmpty()
                                    ? tr("plugins.cap.noJars")
                                    : clean(String.join(", ", jars), MAX_FIELD * 2)));
            lines.add("    " + tr("plugins.cap.codeAlso"));
        }
        List<PluginManifest.DeclaredCommand> commands = new ArrayList<>();
        for (PluginManifest.DeclaredCommand c :
                m.commands == null ? List.<PluginManifest.DeclaredCommand>of() : m.commands) {
            if (c != null && c.id != null && !c.id.isBlank() && c.run != null && !c.run.isEmpty()) {
                commands.add(c); // a blank id is never registered, an empty argv never runs: nothing to disclose
            }
        }
        if (!commands.isEmpty()) {
            lines.add(tr("plugins.cap.commands"));
            for (PluginManifest.DeclaredCommand c : commands) {
                String title = c.title == null || c.title.isBlank() ? c.id : c.title;
                lines.add("    " + clean(title, MAX_FIELD) + ": " + quoted(c.run));
                if (c.dir != null && !c.dir.isBlank()) {
                    lines.add("        " + tr("plugins.cap.inDir", clean(c.dir, MAX_FIELD)));
                }
            }
        }
        Map<String, String> active = m.keymapFor(keymapId);
        if (!active.isEmpty()) {
            lines.add(tr("plugins.cap.keymap"));
            active.forEach((chord, command) -> lines.add("    " + chordLine(m.id, chord, command, keys)));
        }
        if (m.keymaps != null) {
            m.keymaps.forEach((name, chords) -> {
                if (!name.equals(keymapId) && chords != null && !chords.isEmpty()) {
                    lines.add(tr("plugins.cap.keymapOther", clean(name, 40)));
                    chords.forEach((chord, command) -> lines.add("    " + clean(chord, MAX_FIELD) + " → "
                            + (command == null || command.isBlank()
                                    ? tr("plugins.cap.unbindsRaw")
                                    : clean(command, MAX_FIELD))));
                }
            });
        }
        if (hasSnippets) {
            lines.add(tr("plugins.cap.snippets"));
        }
        if (hasTemplates) {
            lines.add(tr("plugins.cap.templates"));
        }
        if (lines.isEmpty()) {
            lines.add(tr("plugins.cap.nothing"));
        }
        return String.join("\n", lines);
    }

    /** "C-x C-s → Format: Pretty (replaces Save)", "unbinds C-x C-s (Save)". */
    private static String chordLine(String pluginId, String chord, String command, Keys keys) {
        String shown = clean(keys.display(chord), MAX_FIELD);
        String current = keys.boundTitle(chord);
        if (command == null || command.isBlank()) {
            return current == null
                    ? tr("plugins.cap.unbinds", shown)
                    : tr("plugins.cap.unbindsNamed", shown, clean(current, MAX_FIELD));
        }
        String target = PluginManifest.qualify(pluginId, command);
        String title = clean(keys.commandTitle(target), MAX_FIELD);
        return current == null || current.equals(keys.commandTitle(target))
                ? shown + " → " + title
                : tr("plugins.cap.replaces", shown, title, clean(current, MAX_FIELD));
    }
}

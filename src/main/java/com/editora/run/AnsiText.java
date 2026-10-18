package com.editora.run;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Reads terminal escape sequences out of program output for the Run console (pure, unit-tested).
 *
 * <p>Scripts colour their output with SGR sequences ({@code \033[31m}) whether or not they are writing to a
 * terminal, and the console used to show them as raw text with an unprintable glyph. A {@link Parser} keeps
 * the colour state of one output stream across chunks and turns each chunk into visible text plus style runs:
 *
 * <ul>
 *   <li>SGR colour and weight become style classes ({@code ansi-red}, {@code ansi-bold}, …) that the theme
 *       colours; 256-colour codes below 16 map to the same classes, other colours and backgrounds are read and
 *       dropped.</li>
 *   <li>Erase-line ({@code \033[2K}) becomes a carriage return, so the console's "overwrite the current line"
 *       handles {@code printf 'x\033[2K\rnew'} the way a terminal draws it; clear-screen ({@code \033[2J},
 *       what {@code clear} prints) becomes {@link #CLEAR_SCREEN}.</li>
 *   <li>Every other control sequence (cursor movement, OSC window titles, …) is removed.</li>
 * </ul>
 *
 * <p>A sequence cut in two by a chunk boundary is held back until the rest arrives.
 */
public final class AnsiText {

    private AnsiText() {}

    /** Stands in the visible text for "clear the screen". */
    public static final char CLEAR_SCREEN = '\f';

    private static final char ESC = '\u001b';

    /** A run of {@code length} visible characters drawn with {@code classes} (empty for the default). */
    public record Run(int length, Set<String> classes) {}

    /** One chunk's visible text and its style runs (covering the whole text, in order). */
    public record Chunk(String text, List<Run> runs) {}

    private static final String[] COLORS = {"black", "red", "green", "yellow", "blue", "magenta", "cyan", "white"};

    /** The colour state of one output stream. Not thread-safe; one per stream per run. */
    public static final class Parser {
        private String fg;
        private boolean bold;
        private boolean dim;
        private boolean italic;
        private boolean underline;
        private final StringBuilder held = new StringBuilder();

        /** Whether a colour or style is in force (or a sequence is half read): plain text is then not plain. */
        public boolean active() {
            return fg != null || bold || dim || italic || underline || !held.isEmpty();
        }

        /** Reads one chunk. */
        public Chunk feed(String chunk) {
            if (held.isEmpty() && chunk.indexOf(ESC) < 0) {
                Set<String> plain = classes(); // the common case: no escapes, one run in the current style
                return new Chunk(chunk, chunk.isEmpty() ? List.of() : List.of(new Run(chunk.length(), plain)));
            }
            String in = held.isEmpty() ? chunk : held + chunk;
            held.setLength(0);
            StringBuilder text = new StringBuilder(in.length());
            List<Run> runs = new ArrayList<>();
            int runStart = 0;
            Set<String> current = classes();
            int i = 0;
            int n = in.length();
            while (i < n) {
                char c = in.charAt(i);
                if (c != ESC) {
                    text.append(c);
                    i++;
                    continue;
                }
                int end = sequenceEnd(in, i);
                if (end < 0) {
                    held.append(in, i, n); // the rest of this sequence is still on its way
                    break;
                }
                char kind = i + 1 < n ? in.charAt(i + 1) : 0;
                if (kind == '[') {
                    char finalChar = in.charAt(end - 1);
                    String params = in.substring(i + 2, end - 1);
                    if (finalChar == 'm') {
                        applySgr(params);
                    } else if (finalChar == 'K' && params.equals("2")) {
                        text.append('\r');
                    } else if (finalChar == 'J' && (params.equals("2") || params.equals("3"))) {
                        if (text.isEmpty() || text.charAt(text.length() - 1) != CLEAR_SCREEN) {
                            text.append(CLEAR_SCREEN);
                        }
                    }
                }
                i = end;
                Set<String> now = classes();
                if (!now.equals(current)) {
                    if (text.length() > runStart) {
                        runs.add(new Run(text.length() - runStart, current));
                    }
                    runStart = text.length();
                    current = now;
                }
            }
            if (text.length() > runStart) {
                runs.add(new Run(text.length() - runStart, current));
            }
            return new Chunk(text.toString(), runs);
        }

        private Set<String> classes() {
            if (fg == null && !bold && !dim && !italic && !underline) {
                return Set.of();
            }
            Set<String> out = new LinkedHashSet<>();
            if (fg != null) {
                out.add("ansi-" + fg);
            }
            if (bold) {
                out.add("ansi-bold");
            }
            if (dim) {
                out.add("ansi-dim");
            }
            if (italic) {
                out.add("ansi-italic");
            }
            if (underline) {
                out.add("ansi-underline");
            }
            return Set.copyOf(out);
        }

        private void applySgr(String params) {
            String[] parts = params.isEmpty() ? new String[] {"0"} : params.split("[;:]", -1);
            for (int p = 0; p < parts.length; p++) {
                int code = number(parts[p]);
                switch (code) {
                    case 0 -> {
                        fg = null;
                        bold = dim = italic = underline = false;
                    }
                    case 1 -> bold = true;
                    case 2 -> dim = true;
                    case 3 -> italic = true;
                    case 4 -> underline = true;
                    case 22 -> bold = dim = false;
                    case 23 -> italic = false;
                    case 24 -> underline = false;
                    case 39 -> fg = null;
                    case 38, 48 -> {
                        // 38;5;n or 38;2;r;g;b (and the 48 background forms): read, keep only n < 16 foregrounds
                        int mode = p + 1 < parts.length ? number(parts[p + 1]) : -1;
                        if (mode == 5) {
                            int index = p + 2 < parts.length ? number(parts[p + 2]) : -1;
                            if (code == 38 && index >= 0 && index < 16) {
                                fg = COLORS[index % 8];
                            }
                            p += 2;
                        } else if (mode == 2) {
                            p += 4;
                        }
                    }
                    default -> {
                        if (code >= 30 && code <= 37) {
                            fg = COLORS[code - 30];
                        } else if (code >= 90 && code <= 97) {
                            fg = COLORS[code - 90];
                        }
                        // backgrounds (40–47, 100–107, 49) and anything else: not drawn
                    }
                }
            }
        }
    }

    /**
     * The index just past the escape sequence starting at {@code start}, or -1 when it is not complete yet.
     * CSI ({@code ESC [ … final}), OSC ({@code ESC ] … BEL} or {@code ESC ] … ESC \}), and two-character
     * escapes are recognized; a lone ESC at the very end is incomplete.
     */
    static int sequenceEnd(String s, int start) {
        int n = s.length();
        if (start + 1 >= n) {
            return -1;
        }
        char kind = s.charAt(start + 1);
        if (kind == '[') {
            for (int j = start + 2; j < n; j++) {
                char c = s.charAt(j);
                if (c >= 0x40 && c <= 0x7e) {
                    return j + 1;
                }
                if (j - start > 64) {
                    return j; // not a real sequence: give up on it rather than hold output forever
                }
            }
            return -1;
        }
        if (kind == ']') {
            for (int j = start + 2; j < n; j++) {
                char c = s.charAt(j);
                if (c == '\u0007') {
                    return j + 1;
                }
                if (c == ESC && j + 1 < n && s.charAt(j + 1) == '\\') {
                    return j + 2;
                }
                if (j - start > 512) {
                    return j;
                }
            }
            return -1;
        }
        if (kind >= 0x20 && kind <= 0x2f) {
            // ESC, intermediates, a final byte: the charset choice ESC ( B that `tput sgr0` sends after a reset
            for (int j = start + 2; j < n; j++) {
                char c = s.charAt(j);
                if (c >= 0x30 && c <= 0x7e) {
                    return j + 1;
                }
                if (c < 0x20 || c > 0x2f) {
                    return j;
                }
            }
            return -1;
        }
        return start + 2; // ESC followed by one character (ESC 7, ESC =, …)
    }

    private static int number(String s) {
        if (s.isEmpty()) {
            return 0;
        }
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}

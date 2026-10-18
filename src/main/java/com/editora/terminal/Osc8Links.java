package com.editora.terminal;

import java.util.Locale;
import java.util.Set;

import com.jediterm.terminal.HyperlinkStyle;
import com.jediterm.terminal.TextStyle;
import com.jediterm.terminal.model.hyperlinks.HyperlinkFilter;
import com.jediterm.terminal.model.hyperlinks.LinkInfo;
import com.jediterm.terminal.model.hyperlinks.LinkResult;
import com.jediterm.terminal.model.hyperlinks.LinkResultItem;

/**
 * OSC 8 hyperlinks ({@code ESC ] 8 ; ; URI ST text ESC ] 8 ; ; ST}): text a program marked as a link to a URI it
 * chose, as {@code ls --hyperlink}, {@code gcc} diagnostics, {@code git} and many CLIs print.
 *
 * <p>JediTerm parses the sequence and asks a {@link HyperlinkFilter} what the URI is; the answer is kept in the
 * style of every cell the program then writes, until the closing sequence. {@link #filter} answers with a {@link
 * Target} carrying the URI, which the view reads back from the cell under the pointer. Only schemes a browser or
 * Editora opens safely become links; anything else stays plain text.
 */
public final class Osc8Links {

    private Osc8Links() {}

    /** Schemes an OSC 8 link may have. */
    static final Set<String> SCHEMES = Set.of("http", "https", "ftp", "mailto", "file");

    /** The URI an OSC 8 link points at, as kept in its cells' style. Opening is the view's, not JediTerm's. */
    public static final class Target extends LinkInfo {

        private final String uri;

        Target(String uri) {
            super(() -> {});
            this.uri = uri;
        }

        public String uri() {
            return uri;
        }
    }

    /** The filter to give the emulator ({@code JediTerminal.setUrlHyperlinkFilter}). */
    public static HyperlinkFilter filter() {
        return uri -> allowed(uri) ? new LinkResult(new LinkResultItem(0, uri.length(), new Target(uri))) : null;
    }

    /** Whether {@code uri} has a scheme an OSC 8 link may open. */
    static boolean allowed(String uri) {
        if (uri == null || uri.isBlank() || uri.length() > 2048) {
            return false;
        }
        int colon = uri.indexOf(':');
        if (colon <= 0) {
            return false;
        }
        return SCHEMES.contains(uri.substring(0, colon).toLowerCase(Locale.ROOT));
    }

    /** The OSC 8 target of a cell's style, or null when the cell is not part of one. */
    public static Target targetOf(TextStyle style) {
        return style instanceof HyperlinkStyle link && link.getLinkInfo() instanceof Target target ? target : null;
    }
}

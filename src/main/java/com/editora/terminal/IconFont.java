package com.editora.terminal;

import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javafx.scene.text.Font;

import org.jspecify.annotations.Nullable;

/**
 * The face that draws private-use code points: the Nerd Font icons {@code eza}, {@code lsd} and every powerline
 * prompt put on screen.
 *
 * <p>No face Editora bundles has a glyph there, so JavaFX goes down its fallback list — fontconfig's ordering for
 * <em>sans</em>, which is about text, not icons — and takes the first face that maps the code point. For most icons
 * that is the installed Nerd Font. But a private-use code point has no agreed meaning, and other fonts put their own
 * glyphs there: on a machine with the Microsoft core fonts and MathJax installed, Arial claims U+F016 and
 * MathJax_WinIE6 claims U+E271, both ahead of the Nerd Font in that list. {@code eza} gives a symlink to a file those
 * two icons, so {@code vmlinuz} and {@code initrd.img} came out with a stray accent beside them while every other
 * icon in the listing was right. Asking for an installed Nerd Font by name for these code points takes the list out of
 * it. With none installed, or with one as the terminal face, nothing changes.
 *
 * <p>Ported from Termina, without its bundled Symbols Nerd Font Mono (see docs/subsystems/terminal.md).
 */
final class IconFont {

    /**
     * A Nerd Fonts family name, in either naming scheme: {@code Hack Nerd Font Mono}, or the short
     * {@code JetBrainsMono NFM} that newer releases put in the family field. Anything after the suffix —
     * {@code JetBrainsMono NF ExtraBold} — is a weight registered as a family of its own.
     */
    private static final Pattern NERD = Pattern.compile("(.+?) (?:Nerd Font( Mono| Propo)?|NF([MP])?)");

    private IconFont() {}

    /**
     * Whether {@code codePoint} is private use, in the BMP or in either supplementary plane. JediTerm's
     * {@link com.jediterm.terminal.util.CharUtils#DWC} filler is one of these, so the renderer has to have dealt with
     * it first.
     */
    static boolean isPrivateUse(int codePoint) {
        return (codePoint >= 0xE000 && codePoint <= 0xF8FF)
                || (codePoint >= 0xF0000 && codePoint <= 0xFFFFD)
                || (codePoint >= 0x100000 && codePoint <= 0x10FFFD);
    }

    /** Whether any code point of {@code text} is private use. */
    static boolean containsPrivateUse(CharSequence text) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            // Every BMP private-use char is in one range, and every supplementary one is a pair whose high
            // surrogate is at least U+DB80; anything below either cannot be one.
            if (c >= 0xE000 && c <= 0xF8FF) return true;
            if (c >= 0xDB80 && c <= 0xDBFF && isPrivateUse(Character.codePointAt(text, i))) return true;
        }
        return false;
    }

    /**
     * The installed family to draw private-use code points in, or empty to leave them to the terminal face and its
     * fallbacks.
     *
     * <p>Empty when {@code primary} is itself a Nerd Font. Otherwise, best first: the Nerd Font build of the
     * terminal's own face, then the symbols-only build, then any other. Within each, the plain variant before
     * {@code Mono}: its icons are drawn larger and may lean into the space after them, which is the look a listing is
     * laid out for. {@code Propo} is proportional and never chosen.
     */
    static Optional<String> chooseFamily(@Nullable String primary, Collection<String> installed) {
        if (primary != null && NERD.matcher(primary).matches()) return Optional.empty();
        String own = primary == null ? "" : squash(primary);
        return installed.stream()
                .filter(family -> {
                    Matcher m = NERD.matcher(family);
                    return m.matches() && !" Propo".equals(m.group(2)) && !"P".equals(m.group(3));
                })
                .min(Comparator.comparingInt((String family) -> rank(family, own))
                        .thenComparing(Comparator.naturalOrder()));
    }

    private static int rank(String family, String own) {
        Matcher m = NERD.matcher(family);
        m.matches();
        String base = squash(m.group(1));
        int source = base.equals(own) ? 0 : base.equals("symbols") ? 1 : 2;
        boolean mono = m.group(2) != null || m.group(3) != null;
        return source * 2 + (mono ? 1 : 0);
    }

    /** {@code JetBrains Mono} and {@code JetBrainsMono} name the same face. */
    private static String squash(String family) {
        return family.replace(" ", "").toLowerCase(Locale.ROOT);
    }

    private static @Nullable Set<String> installed;
    private static final Map<String, Optional<String>> CHOSEN = new HashMap<>();

    /** {@link #chooseFamily} against this machine's fonts, once per terminal face. */
    static synchronized Optional<String> familyFor(String primary) {
        Set<String> fonts = installed;
        if (fonts == null) installed = fonts = Set.copyOf(Font.getFamilies());
        Set<String> chooseFrom = fonts;
        return CHOSEN.computeIfAbsent(primary, p -> chooseFamily(p, chooseFrom));
    }
}

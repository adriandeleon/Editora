package com.editora.ui;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

import javafx.scene.text.Font;
import javafx.scene.text.Text;

/**
 * The editor-font choices: the bundled monospace families first, then the system's monospace families —
 * without the weight variants a font installs as families of their own ("IBM Plex Mono ExtLt",
 * "JetBrainsMono NF Thin") when the family itself is listed, and without math and symbol fonts, which
 * measure as monospaced only because they have no letters to measure.
 */
final class FontChoices {

    /** Words a family name ends with when it is one weight or style of another family. */
    private static final Set<String> WEIGHTS = Set.of(
            "thin",
            "hairline",
            "extralight",
            "ultralight",
            "extlt",
            "light",
            "lt",
            "regular",
            "book",
            "text",
            "retina",
            "medium",
            "medm",
            "md",
            "semibold",
            "demibold",
            "smbld",
            "sembd",
            "bold",
            "bd",
            "extrabold",
            "ultrabold",
            "extbd",
            "black",
            "blk",
            "heavy",
            "italic",
            "oblique");

    /** Math and symbol fonts (TeX's, MathJax's, dingbats): no letters, so not an editor font. */
    private static final Pattern SYMBOL_FONT = Pattern.compile(
            "(?i)^(mathjax_.*|esint\\d*|cm(ex|sy|mi|r|tt)\\d*|ms(am|bm)\\d*|wasy\\d*|stmary\\d*|rsfs\\d*"
                    + "|.*dingbats.*|symbol|opensymbol|.*emoji.*)$");

    private FontChoices() {}

    /** The picker's choices on this machine. */
    static List<String> forThisMachine() {
        Fonts.ensureAllLoaded(); // the deferred families must be registered before the picker lists them
        return choices(Fonts.BUNDLED, monospaceFamilies());
    }

    /**
     * {@code bundled} in order, then each of {@code system} that is not bundled, not a weight or style of
     * another listed family, and not a symbol font. Pure.
     */
    static List<String> choices(List<String> bundled, List<String> system) {
        Set<String> all = new LinkedHashSet<>(bundled);
        all.addAll(system);
        Set<String> lower = new java.util.HashSet<>();
        all.forEach(f -> lower.add(f.toLowerCase(Locale.ROOT)));
        List<String> out = new ArrayList<>(bundled);
        for (String family : system) {
            if (!out.contains(family) && !SYMBOL_FONT.matcher(family.strip()).matches() && !isVariant(family, lower)) {
                out.add(family);
            }
        }
        return out;
    }

    /** Whether {@code family} is {@code <listed family> <weight words…>}. */
    private static boolean isVariant(String family, Set<String> listedLower) {
        String[] words = family.strip().split("\\s+");
        for (int cut = words.length - 1; cut >= 1; cut--) {
            String tail = words[cut].toLowerCase(Locale.ROOT);
            if (!WEIGHTS.contains(tail)) {
                return false;
            }
            String base = String.join(" ", java.util.Arrays.copyOf(words, cut)).toLowerCase(Locale.ROOT);
            if (listedLower.contains(base)) {
                return true;
            }
        }
        return false;
    }

    private static List<String> monospaceFamilies() {
        Text narrow = new Text("iiiiiiiiii");
        Text wide = new Text("WWWWWWWWWW");
        List<String> families = new ArrayList<>();
        for (String family : Font.getFamilies()) {
            Font font = Font.font(family, 14);
            if (font == null) {
                continue;
            }
            narrow.setFont(font);
            wide.setFont(font);
            if (Math.abs(narrow.getLayoutBounds().getWidth()
                            - wide.getLayoutBounds().getWidth())
                    < 0.5) {
                families.add(family);
            }
        }
        return families;
    }
}

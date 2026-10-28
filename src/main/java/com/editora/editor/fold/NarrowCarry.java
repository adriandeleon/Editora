package com.editora.editor.fold;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import com.editora.editor.highlight.TextMateHighlighter.Symbol;
import com.editora.text.FoldRegions;
import com.editora.text.FoldRegions.Region;
import org.fxmisc.richtext.CodeArea;
import org.fxmisc.richtext.model.StyledDocument;
import org.fxmisc.richtext.model.TwoDimensional.Bias;
import org.jspecify.annotations.Nullable;

/**
 * What a narrow/widen text swap carries across so the view stays as it was. The swap replaces the whole area,
 * and a plain-text replace left the text unstyled, the fold gutter on the old lines and the outline nameless
 * until the debounced highlight and fold passes came back — a quarter second after narrowing and over half a
 * second after widening a 1,600-line file, which is what made the Structure window's narrow toggle feel slow.
 * Carrying the styled text, the symbols and the fold regions (re-based by line) makes the swap look finished
 * at once; those passes still run and correct whatever an edit inside the region changed.
 *
 * <p>{@code firstLine}/{@code lastLine} are the document lines of the region's first and last paragraph when it
 * was narrowed.
 */
public final class NarrowCarry {

    /**
     * The text held aside while narrowed (the document before and after the region, plain for {@code getContent}
     * and styled for the swap back), the region as it was narrowed, and the whole document's symbols and fold
     * regions then.
     */
    public record Held(
            String prefixText,
            String suffixText,
            StyledDocument<Collection<String>, String, Collection<String>> prefix,
            StyledDocument<Collection<String>, String, Collection<String>> region,
            StyledDocument<Collection<String>, String, Collection<String>> suffix,
            List<Symbol> symbols,
            List<Region> regions,
            int firstLine,
            int lastLine) {}

    private NarrowCarry() {}

    /**
     * What narrowing {@code area}, which holds the whole document ({@code text}), to {@code [start, end)} sets
     * aside: the text around the region, and the whole document's symbols and fold regions. Call before
     * {@link #narrow}.
     */
    public static Held hold(
            CodeArea area, String text, int start, int end, List<Symbol> symbols, List<Region> regions) {
        // The region's last paragraph is the line end is on (an empty one when the region ends after a newline).
        int firstLine = area.offsetToPosition(start, Bias.Forward).getMajor();
        int lastLine = area.offsetToPosition(end, Bias.Forward).getMajor();
        return new Held(
                text.substring(0, start),
                text.substring(end),
                area.subDocument(0, start),
                area.subDocument(start, end),
                area.subDocument(end, area.getLength()),
                symbols,
                regions,
                firstLine,
                lastLine);
    }

    /** The whole document: {@code region} (what the area holds) with the held text around it, if narrowed. */
    public static String wholeText(@Nullable Held held, String region) {
        return held == null ? region : held.prefixText() + region + held.suffixText();
    }

    /** Where the region starts in the whole document: 0 when not narrowed. */
    public static int regionStart(@Nullable Held held) {
        return held == null ? 0 : held.prefixText().length();
    }

    /** The length of the text held aside: 0 when not narrowed. */
    public static int heldLength(@Nullable Held held) {
        return held == null ? 0 : held.prefixText().length() + held.suffixText().length();
    }

    /** Narrows {@code area} to {@code [start, end)}, keeping the region's styles. */
    public static void narrow(CodeArea area, int start, int end) {
        area.replace(0, area.getLength(), area.subDocument(start, end));
    }

    /**
     * Puts the held text back around the region {@code area} holds, keeping every part's styles. An unedited
     * region comes back as it was narrowed: highlighted alone, without the lines above it, it can have lost a
     * colour that depends on them (a method parameter outside its class).
     */
    public static void widen(CodeArea area, Held held) {
        String regionNow = area.getText();
        var region = unedited(held, regionNow) ? held.region() : area.subDocument(0, area.getLength());
        area.replace(0, area.getLength(), held.prefix().concat(region).concat(held.suffix()));
    }

    /** The held symbols on the region's lines, region-relative. */
    public static List<Symbol> narrowSymbols(Held held) {
        List<Symbol> out = new ArrayList<>();
        for (Symbol s : held.symbols()) {
            if (s.line() >= held.firstLine() && s.line() <= held.lastLine()) {
                out.add(new Symbol(s.line() - held.firstLine(), s.name(), s.kind()));
            }
        }
        return out;
    }

    /** The held fold regions wholly inside the region, region-relative: the area cannot show the enclosing ones. */
    public static List<Region> narrowRegions(Held held) {
        List<Region> out = new ArrayList<>();
        for (Region r : held.regions()) {
            if (r.startLine() >= held.firstLine() && r.endLine() <= held.lastLine()) {
                out.add(new Region(r.startLine() - held.firstLine(), r.endLine() - held.firstLine()));
            }
        }
        return out;
    }

    /**
     * The whole document's symbols after widening ({@code regionNow}: the text the area holds): the held ones when
     * the region was not edited, else the held ones before and after the region (those after it moved by the
     * lines the region gained or lost) around the region's current ones.
     */
    public static List<Symbol> widenSymbols(Held held, CharSequence regionNow, List<Symbol> region) {
        if (unedited(held, regionNow)) {
            return held.symbols(); // exact: the region's own pass saw it without the lines above it
        }
        int delta = lines(regionNow) - (held.lastLine() - held.firstLine() + 1);
        List<Symbol> out = new ArrayList<>();
        for (Symbol s : held.symbols()) {
            if (s.line() < held.firstLine()) {
                out.add(s);
            }
        }
        for (Symbol s : region) {
            out.add(new Symbol(s.line() + held.firstLine(), s.name(), s.kind()));
        }
        for (Symbol s : held.symbols()) {
            if (s.line() > held.lastLine()) {
                out.add(new Symbol(s.line() + delta, s.name(), s.kind()));
            }
        }
        return out;
    }

    /**
     * The whole document's fold regions after widening: the held ones when the region was not edited, else the
     * region's current ones and the held ones that were not wholly inside it with their lines moved past the region by what it gained or lost (an enclosing block
     * keeps its start and grows or shrinks with the region).
     */
    public static List<Region> widenRegions(Held held, CharSequence regionNow, List<Region> region) {
        if (unedited(held, regionNow)) {
            return held.regions();
        }
        int regionLines = lines(regionNow);
        int first = held.firstLine();
        int last = held.lastLine();
        int delta = regionLines - (last - first + 1);
        int newLast = first + Math.max(0, regionLines - 1);
        List<Region> out = new ArrayList<>();
        for (Region r : held.regions()) {
            if (r.startLine() >= first && r.endLine() <= last) {
                continue; // the region's own: the area's current ones replace them
            }
            int start = shift(r.startLine(), first, last, delta, newLast);
            int end = shift(r.endLine(), first, last, delta, newLast);
            if (start < end) {
                out.add(new Region(start, end));
            }
        }
        for (Region r : region) {
            out.add(new Region(r.startLine() + first, r.endLine() + first));
        }
        return FoldRegions.canonicalOrder(out);
    }

    /** The paragraphs {@code text} makes in an area: one more than its line breaks. */
    private static int lines(CharSequence text) {
        int lines = 1;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\n') {
                lines++;
            }
        }
        return lines;
    }

    private static boolean unedited(Held held, CharSequence regionNow) {
        return held.region().getText().contentEquals(regionNow);
    }

    private static int shift(int line, int first, int last, int delta, int newLast) {
        return line < first ? line : line > last ? line + delta : Math.min(line, newLast);
    }
}

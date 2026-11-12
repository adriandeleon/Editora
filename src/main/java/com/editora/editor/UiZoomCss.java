package com.editora.editor;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Locale;
import java.util.Set;

/**
 * Scales a stylesheet for the UI zoom: every length the sheet fixes (a font size, a padding, a radius, a
 * minimum height) is multiplied, and nothing else is touched. JavaFX reads a bare number as pixels, so "8" and
 * "8px" are both lengths; an {@code em} or a percentage is relative already and follows the root font size,
 * which is one of the lengths scaled. Pure text work, with no toolkit.
 */
final class UiZoomCss {

    private UiZoomCss() {}

    /** Properties whose numbers are lengths, beyond those the suffix rule in {@link #isLength} finds. */
    private static final Set<String> LENGTH_PROPERTIES = Set.of(
            "-fx-effect",
            "-fx-font",
            "-fx-size",
            "-fx-indent",
            "-fx-stroke-dash-array",
            "-fx-color-rect-x",
            "-fx-color-rect-y",
            "-fx-translate-x",
            "-fx-translate-y");

    /** Suffixes of a {@code -fx-} property that takes lengths: {@code -fx-pref-width}, {@code -fx-padding}. */
    private static final String[] LENGTH_SUFFIXES = {
        "padding", "insets", "radius", "width", "height", "size", "spacing", "gap", "length"
    };

    /** Properties whose hairlines stay one pixel: a 1 px border at 150% is still a line, not a 2 px bar. */
    private static final Set<String> HAIRLINE_PROPERTIES =
            Set.of("-fx-border-width", "-fx-background-insets", "-fx-border-insets");

    /** Properties the suffix rule would take for lengths and that are counts: a tab's width in spaces. */
    private static final Set<String> NOT_LENGTHS = Set.of("-fx-tab-size");

    /** Whether the numbers of {@code property}'s value are lengths. Pure. */
    static boolean isLength(String property) {
        String name = property.toLowerCase(Locale.ROOT);
        if (LENGTH_PROPERTIES.contains(name)) {
            return true;
        }
        if (NOT_LENGTHS.contains(name)) {
            return false;
        }
        if (!name.startsWith("-fx-")) {
            return false; // a looked-up colour, an -rtfx- property of the editor's text, plain web CSS
        }
        for (String suffix : LENGTH_SUFFIXES) {
            if (name.endsWith(suffix)) {
                return true;
            }
        }
        return false;
    }

    /** A whole stylesheet at {@code factor}: the declarations inside its rules, scaled. Pure. */
    static String scaleSheet(String css, double factor) {
        return scale(css, factor, false);
    }

    /** An inline style ({@code "-fx-padding: 4 8; -fx-font-size: 11px;"}) at {@code factor}. Pure. */
    static String scaleDeclarations(String style, double factor) {
        return scale(style, factor, true);
    }

    private static String scale(String css, double factor, boolean inline) {
        if (factor == 1.0 || css.isEmpty()) {
            return css;
        }
        StringBuilder out = new StringBuilder(css.length() + 512);
        int n = css.length();
        int depth = inline ? 1 : 0;
        int i = 0;
        boolean atDeclarationStart = inline;
        while (i < n) {
            char c = css.charAt(i);
            if (c == '/' && i + 1 < n && css.charAt(i + 1) == '*') {
                int end = css.indexOf("*/", i + 2);
                end = end < 0 ? n : end + 2;
                out.append(css, i, end);
                i = end;
            } else if (c == '"' || c == '\'') {
                int end = endOfString(css, i);
                out.append(css, i, end);
                i = end;
            } else if (c == '{') {
                depth++;
                atDeclarationStart = true;
                out.append(c);
                i++;
            } else if (c == '}') {
                depth = Math.max(0, depth - 1);
                atDeclarationStart = depth > 0;
                out.append(c);
                i++;
            } else if (c == ';') {
                atDeclarationStart = depth > 0;
                out.append(c);
                i++;
            } else if (atDeclarationStart && depth > 0 && isNameChar(c)) {
                int nameEnd = i;
                while (nameEnd < n && isNameChar(css.charAt(nameEnd))) {
                    nameEnd++;
                }
                int colon = nameEnd;
                while (colon < n && Character.isWhitespace(css.charAt(colon))) {
                    colon++;
                }
                atDeclarationStart = false;
                if (colon >= n || css.charAt(colon) != ':') {
                    out.append(css, i, nameEnd); // a nested selector, not a declaration
                    i = nameEnd;
                    continue;
                }
                String property = css.substring(i, nameEnd);
                int valueEnd = endOfValue(css, colon + 1);
                out.append(css, i, colon + 1);
                String value = css.substring(colon + 1, valueEnd);
                out.append(isLength(property) ? scaleValue(property, value, factor) : value);
                i = valueEnd;
            } else {
                if (!Character.isWhitespace(c)) {
                    atDeclarationStart = false;
                }
                out.append(c);
                i++;
            }
        }
        return out.toString();
    }

    private static boolean isNameChar(char c) {
        return c == '-' || c == '_' || Character.isLetterOrDigit(c);
    }

    /** The index just past the string that opens at {@code start}. */
    private static int endOfString(String css, int start) {
        char quote = css.charAt(start);
        int i = start + 1;
        while (i < css.length()) {
            char c = css.charAt(i);
            if (c == '\\') {
                i += 2;
            } else if (c == quote) {
                return i + 1;
            } else {
                i++;
            }
        }
        return css.length();
    }

    /** The index of the {@code ;} or {@code }} that ends the value starting at {@code start}. */
    private static int endOfValue(String css, int start) {
        int parens = 0;
        int i = start;
        while (i < css.length()) {
            char c = css.charAt(i);
            if (c == '"' || c == '\'') {
                i = endOfString(css, i);
                continue;
            }
            if (c == '(') {
                parens++;
            } else if (c == ')') {
                parens = Math.max(0, parens - 1);
            } else if (parens == 0 && (c == ';' || c == '}')) {
                return i;
            }
            i++;
        }
        return css.length();
    }

    /** One function being read: its name and which comma-separated argument the reader is in. */
    private static final class Call {
        final String name;
        int argument;

        Call(String name) {
            this.name = name;
        }
    }

    /** {@code value} with its lengths multiplied by {@code factor}. Pure. */
    static String scaleValue(String property, String value, double factor) {
        String name = property.toLowerCase(Locale.ROOT);
        boolean hairlines = HAIRLINE_PROPERTIES.contains(name);
        boolean pixelsOnly = name.equals("-fx-font"); // "bold 14px Inter": the weight is a bare number
        // -1 is "computed" for a width or a height, not a length.
        boolean computedSentinel = !hairlines && (name.endsWith("width") || name.endsWith("height"));
        StringBuilder out = new StringBuilder(value.length() + 16);
        Deque<Call> calls = new ArrayDeque<>();
        int n = value.length();
        int i = 0;
        while (i < n) {
            char c = value.charAt(i);
            if (c == '"' || c == '\'') {
                int end = endOfString(value, i);
                out.append(value, i, end);
                i = end;
            } else if (c == '(') {
                calls.push(new Call(wordBefore(value, i)));
                out.append(c);
                i++;
            } else if (c == ')') {
                if (!calls.isEmpty()) {
                    calls.pop();
                }
                out.append(c);
                i++;
            } else if (c == ',') {
                if (!calls.isEmpty()) {
                    calls.peek().argument++;
                }
                out.append(c);
                i++;
            } else if (startsNumber(value, i)) {
                int end = i + 1;
                while (end < n && (Character.isDigit(value.charAt(end)) || value.charAt(end) == '.')) {
                    end++;
                }
                int unitEnd = end;
                while (unitEnd < n && (Character.isLetter(value.charAt(unitEnd)) || value.charAt(unitEnd) == '%')) {
                    unitEnd++;
                }
                String number = value.substring(i, end);
                String unit = value.substring(end, unitEnd);
                boolean pixels = unit.equals("px") || (unit.isEmpty() && !pixelsOnly);
                if (pixels && scalable(calls) && isNumber(number)) {
                    double v = Double.parseDouble(number);
                    boolean kept = (hairlines && Math.abs(v) <= 1.0) || (computedSentinel && v == -1.0);
                    if (!kept) {
                        number = format(v * factor);
                    }
                }
                out.append(number).append(unit);
                i = unitEnd;
            } else if (isNameChar(c) || c == '#') {
                // A word or a colour: copied whole, so the digits of "#1b1e2a" or "-color-base-4" are not numbers.
                int end = i + 1;
                while (end < n && (isNameChar(value.charAt(end)) || value.charAt(end) == '.')) {
                    end++;
                }
                out.append(value, i, end);
                i = end;
            } else {
                out.append(c);
                i++;
            }
        }
        return out.toString();
    }

    /** Whether a number where the reader stands is a length: at the top level, or a shadow's radius or offset. */
    private static boolean scalable(Deque<Call> calls) {
        if (calls.isEmpty()) {
            return true;
        }
        if (calls.size() > 1) {
            return false; // inside a colour inside a shadow
        }
        Call call = calls.peek();
        if (call.name.equals("dropshadow") || call.name.equals("innershadow")) {
            // (blur type, colour, radius, spread, offset x, offset y): the spread is a ratio.
            return call.argument == 2 || call.argument == 4 || call.argument == 5;
        }
        // Any other function holds colours, stops or a path (rgba, derive, ladder, a gradient, url, segments).
        return call.name.isEmpty();
    }

    private static String wordBefore(String value, int paren) {
        int start = paren;
        while (start > 0 && isNameChar(value.charAt(start - 1))) {
            start--;
        }
        return value.substring(start, paren).toLowerCase(Locale.ROOT);
    }

    private static boolean startsNumber(String value, int i) {
        char c = value.charAt(i);
        int n = value.length();
        if (Character.isDigit(c)) {
            return true;
        }
        if (c == '.') {
            return i + 1 < n && Character.isDigit(value.charAt(i + 1));
        }
        if (c == '-' || c == '+') {
            // A sign opens a number only after a separator: "-3" is one, the dash of "-color-bg" is not.
            boolean afterSeparator = i == 0
                    || Character.isWhitespace(value.charAt(i - 1))
                    || value.charAt(i - 1) == ','
                    || value.charAt(i - 1) == '(';
            return afterSeparator
                    && i + 1 < n
                    && (Character.isDigit(value.charAt(i + 1))
                            || (value.charAt(i + 1) == '.' && i + 2 < n && Character.isDigit(value.charAt(i + 2))));
        }
        return false;
    }

    private static boolean isNumber(String s) {
        int dots = 0;
        int digits = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '.') {
                dots++;
            } else if (Character.isDigit(c)) {
                digits++;
            } else if (!(i == 0 && (c == '-' || c == '+'))) {
                return false;
            }
        }
        return digits > 0 && dots <= 1;
    }

    /** A length to the hundredth of a pixel, without a trailing ".0". */
    static String format(double v) {
        double rounded = Math.round(v * 100) / 100.0;
        if (rounded == Math.rint(rounded)) {
            return Long.toString((long) rounded);
        }
        return String.format(Locale.ROOT, "%s", rounded);
    }
}

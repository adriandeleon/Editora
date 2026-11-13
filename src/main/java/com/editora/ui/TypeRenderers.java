package com.editora.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.editora.dap.TypeRenderer;
import org.jspecify.annotations.Nullable;

/**
 * Pure: which {@link TypeRenderer} a value's type has, and its expressions made into the value's own — {@code this}
 * replaced by the expression that gives the value.
 */
final class TypeRenderers {

    private TypeRenderers() {}

    /** A child a renderer shows: its name and expression. */
    record Child(String name, String expression) {}

    /**
     * The first enabled renderer of {@code language} (or of every language) for a value of type {@code type} (the
     * adapter's type name; {@code value} names the class when the adapter types every object {@code object}, as
     * js-debug does). Types match by their own name — generics and pointers aside — and, when both name one, by what
     * qualifies them ({@code com.shop.Money} matches {@code Money} and {@code shop.Money}, not {@code bank.Money}).
     * A subclass is not its parent's type: the adapter names the value's own class.
     */
    static @Nullable TypeRenderer match(
            List<TypeRenderer> renderers, String language, @Nullable String type, @Nullable String value) {
        DebugTypeSource.TypeName shown = DebugTypeSource.parse(type, value);
        if (shown == null) {
            return null;
        }
        for (TypeRenderer r : renderers) {
            if (!r.isEnabled() || !(r.getLanguage().isBlank() || r.getLanguage().equals(language))) {
                continue;
            }
            if (!r.getValue().isBlank() || !r.getChildren().isBlank()) {
                DebugTypeSource.TypeName wanted = DebugTypeSource.parse(r.getType(), null);
                if (wanted != null && sameType(wanted, shown)) {
                    return r;
                }
            }
        }
        return null;
    }

    private static boolean sameType(DebugTypeSource.TypeName wanted, DebugTypeSource.TypeName shown) {
        if (!wanted.simple().equals(shown.simple())) {
            return false;
        }
        return wanted.qualifier().isEmpty()
                || shown.qualifier().isEmpty()
                || DebugTypeSource.sameQualifier(wanted.qualifier(), shown.qualifier());
    }

    /** {@code this} and {@code self} as words: not a part of another name, a member ({@code x.this}) or a string. */
    private static final Pattern SELF = Pattern.compile("(?<![\\p{L}\\p{N}_$.])(?:this|self)(?![\\p{L}\\p{N}_$])");

    /**
     * {@code expression} with every {@code this} (or {@code self}) outside a string or character literal replaced by
     * {@code target} in parentheses: {@code this.amount} for the value {@code order.total} is {@code
     * (order.total).amount}.
     */
    static String substitute(String expression, String target) {
        StringBuilder out = new StringBuilder();
        int i = 0;
        int code = 0; // where the code since the last literal starts
        while (i < expression.length()) {
            char c = expression.charAt(i);
            if (c == '"' || c == '\'' || c == '`') {
                out.append(replaceIn(expression.substring(code, i), target));
                int end = literalEnd(expression, i);
                out.append(expression, i, end);
                i = end;
                code = end;
                continue;
            }
            i++;
        }
        out.append(replaceIn(expression.substring(code), target));
        return out.toString();
    }

    private static String replaceIn(String code, String target) {
        Matcher m = SELF.matcher(code);
        return m.replaceAll(Matcher.quoteReplacement("(" + target + ")"));
    }

    /** Where the literal opened by the quote at {@code start} ends (past its closing quote, or the text's end). */
    private static int literalEnd(String s, int start) {
        char quote = s.charAt(start);
        int i = start + 1;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == '\\') {
                i += 2;
                continue;
            }
            if (c == quote) {
                return i + 1;
            }
            i++;
        }
        return s.length();
    }

    /** {@code name = expression}: the first {@code =} that is not part of {@code ==}, {@code <=}, {@code !=}…. */
    private static final Pattern CHILD = Pattern.compile("^\\s*([^=<>!]+?)\\s*=(?!=)\\s*(.+?)\\s*$");

    /**
     * The children a renderer lists, one a line: {@code name = expression}, or a bare expression named by itself.
     * Blank lines and lines starting with {@code #} or {@code //} are skipped.
     */
    static List<Child> children(String text) {
        List<Child> out = new ArrayList<>();
        if (text == null) {
            return out;
        }
        for (String line : text.split("\\R")) {
            String l = line.strip();
            if (l.isEmpty() || l.startsWith("#") || l.startsWith("//")) {
                continue;
            }
            Matcher m = CHILD.matcher(l);
            if (m.matches() && isName(m.group(1))) {
                out.add(new Child(m.group(1).strip(), m.group(2)));
            } else {
                out.add(new Child(l, l));
            }
        }
        return out;
    }

    /** Whether the text before an {@code =} reads as a child's name, not code ({@code a.b}, {@code f(x)}). */
    private static boolean isName(String s) {
        String t = s.strip();
        return !t.isEmpty() && t.chars().noneMatch(ch -> ch == '(' || ch == '[' || ch == '"' || ch == '\'');
    }

    /** A call, an assignment or an increment: what evaluating twice could do twice. */
    private static final Pattern SIDE_EFFECT =
            Pattern.compile("[(]|(?<![=!<>])=(?!=)|\\+\\+|--|\\bnew\\b|\\bdelete\\b");

    /**
     * Whether evaluating {@code expression} again cannot change the program: no call, assignment, increment,
     * {@code new} or {@code delete} outside its string literals. A typed watch, hover or evaluation is rendered only
     * then, since its renderer evaluates it again.
     */
    static boolean safeToRepeat(String expression) {
        StringBuilder code = new StringBuilder();
        int i = 0;
        while (i < expression.length()) {
            char c = expression.charAt(i);
            if (c == '"' || c == '\'' || c == '`') {
                i = literalEnd(expression, i);
                code.append(' ');
                continue;
            }
            code.append(c);
            i++;
        }
        return !SIDE_EFFECT.matcher(code).find();
    }

    /**
     * Where the renderer for a value of {@code type} of {@code language} is in {@code renderers}, on or off, with or
     * without expressions (the one Customize Rendering… edits); -1 for none.
     */
    static int indexOf(List<TypeRenderer> renderers, String language, @Nullable String type, @Nullable String value) {
        DebugTypeSource.TypeName shown = DebugTypeSource.parse(type, value);
        if (shown == null) {
            return -1;
        }
        for (int i = 0; i < renderers.size(); i++) {
            TypeRenderer r = renderers.get(i);
            DebugTypeSource.TypeName wanted = DebugTypeSource.parse(r.getType(), null);
            if ((r.getLanguage().isBlank() || r.getLanguage().equals(language))
                    && wanted != null
                    && sameType(wanted, shown)) {
                return i;
            }
        }
        return -1;
    }

    /** The type a new renderer names: as qualified as the adapter named it ({@code com.shop.Money}, {@code app::Point}). */
    static String typeName(DebugTypeSource.TypeName name, @Nullable String type) {
        if (name.qualifier().isEmpty()) {
            return name.simple();
        }
        String separator = type != null && type.contains("::") ? "::" : ".";
        return name.qualifier() + separator + name.simple();
    }

    /**
     * A renderer's value without the quotes a debugger puts around a string ({@code "12.50 EUR"}, Python's {@code
     * '12.50 EUR'}): it reads as the value's label. Anything else as given.
     */
    static String unquote(String result) {
        if (result.length() >= 2) {
            char first = result.charAt(0);
            if ((first == '"' || first == '\'') && result.charAt(result.length() - 1) == first) {
                return result.substring(1, result.length() - 1);
            }
        }
        return result;
    }

    /** A renderer's one-line summary, as the Settings list shows it: {@code Money (java)}. */
    static String label(TypeRenderer r) {
        String type = r.getType().isBlank() ? "?" : r.getType();
        return r.getLanguage().isBlank() ? type : type + " (" + r.getLanguage() + ")";
    }
}

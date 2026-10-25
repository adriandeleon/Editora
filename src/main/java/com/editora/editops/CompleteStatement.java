package com.editora.editops;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Set;
import java.util.function.IntFunction;
import java.util.regex.Pattern;

import org.jspecify.annotations.Nullable;

/**
 * Complete Current Statement (IntelliJ's Ctrl+Shift+Enter) for brace languages, as the edit it makes for one
 * caret. At the end of the caret's line it closes an open string and the brackets the statement left open, then
 * finishes the statement the way its first words ask:
 *
 * <ul>
 *   <li>a control header ({@code if (…)}, {@code for (…)}, {@code catch (…)}, {@code else}, {@code try}…), a
 *       type or a method declaration gets a {@code { }} block with the caret on its empty body line;
 *   <li>a method with no body to give ({@code abstract}, {@code native}, or one in an interface) and any other
 *       statement get a {@code ;} (in {@link Style#NO_SEMICOLON} languages, only the closers);
 *   <li>a line that ends in an open {@code {}, or in an empty {@code {}} block, opens its body;
 *   <li>a statement that is already complete, an annotation, a label or an expression that is still going
 *       ({@code x =}, {@code a &&}) gets a new line below, indented as Enter there would indent it.
 * </ul>
 *
 * <p>The scan reads the text from the start up to the end of the caret's line, so brackets opened on earlier
 * lines of the statement are closed too. Strings, character literals and comments are skipped; inside a block
 * comment or a text block nothing is completed. Pure; the result is an {@link Indenter.TabEdit}.
 */
public final class CompleteStatement {

    private CompleteStatement() {}

    /** Whether statements end in {@code ;}. */
    public enum Style {
        SEMICOLON,
        NO_SEMICOLON
    }

    private static final Set<String> SEMICOLON_LANGUAGES = Set.of(
            "java", "c", "cpp", "csharp", "javascript", "javascriptreact", "typescript", "typescriptreact", "php");

    private static final Set<String> NO_SEMICOLON_LANGUAGES = Set.of("kotlin");

    /** The style for {@code language} (a {@code LanguageRegistry} name), or null for a language it does not know. */
    public static @Nullable Style styleFor(String language) {
        if (SEMICOLON_LANGUAGES.contains(language)) {
            return Style.SEMICOLON;
        }
        return NO_SEMICOLON_LANGUAGES.contains(language) ? Style.NO_SEMICOLON : null;
    }

    /** A control header whose parentheses are complete. */
    private static final Pattern CONTROL = Pattern.compile(
            "(?:else\\s+)?(?:if|while|for|foreach|switch|synchronized|catch|using|lock|when)\\s*\\(.*\\)",
            Pattern.DOTALL);

    /** A keyword that takes a block of its own. */
    private static final Pattern BARE_BLOCK = Pattern.compile("else|try|do|finally|static|init");

    private static final Pattern TRY_RESOURCES = Pattern.compile("try\\s*\\(.*\\)", Pattern.DOTALL);

    private static final String MODIFIERS = "(?:(?:@[\\w.]+(?:\\(.*?\\))?|public|protected|private|internal|static"
            + "|final|abstract|synchronized|native|default|strictfp|override|virtual|async|sealed|extern|inline"
            + "|unsafe|open|data|inner|suspend|operator|infix|tailrec|export)\\s+)";

    /** A class, interface, enum, record, struct, namespace or Kotlin object, with whatever follows its name. */
    private static final Pattern TYPE_DECL = Pattern.compile(
            MODIFIERS + "*(?:enum\\s+)?(?:class|interface|enum|record|struct|namespace|object|@interface)\\s+\\w[^=]*",
            Pattern.DOTALL);

    /** {@code [modifiers] Type name(params) [throws …]}, or a constructor's {@code modifiers Name(params)}. */
    private static final Pattern METHOD_DECL = Pattern.compile(
            MODIFIERS
                    + "*(?:<[^()]*>\\s+)?(?:[\\w.$]+(?:<[^()]*>)?(?:\\[\\])*[*&]?\\s+[*&]?)?[\\w$~]+\\s*\\(.*\\)"
                    + "(?:\\s*const)?(?:\\s*throws\\s+[\\w.$,\\s]+)?",
            Pattern.DOTALL);

    /** A JavaScript, TypeScript or PHP function, or a Kotlin {@code fun}. */
    private static final Pattern FUNCTION = Pattern.compile(
            MODIFIERS + "*(?:function\\s*\\*?|fun)\\s*(?:<[^()]*>\\s*)?[\\w$.]*\\s*\\(.*\\)(?:\\s*:\\s*[^={]+)?",
            Pattern.DOTALL);

    /** Words that start a statement, never a declaration. */
    private static final Pattern NOT_A_DECL = Pattern.compile(
            "(?:return|new|throw|else|yield|case|await|delete|typeof|goto|echo|print)\\b.*", Pattern.DOTALL);

    private static final Pattern ANNOTATIONS = Pattern.compile("(?:@[\\w.]+(?:\\(.*\\))?\\s*)+", Pattern.DOTALL);

    private static final Pattern LABEL = Pattern.compile("(?:case\\b.*|default|[\\w$]+)\\s*:", Pattern.DOTALL);

    /** An expression that is still going: the next line continues it. */
    private static final Pattern UNFINISHED =
            Pattern.compile(".*(?:[=+\\-*/%&|^!<>?:,.]|->|=>|\\bnew|\\breturn\\s+new)", Pattern.DOTALL);

    /** One open bracket. A statement brace opens a body of statements; any other bracket is part of one. */
    private record Open(
            char ch,
            int pos,
            boolean statementBrace,
            boolean blockHeader,
            String owner,
            String indent,
            String savedHead,
            int savedFirstCode) {}

    /**
     * The edit for one caret (the end of {@code [selStart, selEnd)}); {@code enterAt} is the language's Enter
     * at an offset, for a statement there is nothing to add to.
     */
    public static Indenter.TabEdit edit(
            String text, int selStart, int selEnd, Style style, String unit, IntFunction<Indenter.EnterEdit> enterAt) {
        int caret = Math.max(0, Math.min(Math.max(selStart, selEnd), text.length()));
        int ls = TextNav.lineStart(text, caret);
        int le = text.indexOf('\n', caret);
        le = le < 0 ? text.length() : le;
        Scan s = scan(text, ls, le);
        if (s == null || s.lastCode < ls) { // inside a comment or a text block, or no code on the line
            return newLine(text, caret, enterAt);
        }
        StringBuilder closers = new StringBuilder();
        if (s.openQuote != 0) {
            closers.append(s.openQuote);
        }
        for (Open o : s.stack) { // top first
            if (o.statementBrace) {
                break;
            }
            closers.append(o.ch == '(' ? ')' : o.ch == '[' ? ']' : '}');
        }
        String head = s.head.toString().strip();
        String h = (head + closers).replaceAll("\\s+", " ").strip();
        if (h.isEmpty()) {
            return emptyHead(text, s, le, enterAt, caret, unit);
        }
        Open enclosing =
                s.stack.stream().filter(Open::statementBrace).findFirst().orElse(null);
        String indent = s.firstCode < 0 ? leading(text, ls) : leading(text, TextNav.lineStart(text, s.firstCode));
        Kind kind = classify(h, style, enclosing, s.closedOwner);
        int end = s.codeEnd;
        switch (kind) {
            case BLOCK -> {
                String rest = text.substring(end, le).stripTrailing();
                String open = closers + " {" + rest + "\n" + indent + unit;
                int at = end + open.length();
                return new Indenter.TabEdit(end, le, open + "\n" + indent + "}", at, at);
            }
            case STATEMENT -> {
                String add = closers + (style == Style.SEMICOLON ? ";" : "");
                if (head.endsWith(";") && closers.length() > 0) { // `foo(a;`: the ; goes after the closers
                    String moved = closers + ";";
                    int at = s.lastCode + moved.length();
                    return new Indenter.TabEdit(s.lastCode, end, moved, at, at);
                }
                if (head.endsWith(";") || add.isEmpty()) {
                    return newLine(text, caret, enterAt);
                }
                int at = end + add.length();
                return new Indenter.TabEdit(end, end, add, at, at);
            }
            default -> {
                return newLine(text, caret, enterAt);
            }
        }
    }

    private enum Kind {
        BLOCK,
        STATEMENT,
        NEW_LINE
    }

    private static Kind classify(String h, Style style, @Nullable Open enclosing, @Nullable String closedOwner) {
        if (h.startsWith("while")
                && "do".equals(closedOwner)
                && CONTROL.matcher(h).matches()) {
            return Kind.STATEMENT; // the `while (…)` of a do-while
        }
        if (CONTROL.matcher(h).matches()
                || BARE_BLOCK.matcher(h).matches()
                || TRY_RESOURCES.matcher(h).matches()) {
            return Kind.BLOCK;
        }
        if (NOT_A_DECL.matcher(h).matches()) {
            return unfinished(h) ? Kind.NEW_LINE : Kind.STATEMENT;
        }
        if (TYPE_DECL.matcher(h).matches()) {
            return Kind.BLOCK;
        }
        if (FUNCTION.matcher(h).matches() || isMethodDecl(h)) {
            boolean inInterface = enclosing != null
                    && enclosing.owner.matches("(?s).*\\binterface\\b.*")
                    && !h.matches("(?s).*\\b(?:default|static|private)\\b.*");
            boolean noBody = h.matches("(?s).*\\b(?:abstract|native)\\b.*") || inInterface;
            return noBody && style == Style.SEMICOLON ? Kind.STATEMENT : Kind.BLOCK;
        }
        if (ANNOTATIONS.matcher(h).matches() || LABEL.matcher(h).matches() || unfinished(h)) {
            return Kind.NEW_LINE;
        }
        return Kind.STATEMENT;
    }

    /** {@link #METHOD_DECL}, with at least a type or a modifier before the name (so a call is not one). */
    private static boolean isMethodDecl(String h) {
        if (!METHOD_DECL.matcher(h).matches()) {
            return false;
        }
        int paren = h.indexOf('(');
        String before = h.substring(0, paren).strip();
        return before.contains(" ") && !before.contains("=") && !before.endsWith(".");
    }

    private static boolean unfinished(String h) {
        return UNFINISHED.matcher(h).matches() && !h.endsWith("++") && !h.endsWith("--");
    }

    /** No statement on the line: open the body of a `{` it ends in, or of an empty `{}`, else a new line. */
    private static Indenter.TabEdit emptyHead(
            String text, Scan s, int le, IntFunction<Indenter.EnterEdit> enterAt, int caret, String unit) {
        Open top = s.stack.peek();
        int ls = TextNav.lineStart(text, caret);
        if (top != null && top.statementBrace && top.pos == s.lastCode && top.pos >= ls) {
            String body = "\n" + top.indent + unit;
            String close = closedLater(text, le) ? "" : "\n" + top.indent + "}";
            int at = le + body.length();
            return new Indenter.TabEdit(le, le, body + close, at, at);
        }
        if (s.emptyOpen >= ls && s.emptyClose == s.lastCode) {
            String body = "\n" + s.emptyIndent + unit;
            int at = s.emptyOpen + 1 + body.length();
            return new Indenter.TabEdit(s.emptyOpen + 1, s.emptyClose, body + "\n" + s.emptyIndent, at, at);
        }
        return newLine(text, caret, enterAt);
    }

    /** Whether a `}` after {@code from} closes the brace just before it. */
    private static boolean closedLater(String text, int from) {
        Lexer lx = new Lexer(text, text.length());
        int depth = 0;
        for (int i = from; i < text.length(); ) {
            int next = lx.skip(i);
            if (next >= 0) {
                i = next;
                continue;
            }
            char c = text.charAt(i++);
            if (c == '{') {
                depth++;
            } else if (c == '}' && depth-- == 0) {
                return true;
            }
        }
        return false;
    }

    private static Indenter.TabEdit newLine(String text, int caret, IntFunction<Indenter.EnterEdit> enterAt) {
        return LineCommands.insertBelow(text, caret, caret, enterAt);
    }

    private static String leading(String text, int lineStart) {
        int i = lineStart;
        while (i < text.length() && (text.charAt(i) == ' ' || text.charAt(i) == '\t')) {
            i++;
        }
        return text.substring(lineStart, i);
    }

    /** What the scan found at the end of the caret's line. */
    private static final class Scan {
        final Deque<Open> stack = new ArrayDeque<>();
        StringBuilder head = new StringBuilder();
        int firstCode = -1;
        int lastCode = -1;
        int codeEnd;
        char openQuote;

        @Nullable
        String closedOwner;

        int emptyOpen = -1;
        int emptyClose = -1;
        String emptyIndent = "";
        boolean lineBroken;
    }

    /** Skips comments and literals. */
    private static final class Lexer {
        final String t;
        final int limit;
        /** Set when the literal at the last {@link #skip} is still open at {@link #limit}. */
        boolean unterminated;
        /** Its quote, when it is a one-line string or character literal. */
        char quote;

        Lexer(String t, int limit) {
            this.t = t;
            this.limit = limit;
        }

        /** The offset after the comment or literal at {@code i}, or -1 when none starts there. */
        int skip(int i) {
            unterminated = false;
            quote = 0;
            char c = t.charAt(i);
            char d = i + 1 < limit ? t.charAt(i + 1) : 0;
            if (c == '/' && d == '/') {
                int nl = t.indexOf('\n', i);
                return nl < 0 || nl > limit ? limit : nl;
            }
            if (c == '/' && d == '*') {
                return close(t.indexOf("*/", i + 2), 2);
            }
            if (c == '"' && t.startsWith("\"\"\"", i)) {
                return close(t.indexOf("\"\"\"", i + 3), 3);
            }
            if (c == '"' || c == '\'' || c == '`') {
                for (int j = i + 1; j < limit; j++) {
                    char e = t.charAt(j);
                    if (e == '\\') {
                        j++;
                    } else if (e == c) {
                        return j + 1;
                    } else if (e == '\n' && c != '`') {
                        return j; // unclosed on an earlier line: it ends with its line
                    }
                }
                unterminated = true;
                quote = c == '`' ? 0 : c;
                return limit;
            }
            return -1;
        }

        private int close(int at, int len) {
            if (at < 0 || at + len > limit) {
                unterminated = true;
                return limit;
            }
            return at + len;
        }
    }

    /** Scans {@code [0, le)}; null when the caret's line ends inside a comment, a text block or a template. */
    private static @Nullable Scan scan(String text, int ls, int le) {
        Scan s = new Scan();
        Lexer lx = new Lexer(text, le);
        int i = 0;
        while (i < le) {
            int next = lx.skip(i);
            if (next >= 0) {
                if (lx.unterminated) {
                    if (lx.quote == 0 || i < ls) {
                        return null;
                    }
                    s.openQuote = lx.quote;
                    code(s, i);
                    s.head.append(lx.quote).append(lx.quote);
                    s.codeEnd = le;
                    return s;
                }
                char c = text.charAt(i);
                if (c == '"' || c == '\'' || c == '`') {
                    abandonAtLineBreak(s, text, c);
                    code(s, i);
                    s.lastCode = next - 1;
                    s.head.append(c).append(c);
                    s.emptyClose = -1;
                } else {
                    s.head.append(' ');
                }
                i = next;
                continue;
            }
            char c = text.charAt(i);
            if (Character.isWhitespace(c)) {
                s.head.append(' ');
                s.lineBroken |= c == '\n';
                i++;
                continue;
            }
            abandonAtLineBreak(s, text, c);
            code(s, i);
            switch (c) {
                case '(', '[' -> {
                    s.stack.push(new Open(c, i, false, false, "", "", "", -1));
                    s.head.append(c);
                }
                case ')', ']' -> {
                    Open top = s.stack.peek();
                    if (top != null && !top.statementBrace) {
                        s.stack.pop();
                    }
                    s.head.append(c);
                }
                case '{' -> openBrace(s, text, i);
                case '}' -> closeBrace(s, text, i);
                case ';' -> {
                    Open top = s.stack.peek();
                    if (top != null && !top.statementBrace) {
                        s.head.append(';');
                    } else {
                        reset(s);
                        s.closedOwner = null;
                    }
                }
                default -> s.head.append(c);
            }
            if (c != '}') {
                s.emptyClose = -1;
            }
            i++;
        }
        s.codeEnd = s.lastCode + 1;
        return s;
    }

    /**
     * A bracket left open on an earlier line whose end reads like a finished statement ({@code g(1}), with
     * this line not continuing it, is abandoned: this line starts a statement of its own.
     */
    private static void abandonAtLineBreak(Scan s, String text, char first) {
        boolean broken = s.lineBroken;
        s.lineBroken = false;
        Open top = s.stack.peek();
        if (!broken || top == null || top.statementBrace || s.lastCode < 0) {
            return;
        }
        char last = text.charAt(s.lastCode);
        boolean finished =
                Character.isJavaIdentifierPart(last) || last == ')' || last == ']' || last == '"' || last == '\'';
        if (!finished || ".)],+-*/%&|^?:=<>".indexOf(first) >= 0) {
            return;
        }
        while (s.stack.peek() != null && !s.stack.peek().statementBrace) {
            s.stack.pop();
        }
        reset(s);
    }

    private static void code(Scan s, int i) {
        if (s.firstCode < 0) {
            s.firstCode = i;
        }
        s.lastCode = i;
    }

    private static void reset(Scan s) {
        s.head = new StringBuilder();
        s.firstCode = -1;
    }

    private static void openBrace(Scan s, String text, int i) {
        String owner = s.head.toString().replaceAll("\\s+", " ").strip();
        Open top = s.stack.peek();
        boolean inExpression = top != null && !top.statementBrace;
        boolean body = owner.isEmpty()
                || owner.endsWith(")")
                || owner.endsWith("->")
                || owner.endsWith("=>")
                || Character.isJavaIdentifierPart(owner.charAt(owner.length() - 1))
                || owner.endsWith(">");
        if (!body || (inExpression && !owner.endsWith(")") && !owner.endsWith("->") && !owner.endsWith("=>"))) {
            s.stack.push(new Open('{', i, false, false, "", "", "", -1)); // an initializer or object literal
            s.head.append('{');
            return;
        }
        String head = owner.replaceFirst("^\\}\\s*", "");
        boolean header = !inExpression && !partOfExpression(head);
        int lineOf = s.firstCode < 0 ? i : s.firstCode;
        String indent = leading(text, TextNav.lineStart(text, lineOf));
        s.stack.push(new Open('{', i, true, header && !inExpression, head, indent, s.head.toString(), s.firstCode));
        reset(s);
        s.closedOwner = null;
    }

    /** A switch arm: its block is the arm's body. */
    private static final Pattern CASE_ARROW = Pattern.compile("(?:case\\b.*|default)\\s*(?:->|=>)", Pattern.DOTALL);

    /** A statement-level word whose {@code {} is part of an expression (an object literal, an anonymous class). */
    private static final Pattern EXPRESSION_START = Pattern.compile("(?:return|throw|yield|new)\\b.*", Pattern.DOTALL);

    /**
     * Whether the {@code {} after {@code owner} belongs to the expression it is in — a lambda's body, an
     * anonymous class, a switch expression — rather than opening a block of its own: after an arrow that is not
     * a switch arm's, or after a {@code =}, {@code return}, {@code throw}, {@code yield} or {@code new} outside
     * the owner's brackets.
     */
    private static boolean partOfExpression(String owner) {
        if (owner.endsWith("->") || owner.endsWith("=>")) {
            return !CASE_ARROW.matcher(owner).matches();
        }
        String top = owner;
        for (String prev = ""; !prev.equals(top); ) { // drop bracketed groups, innermost first
            prev = top;
            top = top.replaceAll("\\([^()]*\\)|\\[[^\\[\\]]*\\]|<[^<>()]*>", "");
        }
        return EXPRESSION_START.matcher(top).matches()
                || top.matches("(?s).*\\bnew\\b.*")
                || top.replace("==", "")
                        .replace("!=", "")
                        .replace("<=", "")
                        .replace(">=", "")
                        .contains("=");
    }

    private static void closeBrace(Scan s, String text, int i) {
        Open o = s.stack.peek();
        while (o != null && o.ch != '{') { // an unclosed ( or [ inside the block ends with it
            s.stack.pop();
            o = s.stack.peek();
        }
        if (o == null) {
            reset(s);
            return;
        }
        s.stack.pop();
        if (!o.statementBrace) {
            s.head.append('}');
            return;
        }
        boolean empty = text.substring(o.pos + 1, i).isBlank();
        if (o.blockHeader || o.owner.isEmpty()) {
            reset(s);
            s.closedOwner = o.owner;
        } else { // a lambda, anonymous class or switch expression: the statement around it goes on
            s.head = new StringBuilder(o.savedHead).append("{}");
            s.firstCode = o.savedFirstCode;
            s.closedOwner = null;
        }
        if (empty) {
            s.emptyOpen = o.pos;
            s.emptyClose = i;
            s.emptyIndent = o.indent;
        } else {
            s.emptyOpen = -1;
        }
    }
}

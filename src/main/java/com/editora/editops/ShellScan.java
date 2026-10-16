package com.editora.editops;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * One forward pass over shell source (sh, bash, zsh) that knows what a line-at-a-time look cannot: quotes and
 * {@code $( … )} that span lines, {@code #} comments, here-documents, and the keyword blocks of the language
 * ({@code if … fi}, {@code for/while/until/select … done}, {@code case … esac} and its {@code pattern) … ;;}
 * items, {@code { … }}, and {@code ( … )} subshells, substitutions and arrays). Pure, allocation-light, no
 * toolkit — shared by {@link Indenter} (Enter, closer de-indent, smart Tab) and {@code FoldRegions}.
 *
 * <p>It is a heuristic lexer, not a parser: it only has to be right about the constructs real scripts are made
 * of, and it never fails — unbalanced input leaves blocks open (they then fold nothing). Keywords count only
 * where the shell itself treats them as reserved words, at the start of a command, so {@code echo what to do}
 * opens nothing, while {@code a; then} does.
 */
public final class ShellScan {

    private ShellScan() {}

    /** What a {@link Block} is. */
    public enum Kind {
        IF,
        LOOP,
        CASE,
        CASE_ITEM,
        BRACE,
        PAREN,
        HEREDOC
    }

    /** Per-line event codes: the last structural event on a line ({@link Result#lastEvent}). */
    public static final byte NONE = 0;

    /** A block's head without its body yet ({@code if}, {@code while}, {@code case x}, {@code elif}). */
    public static final byte OPEN = 1;

    /** A body starts here: {@code then}, {@code do}, {@code else}, {@code in}, {@code pattern)}, {@code {}, {@code (}. */
    public static final byte BODY = 2;

    /** A block ends here: {@code fi}, {@code done}, {@code esac}, {@code ;;}, {@code }}, {@code )}, a here-doc's delimiter. */
    public static final byte CLOSE = 3;

    /** What a line starts inside ({@link Result#startState}). */
    public static final byte CODE = 0;

    public static final byte IN_HEREDOC = 1;
    public static final byte IN_STRING = 2;

    /** One block: where its statement starts, where it ends (or -1 while open). */
    public static final class Block {
        private final Kind kind;
        private final int headerLine;
        private int closeLine = -1;
        private final String delimiter;
        private final boolean dash;
        private boolean array;
        private int frameDepth = -1; // a $( … ) / <( … ): the mode stack to restore when it closes
        private boolean inSeen; // CASE: past its `in`, so patterns follow

        Block(Kind kind, int headerLine, String delimiter, boolean dash) {
            this.kind = kind;
            this.headerLine = headerLine;
            this.delimiter = delimiter;
            this.dash = dash;
        }

        public Kind kind() {
            return kind;
        }

        /** The 0-based line the block's statement starts on (a function's name line for {@code f()⏎{}). */
        public int headerLine() {
            return headerLine;
        }

        /** The 0-based line that closes the block, or -1 while it is open. */
        public int closeLine() {
            return closeLine;
        }

        /** A here-doc's delimiter, quotes removed; null for other blocks. */
        public String delimiter() {
            return delimiter;
        }

        /** A {@code <<-} here-doc, whose body and delimiter may be indented with tabs. */
        public boolean dash() {
            return dash;
        }

        @Override
        public String toString() {
            return kind + "[" + headerLine + ".." + closeLine + "]";
        }
    }

    /** What a scan found. Line numbers are 0-based within the scanned text. */
    public static final class Result {
        private final String text;
        private final int[] lineStarts;
        private final byte[] startState;
        private final Block[] startHeredoc;
        private final byte[] lastEvent;
        private final Block[] lastBlock;
        private final List<Block> blocks;
        private final List<Block> open;
        private final boolean endsInString;

        Result(Scanner s) {
            this.text = s.text;
            this.lineStarts = s.lineStarts;
            this.startState = s.startState;
            this.startHeredoc = s.startHeredoc;
            this.lastEvent = s.lastEvent;
            this.lastBlock = s.lastBlock;
            this.blocks = s.blocks;
            this.open = s.stack;
            this.endsInString = s.inQuote();
        }

        public int lineCount() {
            return lineStarts.length;
        }

        public int lineStart(int line) {
            return lineStarts[line];
        }

        /** The leading blanks of {@code line}. */
        public String indentOf(int line) {
            int i = lineStarts[line];
            int j = i;
            while (j < text.length() && (text.charAt(j) == ' ' || text.charAt(j) == '\t')) {
                j++;
            }
            return text.substring(i, j);
        }

        /** {@link #CODE}, {@link #IN_HEREDOC} or {@link #IN_STRING}: what {@code line} starts inside. */
        public byte startState(int line) {
            return startState[line];
        }

        /** The here-doc whose body {@code line} belongs to (its delimiter line included), or null. */
        public Block heredocAt(int line) {
            return startHeredoc[line];
        }

        /** The last structural event on {@code line}: {@link #NONE}, {@link #OPEN}, {@link #BODY}, {@link #CLOSE}. */
        public byte lastEvent(int line) {
            return lastEvent[line];
        }

        /** The block {@link #lastEvent} is about, or null. */
        public Block lastBlock(int line) {
            return lastBlock[line];
        }

        /** Every block, in the order they opened (here-docs included). */
        public List<Block> blocks() {
            return blocks;
        }

        /** The blocks still open at the end of the text, outermost first. */
        public List<Block> openAtEnd() {
            return open;
        }

        /** The innermost block of {@code kind} still open at the end, or null. */
        public Block innermostOpen(Kind kind) {
            for (int k = open.size() - 1; k >= 0; k--) {
                if (open.get(k).kind == kind) {
                    return open.get(k);
                }
            }
            return null;
        }

        /** Whether the text ends inside a quoted string, backticks or a {@code ${ … }}. */
        public boolean endsInString() {
            return endsInString;
        }
    }

    /** Scans {@code text} (lines separated by {@code \n}). */
    public static Result scan(String text) {
        Scanner s = new Scanner(text == null ? "" : text);
        s.run();
        return new Result(s);
    }

    /** Whether {@code c} ends a shell word. */
    static boolean isMeta(char c) {
        return c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == ';' || c == '&' || c == '|' || c == '('
                || c == ')' || c == '<' || c == '>';
    }

    // --- the scanner ------------------------------------------------------------------------------

    /** A lexical mode: code ('C'), a {@code [[ ]]} test ('T'), quotes, arithmetic ('A'), or {@code ${ }} ('P'). */
    private static final class Frame {
        final char mode;
        int depth;
        final char close; // arithmetic: ')' for (( )), ']' for $[ ]
        final boolean fromDq; // ${ … } inside "…": a ' is literal there

        Frame(char mode, char close, boolean fromDq) {
            this.mode = mode;
            this.close = close;
            this.fromDq = fromDq;
        }
    }

    private static final class Scanner {
        final String text;
        final int n;
        final int[] lineStarts;
        final byte[] startState;
        final Block[] startHeredoc;
        final byte[] lastEvent;
        final Block[] lastBlock;
        final List<Block> blocks = new ArrayList<>();
        final List<Block> stack = new ArrayList<>();
        final List<Frame> frames = new ArrayList<>();
        final Deque<Block> pending = new ArrayDeque<>();

        int i;
        int line;
        boolean cmd = true; // the next word is at the start of a command
        boolean wordStart = true;
        boolean continued; // the line ended with a `\`
        boolean forHeader; // after `for`/`select`: words until the separator are not keywords
        boolean caseHeader; // after `case`, before `in`
        boolean redirectTarget; // the next word is a redirection's target
        boolean funcCandidate; // the word just read could be a function's name: `name ()`
        boolean funcPending; // a function header (`name ()` / `function name`) waits for its body
        int funcLine;

        Scanner(String text) {
            this.text = text;
            this.n = text.length();
            int lines = 1;
            for (int k = text.indexOf('\n'); k >= 0; k = text.indexOf('\n', k + 1)) {
                lines++;
            }
            lineStarts = new int[lines];
            startState = new byte[lines];
            startHeredoc = new Block[lines];
            lastEvent = new byte[lines];
            lastBlock = new Block[lines];
        }

        boolean inQuote() {
            char m = mode();
            return m == '"' || m == '\'' || m == '$' || m == '`' || m == 'P';
        }

        char mode() {
            return frames.isEmpty() ? 'C' : frames.get(frames.size() - 1).mode;
        }

        Frame top() {
            return frames.get(frames.size() - 1);
        }

        void push(char mode) {
            frames.add(new Frame(mode, (char) 0, false));
        }

        void pop() {
            if (!frames.isEmpty()) {
                frames.remove(frames.size() - 1);
            }
        }

        void event(byte type, Block b) {
            lastEvent[line] = type;
            lastBlock[line] = b;
        }

        Block open(Kind kind, int header, byte type) {
            Block b = new Block(kind, header, null, false);
            blocks.add(b);
            stack.add(b);
            event(type, b);
            return b;
        }

        Block innermost(Kind kind) {
            for (int k = stack.size() - 1; k >= 0; k--) {
                if (stack.get(k).kind == kind) {
                    return stack.get(k);
                }
            }
            return null;
        }

        /** Closes the innermost open {@code kind} at {@code at}, dropping any block still open inside it. */
        Block close(Kind kind, int at) {
            for (int k = stack.size() - 1; k >= 0; k--) {
                Block b = stack.get(k);
                if (b.kind == kind) {
                    while (stack.size() > k) {
                        stack.remove(stack.size() - 1);
                    }
                    b.closeLine = Math.max(b.headerLine, at);
                    event(CLOSE, b);
                    return b;
                }
            }
            return null;
        }

        /** Inside a {@code case}, past its {@code in}, with no item open: the words read are patterns. */
        boolean patternPosition() {
            if (stack.isEmpty() || caseHeader) {
                return false;
            }
            Block b = stack.get(stack.size() - 1);
            return b.kind == Kind.CASE && b.inSeen;
        }

        boolean inArray() {
            return !stack.isEmpty() && stack.get(stack.size() - 1).array;
        }

        void run() {
            push('C');
            while (i < n) {
                char c = text.charAt(i);
                if (c == '\n') {
                    newline();
                    continue;
                }
                switch (mode()) {
                    case 'C', 'T' -> code(c);
                    case '"' -> doubleQuoted(c);
                    case '\'' -> {
                        if (c == '\'') {
                            pop();
                        }
                        i++;
                    }
                    case '$' -> { // $'…': backslash escapes
                        if (c == '\\') {
                            i += 2;
                        } else {
                            if (c == '\'') {
                                pop();
                            }
                            i++;
                        }
                    }
                    case '`' -> {
                        if (c == '\\') {
                            i += 2;
                        } else {
                            if (c == '`') {
                                pop();
                            }
                            i++;
                        }
                    }
                    case 'A' -> arithmetic(c);
                    case 'P' -> parameter(c);
                    default -> i++;
                }
            }
        }

        void newline() {
            boolean code = mode() == 'C' || mode() == 'T';
            i++;
            line++;
            lineStarts[line] = i;
            startState[line] = inQuote() ? IN_STRING : CODE;
            if (code && !continued) {
                cmd = !inArray();
                forHeader = false;
                redirectTarget = false;
                funcCandidate = false;
            }
            wordStart = true;
            continued = false;
            if (code && !pending.isEmpty()) {
                // One body per line break: the next one starts after this one's delimiter line.
                heredoc(pending.removeFirst());
            }
        }

        /**
         * Reads a here-doc body starting at the current line, up to and including its delimiter line. Returns
         * false when the text ended first; else leaves {@code i} on the delimiter line's break (or the end).
         */
        boolean heredoc(Block h) {
            while (true) {
                startState[line] = IN_HEREDOC;
                startHeredoc[line] = h;
                int e = text.indexOf('\n', i);
                int end = e < 0 ? n : e;
                int from = i;
                if (h.dash) {
                    while (from < end && text.charAt(from) == '\t') {
                        from++;
                    }
                }
                int to = end > from && text.charAt(end - 1) == '\r' ? end - 1 : end;
                if (text.startsWith(h.delimiter, from) && to - from == h.delimiter.length()) {
                    h.closeLine = line;
                    event(CLOSE, h);
                    i = end;
                    return true;
                }
                if (e < 0) {
                    i = n;
                    return false;
                }
                i = e + 1;
                line++;
                lineStarts[line] = i;
            }
        }

        void code(char c) {
            boolean test = mode() == 'T';
            if (c == ' ' || c == '\t' || c == '\r') {
                wordStart = true;
                i++;
                return;
            }
            if (c == '#' && wordStart) {
                int e = text.indexOf('\n', i);
                i = e < 0 ? n : e;
                return;
            }
            if (c == '\\') {
                if (wordStart) {
                    word();
                }
                if (i + 1 < n && text.charAt(i + 1) == '\n') {
                    continued = true;
                    i++;
                } else {
                    i += 2;
                }
                return;
            }
            if (test) {
                if (wordStart && text.startsWith("]]", i) && (i + 2 >= n || isMeta(text.charAt(i + 2)))) {
                    pop();
                    cmd = false;
                    i += 2;
                    return;
                }
                if (c == '(' || c == ')' || c == '<' || c == '>' || c == '&' || c == '|') {
                    wordStart = true;
                    i++;
                    return;
                }
            }
            if (!test && patternPosition()) {
                pattern(c);
                return;
            }
            switch (c) {
                case ';' -> semicolon();
                case '&' -> {
                    if (i + 1 < n && text.charAt(i + 1) == '>') {
                        i++; // &>file: the > that follows is the redirection
                        return;
                    }
                    separator();
                    i += i + 1 < n && text.charAt(i + 1) == '&' ? 2 : 1;
                }
                case '|' -> {
                    separator();
                    i += i + 1 < n && (text.charAt(i + 1) == '|' || text.charAt(i + 1) == '&') ? 2 : 1;
                }
                case '<', '>' -> redirect(c);
                case '(' -> openParen();
                case ')' -> {
                    Block b = close(Kind.PAREN, line);
                    if (b != null && b.frameDepth >= 0) {
                        while (frames.size() > b.frameDepth) {
                            pop();
                        }
                    }
                    cmd = b != null && b.array;
                    funcCandidate = false;
                    wordStart = true;
                    i++;
                }
                case '{' -> {
                    if (wordStart && cmd && !inArray() && (i + 1 >= n || isMeta(text.charAt(i + 1)))) {
                        int header = funcPending ? funcLine : line;
                        funcPending = false;
                        open(Kind.BRACE, header, BODY);
                        cmd = true;
                        i++;
                        return;
                    }
                    if (wordStart) {
                        word();
                    }
                    i++;
                }
                case '}' -> {
                    if (wordStart && cmd && (i + 1 >= n || isMeta(text.charAt(i + 1)))) {
                        close(Kind.BRACE, line);
                        cmd = false;
                        i++;
                        return;
                    }
                    if (wordStart) {
                        word();
                    }
                    i++;
                }
                case '\'' -> {
                    if (wordStart) {
                        word();
                    }
                    push('\'');
                    i++;
                }
                case '"' -> {
                    if (wordStart) {
                        word();
                    }
                    push('"');
                    i++;
                }
                case '`' -> {
                    if (wordStart) {
                        word();
                    }
                    push('`');
                    i++;
                }
                case '$' -> {
                    if (wordStart) {
                        word();
                    }
                    dollar();
                }
                case '[' -> {
                    if (wordStart && cmd && text.startsWith("[[", i) && (i + 2 >= n || isMeta(text.charAt(i + 2)))) {
                        wordStart = false;
                        cmd = false;
                        funcCandidate = false;
                        push('T');
                        i += 2;
                        return;
                    }
                    if (wordStart) {
                        word();
                    }
                    i++;
                }
                default -> {
                    if (c == '!' && wordStart && !test && (i + 1 >= n || isMeta(text.charAt(i + 1)))) {
                        i++; // `! cmd`: the pipeline's command still follows
                        return;
                    }
                    if (wordStart && !test) {
                        if (keyword()) {
                            return;
                        }
                        word();
                    } else if (wordStart) {
                        wordStart = false;
                    }
                    i++;
                }
            }
        }

        /** A word that is not a keyword starts at {@code i}: decide whether the next one is a command. */
        void word() {
            wordStart = false;
            if (mode() == 'T') {
                return;
            }
            if (redirectTarget) {
                redirectTarget = false;
                return;
            }
            boolean wasCmd = cmd;
            funcPending = false;
            funcCandidate = wasCmd && !inArray() && nameAt(i);
            cmd = wasCmd && assignmentAt(i);
        }

        /** {@code name=…}, {@code name+=…} or {@code name[…]=…}: an assignment, after which a command may follow. */
        boolean assignmentAt(int at) {
            int k = at;
            if (k >= n || !(Character.isLetter(text.charAt(k)) || text.charAt(k) == '_')) {
                return false;
            }
            while (k < n && (Character.isLetterOrDigit(text.charAt(k)) || text.charAt(k) == '_')) {
                k++;
            }
            if (k < n && text.charAt(k) == '[') {
                int close = text.indexOf(']', k);
                if (close < 0) {
                    return false;
                }
                k = close + 1;
            }
            if (k < n && text.charAt(k) == '+') {
                k++;
            }
            return k < n && text.charAt(k) == '=';
        }

        /** Whether a plain name (a function's, possibly {@code lib::fn} or {@code a-b}) starts at {@code at}. */
        boolean nameAt(int at) {
            char c = text.charAt(at);
            return Character.isLetterOrDigit(c) || c == '_' || c == '.' || c == ':' || c == '-' || c == '@';
        }

        /** Handles a reserved word at {@code i} when it is one here; returns whether it did. */
        boolean keyword() {
            int k = i;
            while (k < n && text.charAt(k) >= 'a' && text.charAt(k) <= 'z') {
                k++;
            }
            if (k == i || (k < n && !isMeta(text.charAt(k)))) {
                return false;
            }
            String w = text.substring(i, k);
            if (caseHeader) {
                if (!w.equals("in")) {
                    return false;
                }
                caseHeader = false;
                Block b = innermost(Kind.CASE);
                if (b != null) {
                    b.inSeen = true;
                    event(BODY, b);
                }
                return consume(k, false);
            }
            if (!cmd || inArray() || redirectTarget) {
                return false;
            }
            if (forHeader && !w.equals("do")) {
                return false;
            }
            switch (w) {
                case "if" -> open(Kind.IF, line, OPEN);
                case "then", "else" -> body(Kind.IF);
                case "elif" -> {
                    Block b = innermost(Kind.IF);
                    if (b != null) {
                        event(OPEN, b);
                    }
                }
                case "fi" -> {
                    close(Kind.IF, line);
                    return consume(k, false);
                }
                case "for", "select" -> {
                    open(Kind.LOOP, line, OPEN);
                    forHeader = true;
                    return consume(k, false);
                }
                case "while", "until" -> open(Kind.LOOP, line, OPEN);
                case "do" -> {
                    forHeader = false;
                    body(Kind.LOOP);
                }
                case "done" -> {
                    close(Kind.LOOP, line);
                    return consume(k, false);
                }
                case "case" -> {
                    open(Kind.CASE, line, OPEN);
                    caseHeader = true;
                    return consume(k, false);
                }
                case "esac" -> {
                    esac();
                    return consume(k, false);
                }
                case "function" -> {
                    function(k);
                    return true;
                }
                case "time", "coproc" -> {
                    // a command follows
                }
                default -> {
                    return false;
                }
            }
            return consume(k, true);
        }

        boolean consume(int to, boolean commandFollows) {
            i = to;
            wordStart = true;
            cmd = commandFollows;
            funcCandidate = false;
            funcPending = false;
            return true;
        }

        /** {@code then}/{@code do}/{@code else}: the body of the innermost such block — or of one opened above
         *  the scanned text, which still indents what follows. */
        void body(Kind kind) {
            Block b = innermost(kind);
            if (b == null) {
                b = new Block(kind, line, null, false);
            }
            event(BODY, b);
        }

        void esac() {
            Block c = innermost(Kind.CASE);
            if (c == null) {
                return;
            }
            Block top = stack.get(stack.size() - 1);
            if (top.kind == Kind.CASE_ITEM) {
                // The last item may end without `;;`: it ends on the line before `esac`.
                close(Kind.CASE_ITEM, line - 1);
            }
            close(Kind.CASE, line);
        }

        /** {@code function name [()]}: the name is not a command, and the body ({ or ( ) follows. */
        void function(int k) {
            int j = k;
            while (j < n && (text.charAt(j) == ' ' || text.charAt(j) == '\t')) {
                j++;
            }
            while (j < n && !isMeta(text.charAt(j))) {
                j++;
            }
            int after = j;
            while (after < n && (text.charAt(after) == ' ' || text.charAt(after) == '\t')) {
                after++;
            }
            if (after + 1 < n && text.charAt(after) == '(') {
                int close = after + 1;
                while (close < n && (text.charAt(close) == ' ' || text.charAt(close) == '\t')) {
                    close++;
                }
                if (close < n && text.charAt(close) == ')') {
                    j = close + 1;
                }
            }
            consume(j, true);
            funcPending = true;
            funcLine = line;
        }

        void semicolon() {
            if (text.startsWith(";;", i) || text.startsWith(";&", i)) {
                int len = text.startsWith(";;&", i) ? 3 : 2;
                Block item = innermost(Kind.CASE_ITEM);
                if (item != null) {
                    close(Kind.CASE_ITEM, line);
                }
                i += len;
            } else {
                i++;
            }
            separator();
        }

        void separator() {
            cmd = !inArray();
            wordStart = true;
            forHeader = false;
            redirectTarget = false;
            funcCandidate = false;
        }

        /** In a {@code case} between items: patterns up to the {@code )} that starts an item, or {@code esac}. */
        void pattern(char c) {
            switch (c) {
                case '(', '|' -> {
                    wordStart = true;
                    i++;
                }
                case ')' -> {
                    open(Kind.CASE_ITEM, line, BODY);
                    cmd = true;
                    wordStart = true;
                    i++;
                }
                case ';' -> i++; // a stray ;; between items
                case '\'' -> {
                    push('\'');
                    wordStart = false;
                    i++;
                }
                case '"' -> {
                    push('"');
                    wordStart = false;
                    i++;
                }
                case '$' -> {
                    wordStart = false;
                    dollar();
                }
                default -> {
                    if (wordStart && text.startsWith("esac", i) && (i + 4 >= n || isMeta(text.charAt(i + 4)))) {
                        esac();
                        consume(i + 4, false);
                        return;
                    }
                    wordStart = false;
                    i++;
                }
            }
        }

        void openParen() {
            if (wordStart && text.startsWith("((", i)) {
                frames.add(new Frame('A', ')', false)); // (( arithmetic )), also `for ((…))`
                wordStart = false;
                cmd = false;
                funcCandidate = false;
                i += 2;
                return;
            }
            if (funcCandidate) {
                int k = i + 1;
                while (k < n && (text.charAt(k) == ' ' || text.charAt(k) == '\t')) {
                    k++;
                }
                if (k < n && text.charAt(k) == ')') { // name (): a function definition
                    funcCandidate = false;
                    funcPending = true;
                    funcLine = line;
                    cmd = true;
                    wordStart = true;
                    i = k + 1;
                    return;
                }
            }
            boolean array = i > 0 && text.charAt(i - 1) == '=';
            int header = funcPending ? funcLine : line;
            funcPending = false;
            funcCandidate = false;
            Block b = open(Kind.PAREN, header, BODY);
            b.array = array;
            cmd = !array;
            wordStart = true;
            i++;
        }

        /** {@code $(…)}, {@code <(…)} and {@code >(…)} open code inside whatever mode they appear in. */
        void substitution(int skip) {
            Block b = open(Kind.PAREN, line, BODY);
            b.frameDepth = frames.size();
            push('C');
            cmd = true;
            wordStart = true;
            funcCandidate = false;
            i += skip;
        }

        void dollar() {
            char next = i + 1 < n ? text.charAt(i + 1) : 0;
            char m = mode();
            if (next == '(') {
                if (text.startsWith("((", i + 1)) {
                    frames.add(new Frame('A', ')', false));
                    i += 3;
                } else {
                    substitution(2);
                }
            } else if (next == '{') {
                frames.add(new Frame('P', (char) 0, m == '"'));
                i += 2;
            } else if (next == '[') {
                frames.add(new Frame('A', ']', false));
                i += 2;
            } else if (next == '\'' && m != '"') {
                push('$');
                i += 2;
            } else if (next == '"' && m != '"') {
                push('"');
                i += 2;
            } else {
                i += next == 0 ? 1 : 2; // $x, $#, $$ … (a $# is never a comment)
            }
        }

        void doubleQuoted(char c) {
            switch (c) {
                case '\\' -> i += 2;
                case '"' -> {
                    pop();
                    i++;
                }
                case '$' -> dollar();
                case '`' -> {
                    push('`');
                    i++;
                }
                default -> i++;
            }
        }

        void arithmetic(char c) {
            Frame f = top();
            char open = f.close == ']' ? '[' : '(';
            if (c == '$') {
                dollar();
                return;
            }
            if (c == open) {
                f.depth++;
            } else if (c == f.close) {
                if (f.depth > 0) {
                    f.depth--;
                } else {
                    pop();
                    if (f.close == ')' && i + 1 < n && text.charAt(i + 1) == ')') {
                        i++;
                    }
                }
            }
            i++;
        }

        void parameter(char c) {
            Frame f = top();
            switch (c) {
                case '\\' -> i += 2;
                case '{' -> {
                    f.depth++;
                    i++;
                }
                case '}' -> {
                    if (f.depth == 0) {
                        pop();
                    } else {
                        f.depth--;
                    }
                    i++;
                }
                case '"' -> {
                    push('"');
                    i++;
                }
                case '\'' -> {
                    if (!f.fromDq) {
                        push('\'');
                    }
                    i++;
                }
                case '$' -> dollar();
                case '`' -> {
                    push('`');
                    i++;
                }
                default -> i++;
            }
        }

        void redirect(char c) {
            if (i + 1 < n && text.charAt(i + 1) == '(') {
                if (wordStart) {
                    word();
                }
                substitution(2); // <(…) / >(…)
                return;
            }
            if (c == '<' && text.startsWith("<<", i) && !text.startsWith("<<<", i)) {
                hereDocStart();
                return;
            }
            int k = i + 1;
            while (k < n && (text.charAt(k) == '<' || text.charAt(k) == '>' || text.charAt(k) == '|')) {
                k++;
            }
            if (k < n && text.charAt(k) == '&') {
                k++;
                while (k < n && (Character.isDigit(text.charAt(k)) || text.charAt(k) == '-')) {
                    k++;
                }
                i = k;
                wordStart = true;
                return; // >&2: no target word follows
            }
            i = k;
            wordStart = true;
            redirectTarget = true;
            funcCandidate = false;
        }

        /** {@code <<WORD} / {@code <<-WORD} (quoted or not): queue a body for the next line. */
        void hereDocStart() {
            int k = i + 2;
            boolean dash = k < n && text.charAt(k) == '-';
            if (dash) {
                k++;
            }
            while (k < n && (text.charAt(k) == ' ' || text.charAt(k) == '\t')) {
                k++;
            }
            StringBuilder delim = new StringBuilder();
            char quote = 0;
            while (k < n && text.charAt(k) != '\n') {
                char d = text.charAt(k);
                if (quote != 0) {
                    if (d == quote) {
                        quote = 0;
                    } else {
                        delim.append(d);
                    }
                } else if (d == '\'' || d == '"') {
                    quote = d;
                } else if (d == '\\' && k + 1 < n && text.charAt(k + 1) != '\n') {
                    delim.append(text.charAt(++k));
                } else if (isMeta(d)) {
                    break;
                } else {
                    delim.append(d);
                }
                k++;
            }
            i = k;
            wordStart = true;
            funcCandidate = false;
            if (!delim.isEmpty()) {
                Block h = new Block(Kind.HEREDOC, line, delim.toString(), dash);
                blocks.add(h);
                pending.add(h);
            }
        }
    }
}

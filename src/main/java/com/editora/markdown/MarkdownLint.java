package com.editora.markdown;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pure, high-confidence Markdown linter (a curated subset of the markdownlint rule set). Returns
 * 1-based {@link Diagnostic}s in document order, mirroring {@link com.editora.mermaid.MaidOutput}'s
 * shape so the lint overlay/panel/stripe can render either.
 *
 * <p>What is code, front matter or raw HTML is decided once by {@link MarkdownBlocks}: nothing inside a fenced or
 * indented code block (at any nesting), YAML/TOML front matter, an HTML comment or a {@code <pre>}-style block
 * is linted, and code spans are matched by backtick run length.
 *
 * <p>A {@code disabled} set (rule codes the user turned off in Settings / a {@code .markdownlint.json})
 * and inline {@link MarkdownLintDirectives} (<!-- markdownlint-disable … -->) both suppress emission.
 *
 * <p>Stateless and toolkit-free — unit-tested directly.
 */
public final class MarkdownLint {

    private MarkdownLint() {}

    public static final String WARNING = "warning";

    /**
     * One diagnostic: 1-based {@code line}/{@code column}, char span {@code length} (≥1), a {@code severity}
     * ({@code error}/{@code warning}), a rule {@code code}, an English {@code message} (logs, tests) and the
     * catalog {@code key} the UI resolves it with ({@code null} when the producer has none).
     */
    public record Diagnostic(
            int line, int column, int length, String severity, String code, String message, String key) {

        public Diagnostic(int line, int column, int length, String severity, String code, String message) {
            this(line, column, length, severity, code, message, null);
        }

        public boolean isError() {
            return "error".equalsIgnoreCase(severity);
        }
    }

    /**
     * A lint rule: its code, whether {@link MarkdownLintFix} can correct it, and the markdownlint names it also
     * answers to in a directive or a config file ({@code aliases} name this rule, {@code tags} a group of rules).
     * Its display name is the catalog entry {@code mdlint.rule.<code>}.
     */
    public record Rule(String code, boolean fixable, List<String> aliases, List<String> tags) {}

    /** Every rule this linter can emit, in code order — the source of truth for the per-rule UI. */
    public static final List<Rule> RULES = List.of(
            new Rule("MD001", false, List.of("heading-increment"), List.of("headings")),
            new Rule("MD009", true, List.of("no-trailing-spaces"), List.of("whitespace")),
            new Rule("MD010", true, List.of("no-hard-tabs"), List.of("whitespace", "hard_tab")),
            new Rule("MD012", true, List.of("no-multiple-blanks"), List.of("whitespace", "blank_lines")),
            new Rule("MD018", true, List.of("no-missing-space-atx"), List.of("headings", "atx", "spaces")),
            new Rule("MD019", true, List.of("no-multiple-space-atx"), List.of("headings", "atx", "spaces")),
            new Rule("MD022", false, List.of("blanks-around-headings"), List.of("headings", "blank_lines")),
            new Rule("MD023", true, List.of("heading-start-left"), List.of("headings", "spaces")),
            new Rule("MD025", false, List.of("single-title", "single-h1"), List.of("headings")),
            new Rule("MD026", true, List.of("no-trailing-punctuation"), List.of("headings")),
            new Rule("MD031", false, List.of("blanks-around-fences"), List.of("code", "blank_lines")),
            new Rule("MD034", false, List.of("no-bare-urls"), List.of("links", "url")),
            new Rule("MD040", false, List.of("fenced-code-language"), List.of("code", "language")),
            new Rule("MD041", false, List.of("first-line-heading", "first-line-h1"), List.of("headings")),
            new Rule("MD047", true, List.of("single-trailing-newline"), List.of("blank_lines")),
            new Rule("MD052", false, List.of("reference-links-images"), List.of("images", "links")));

    /** The codes of {@link #RULES}. */
    public static final Set<String> CODES;

    private static final Map<String, Set<String>> BY_NAME;

    static {
        Set<String> codes = new java.util.LinkedHashSet<>();
        Map<String, Set<String>> byName = new java.util.HashMap<>();
        for (Rule r : RULES) {
            codes.add(r.code());
            byName.computeIfAbsent(r.code().toLowerCase(Locale.ROOT), k -> new HashSet<>())
                    .add(r.code());
            for (String alias : r.aliases()) {
                byName.computeIfAbsent(alias, k -> new HashSet<>()).add(r.code());
            }
            for (String tag : r.tags()) {
                byName.computeIfAbsent(tag, k -> new HashSet<>()).add(r.code());
            }
        }
        CODES = java.util.Collections.unmodifiableSet(codes);
        BY_NAME = byName;
    }

    /**
     * The rules a markdownlint {@code name} stands for: a code ({@code MD009}), an alias
     * ({@code no-trailing-spaces}) or a tag ({@code whitespace}), case-insensitively. Empty for a name this
     * linter does not know ({@code no-inline-html}) — the caller then leaves every rule alone.
     */
    public static Set<String> resolve(String name) {
        if (name == null) {
            return Set.of();
        }
        String key = name.strip().toLowerCase(Locale.ROOT);
        Set<String> hit = BY_NAME.get(key);
        if (hit != null) {
            return Set.copyOf(hit);
        }
        // A code of a rule that is not implemented here still names exactly that rule (and nothing else).
        return key.matches("md\\d{3}") ? Set.of(key.toUpperCase(Locale.ROOT)) : Set.of();
    }

    /** Whether {@link MarkdownLintFix} can correct {@code code}. */
    public static boolean fixable(String code) {
        for (Rule r : RULES) {
            if (r.code().equals(code)) {
                return r.fixable();
            }
        }
        return false;
    }

    /** The English text of every message, by the part of its catalog key after {@code mdlint.msg.}. */
    private static final Map<String, String> ENGLISH = Map.ofEntries(
            Map.entry("MD001", "Heading levels should increment by one level at a time"),
            Map.entry("MD009", "Trailing whitespace"),
            Map.entry("MD010", "Hard tab"),
            Map.entry("MD012", "Multiple consecutive blank lines"),
            Map.entry("MD018", "No space after heading marker"),
            Map.entry("MD019", "Multiple spaces after heading marker"),
            Map.entry("MD022.before", "Heading should be preceded by a blank line"),
            Map.entry("MD022.after", "Heading should be followed by a blank line"),
            Map.entry("MD023", "Heading must start at line beginning"),
            Map.entry("MD025", "Multiple top-level headings"),
            Map.entry("MD026", "Trailing punctuation in heading"),
            Map.entry("MD031.before", "Fenced code block should be preceded by a blank line"),
            Map.entry("MD031.after", "Fenced code block should be followed by a blank line"),
            Map.entry("MD034", "Bare URL used"),
            Map.entry("MD040", "Fenced code block should specify a language"),
            Map.entry("MD041", "First line in a file should be a top-level heading"),
            Map.entry("MD047.missing", "File should end with a newline"),
            Map.entry("MD047.multiple", "File should end with a single newline"),
            Map.entry("MD052", "Reference link has no matching definition"));

    /** The catalog keys of every message this linter emits (for the catalog-parity test). */
    public static Set<String> messageKeys() {
        Set<String> keys = new java.util.TreeSet<>();
        for (String id : ENGLISH.keySet()) {
            keys.add("mdlint.msg." + id);
        }
        return keys;
    }

    private static final String ESCAPED_TEXT = "((?:\\\\.|[^\\[\\]\\\\])*+)";
    // A full or collapsed reference link use: [text][label] / [text][] (also the tail of an image).
    private static final Pattern FULL_REF =
            Pattern.compile("(?<!\\\\)\\[" + ESCAPED_TEXT + "\\]\\[" + ESCAPED_TEXT + "\\]");
    // A reference definition: [label]: url (the url may sit on the next line).
    private static final Pattern REF_DEF = Pattern.compile("^ {0,3}\\[((?:\\\\.|[^\\[\\]\\\\])++)\\]:");
    private static final Pattern LABEL_ONLY_DEF = Pattern.compile("^ {0,3}\\[(?:\\\\.|[^\\[\\]\\\\])++\\]:\\s*$");
    // A bare URL (no surrounding markdown link / autolink syntax); its tail is trimmed by trimUrl.
    private static final Pattern BARE_URL = Pattern.compile("https?://[^\\s<>\\[\\]]+");
    private static final Pattern ENTITY_AT_END =
            Pattern.compile("&(?:[A-Za-z][A-Za-z0-9]*|#[0-9]+|#[xX][0-9A-Fa-f]+);$");
    private static final String URL_TAIL = ".,;:!?'\"*_~";

    /** Lints with no rules disabled. */
    public static List<Diagnostic> lint(String text) {
        return lint(text, Set.of());
    }

    /** Lints {@code text}; {@code disabled} rule codes (and inline directives) suppress those diagnostics. */
    public static List<Diagnostic> lint(String text, Set<String> disabled) {
        return lint(text, disabled, MarkdownLintOptions.DEFAULT);
    }

    /**
     * As {@link #lint(String, Set)}, with the project's rule {@code options}; a
     * {@code markdownlint-configure-file} comment in the document refines both.
     */
    public static List<Diagnostic> lint(String text, Set<String> disabled, MarkdownLintOptions options) {
        List<Diagnostic> out = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            return out;
        }
        String[] lines = text.split("\n", -1);
        MarkdownBlocks blocks = MarkdownBlocks.scan(lines);
        MarkdownLintDirectives directives = MarkdownLintDirectives.compute(blocks);
        MarkdownLintOptions opts = directives.options(options == null ? MarkdownLintOptions.DEFAULT : options);
        Emitter emit = new Emitter(out, disabled == null ? Set.of() : disabled, directives, opts);
        Set<String> defined = definitions(blocks);
        int n = blocks.lineCount();

        boolean tagOpen = false; // an HTML tag opened on an earlier line and not closed yet
        int blankRun = 0; // consecutive blank prose lines, this one included
        for (int i = 0; i < n; i++) {
            if (!blocks.isText(i)) {
                tagOpen = false;
                blankRun = 0;
                if (opts.tabsInCode() && blocks.isCode(i)) {
                    hardTabs(emit, blocks.line(i), i + 1); // MD010.code_blocks: asked for explicitly
                }
                continue; // code, front matter, raw HTML: no other line-level rules
            }
            String line = blocks.line(i);
            int lineNo = i + 1;
            blankRun = line.isBlank() ? blankRun + 1 : 0;
            whitespaceRules(emit, blocks, lines, i, blankRun);
            headingRules(emit, blocks, i);
            if (line.isBlank()) {
                tagOpen = false;
                continue;
            }
            int tagEnd = tagOpen ? line.indexOf('>') : -1; // up to here the line is still inside that tag
            boolean attributesOnly = tagOpen && tagEnd < 0;
            int inTag = tagOpen ? (attributesOnly ? line.length() : tagEnd) : 0;
            bareUrls(emit, blocks, i, inTag);
            bareEmails(emit, blocks, i, inTag);
            tagOpen = attributesOnly || endsInsideTag(line, Math.max(0, tagEnd));

            // MD052: [text][label] / [text][] with no matching definition.
            if (line.indexOf("][") >= 0) {
                Matcher ref = FULL_REF.matcher(line);
                while (ref.find()) {
                    String label = ref.group(2).isBlank() ? ref.group(1) : ref.group(2);
                    if (!blocks.inCodeSpan(i, ref.start())
                            && !blocks.inHtmlComment(i, ref.start())
                            && !defined.contains(normalizeLabel(label))) {
                        emit.add(lineNo, ref.start() + 1, ref.end() - ref.start(), "MD052", "MD052");
                    }
                }
            }
        }

        fenceRules(emit, blocks);
        structureRules(emit, blocks);

        // MD047: file should end with a single trailing newline.
        int lastLine = lines.length; // split("\n",-1): a trailing "\n" yields a final empty element
        if (!text.endsWith("\n")) {
            emit.add(lastLine, Math.max(1, blocks.line(n - 1).length()), 1, "MD047", "MD047.missing");
        } else if (text.endsWith("\n\n") || text.endsWith("\n\r\n")) {
            emit.add(lastLine, 1, 1, "MD047", "MD047.multiple");
        }

        out.sort((a, b) ->
                a.line() != b.line() ? Integer.compare(a.line(), b.line()) : Integer.compare(a.column(), b.column()));
        return out;
    }

    /** Collects diagnostics, dropping the ones a setting or an inline directive turned off. */
    private record Emitter(
            List<Diagnostic> out, Set<String> off, MarkdownLintDirectives directives, MarkdownLintOptions options) {
        void add(int line, int column, int length, String code, String message) {
            if (off.contains(code) || directives.disabled(line - 1, code)) {
                return;
            }
            out.add(new Diagnostic(
                    line, column, Math.max(1, length), WARNING, code, ENGLISH.get(message), "mdlint.msg." + message));
        }
    }

    /** MD010: every run of hard tabs of {@code line}. */
    private static void hardTabs(Emitter emit, String line, int lineNo) {
        for (int tab = line.indexOf('\t'); tab >= 0; ) {
            int run = tab;
            while (run < line.length() && line.charAt(run) == '\t') {
                run++;
            }
            emit.add(lineNo, tab + 1, run - tab, "MD010", "MD010");
            tab = line.indexOf('\t', run);
        }
    }

    /** MD010, MD009, MD012 on prose line {@code i}, the {@code blankRun}-th blank line in a row (0: not blank). */
    private static void whitespaceRules(Emitter emit, MarkdownBlocks blocks, String[] lines, int i, int blankRun) {
        String line = blocks.line(i);
        int lineNo = i + 1;
        hardTabs(emit, line, lineNo);
        // MD009: trailing whitespace — except a hard line break.
        int end = line.length();
        while (end > 0 && (line.charAt(end - 1) == ' ' || line.charAt(end - 1) == '\t')) {
            end--;
        }
        if (end < line.length()
                && !isHardLineBreak(line, end, lines, i, emit.options().brSpaces())) {
            emit.add(lineNo, end + 1, line.length() - end, "MD009", "MD009");
        }
        // MD012: more consecutive blank lines than allowed. (The empty element after a file's only "\n" is not
        // a line.)
        boolean phantom = i == 1 && blocks.lineCount() == 2;
        if (blankRun > emit.options().maxBlankLines() && !phantom) {
            emit.add(lineNo, 1, 1, "MD012", "MD012");
        }
    }

    /**
     * The number of {@code #} that open an ATX heading on prose line {@code i} (1–6), else 0. A {@code #tag} or
     * {@code #123} that continues a paragraph is text, not a heading with a missing space.
     */
    static int headingHashes(MarkdownBlocks blocks, int i) {
        if (!blocks.isText(i)) {
            return 0;
        }
        String c = blocks.content(i);
        int hashes = leadingHashes(c);
        if (hashes == 0) {
            return 0;
        }
        if (spacedHeading(c)) {
            return hashes;
        }
        // "#tag" lines: walk up to the first of a run of them. What stands above the run decides for all of it.
        int first = i;
        for (int guard = 0;
                guard < 100
                        && first > 0
                        && blocks.isText(first - 1)
                        && leadingHashes(blocks.content(first - 1)) > 0
                        && !spacedHeading(blocks.content(first - 1));
                guard++) {
            first--;
        }
        if (first > 0 && blocks.isText(first - 1) && !blocks.isBlank(first - 1)) {
            String above = blocks.content(first - 1);
            if (leadingHashes(above) == 0 && !MarkdownLintDirectives.isDirectiveLine(above)) {
                return 0;
            }
        }
        return hashes;
    }

    /** Whether {@code c} starts with 1–6 {@code #} followed by a space, a tab or the end of the line. */
    private static boolean spacedHeading(String c) {
        int after = leadingSpaces(c) + leadingHashes(c);
        return leadingHashes(c) > 0 && (after >= c.length() || c.charAt(after) == ' ' || c.charAt(after) == '\t');
    }

    /** MD023, MD018, MD019, MD026, MD022 on prose line {@code i}. */
    private static void headingRules(Emitter emit, MarkdownBlocks blocks, int i) {
        int hashes = headingHashes(blocks, i);
        if (hashes == 0) {
            return;
        }
        String c = blocks.content(i);
        int off = blocks.contentStart(i);
        int lineNo = i + 1;
        int indent = leadingSpaces(c);
        // MD023: headings must start at the beginning of the line (of their list item / block quote).
        if (indent > 0) {
            emit.add(lineNo, off + 1, indent, "MD023", "MD023");
        }
        int after = indent + hashes;
        // MD018 / MD019: ATX heading hash spacing.
        if (after < c.length() && c.charAt(after) != ' ' && c.charAt(after) != '\t') {
            emit.add(lineNo, off + indent + 1, hashes, "MD018", "MD018");
        } else {
            int p = after;
            while (p < c.length() && c.charAt(p) == ' ') {
                p++;
            }
            if (p - after > 1 && p < c.length()) {
                emit.add(lineNo, off + after + 1, p - after, "MD019", "MD019");
            }
        }
        // MD026: trailing punctuation in a heading.
        int punct = trailingPunctuation(c, after, emit.options().punctuation());
        if (punct >= 0) {
            emit.add(lineNo, off + punct + 1, 1, "MD026", "MD026");
        }
        // MD022: a heading should be surrounded by blank lines.
        if (!blankLines(blocks, i, -1, emit.options().linesAbove())) {
            emit.add(lineNo, off + indent + 1, hashes, "MD022", "MD022.before");
        }
        if (!blankLines(blocks, i, 1, emit.options().linesBelow())) {
            emit.add(lineNo, off + indent + 1, hashes, "MD022", "MD022.after");
        }
    }

    /**
     * Whether {@code need} blank lines stand before ({@code step} -1) or after (+1) line {@code i}. The start
     * and the end of the document, and the front matter, count as all the blank lines one could want; directive
     * lines are looked past.
     */
    private static boolean blankLines(MarkdownBlocks blocks, int i, int step, int need) {
        int j = neighbour(blocks, i, step);
        for (int k = 0; k < need; k++, j += step) {
            if (j < 0 || j >= blocks.lineCount() || blocks.kind(j) == MarkdownBlocks.Kind.FRONT_MATTER) {
                return true;
            }
            if (!blocks.isBlank(j)) {
                return false;
            }
        }
        return true;
    }

    /**
     * The nearest line before ({@code step} -1) or after (+1) line {@code i}, looking past lines that hold only
     * a markdownlint directive: a {@code disable-next-line} comment is not "text touching the heading".
     */
    private static int neighbour(MarkdownBlocks blocks, int i, int step) {
        int j = i + step;
        while (j >= 0 && j < blocks.lineCount() && MarkdownLintDirectives.isDirectiveLine(blocks.content(j))) {
            j += step;
        }
        return j;
    }

    /** MD034 on prose line {@code i}; the first {@code skipUntil} characters lie inside an HTML tag. */
    private static void bareUrls(Emitter emit, MarkdownBlocks blocks, int i, int skipUntil) {
        String line = blocks.line(i);
        if (line.indexOf("://") < 0) {
            return;
        }
        String content = blocks.content(i);
        if (REF_DEF.matcher(content).find()
                || (i > 0
                        && blocks.isText(i - 1)
                        && LABEL_ONLY_DEF.matcher(blocks.content(i - 1)).find())) {
            return; // the URL of a reference definition
        }
        LineScan scan = new LineScan(line);
        Matcher url = BARE_URL.matcher(line);
        while (url.find()) {
            int s = url.start();
            int end = trimUrl(line, s, url.end());
            if (s < skipUntil || blocks.inCodeSpan(i, s) || blocks.inHtmlComment(i, s)) {
                continue;
            }
            char before = s > 0 ? line.charAt(s - 1) : ' ';
            if (before == '(' || before == '<' || linkDestination(line, s) || scan.inLinkTextOrTag(s)) {
                continue;
            }
            if (line.regionMatches(true, end, "</a>", 0, 4)) {
                continue; // <a …>https://…</a>: already a link
            }
            emit.add(i + 1, s + 1, end - s, "MD034", "MD034");
        }
    }

    /**
     * MD034 for bare e-mail addresses on prose line {@code i} ({@code name@example.com} outside {@code <…>}, a
     * link, code or a URL). Deliberately narrower than GFM's autolink: the domain must end in a letters-only label
     * ({@code pkg@1.2.3} is a version, not an address) and {@code git@host:path} is a remote, not a mailbox.
     */
    private static void bareEmails(Emitter emit, MarkdownBlocks blocks, int i, int skipUntil) {
        String line = blocks.line(i);
        int at = line.indexOf('@');
        if (at < 0 || REF_DEF.matcher(blocks.content(i)).find()) {
            return;
        }
        LineScan scan = new LineScan(line);
        for (; at >= 0; at = line.indexOf('@', at + 1)) {
            int s = at;
            while (s > 0 && localPartChar(line.charAt(s - 1))) {
                s--;
            }
            int end = at + 1;
            while (end < line.length()
                    && (Character.isLetterOrDigit(line.charAt(end)) || ".-".indexOf(line.charAt(end)) >= 0)) {
                end++;
            }
            while (end > at + 1 && ".-".indexOf(line.charAt(end - 1)) >= 0) {
                end--;
            }
            int lastDot = line.lastIndexOf('.', end - 1);
            if (s == at || lastDot <= at + 1 || end - lastDot < 3 || !letters(line, lastDot + 1, end)) {
                continue; // no local part, no dot in the domain, or no letters-only last label
            }
            char before = s > 0 ? line.charAt(s - 1) : ' ';
            char after = end < line.length() ? line.charAt(end) : ' ';
            if (!Character.isWhitespace(before) && "([{\"'*_~>,;".indexOf(before) < 0
                    || before == '(' && s > 1 && line.charAt(s - 2) == ']') {
                continue; // <a@b.co>, mailto:a@b.co, https://user@host.io, path/a@b.co, [x](a@b.co)
            }
            if (after == '/'
                    || after == '@'
                    || (after == ':' && end + 1 < line.length() && !Character.isWhitespace(line.charAt(end + 1)))) {
                continue; // git@github.com:owner/repo.git, user@host/path
            }
            if (s < skipUntil || blocks.inCodeSpan(i, s) || blocks.inHtmlComment(i, s) || scan.inLinkTextOrTag(s)) {
                continue;
            }
            emit.add(i + 1, s + 1, end - s, "MD034", "MD034");
        }
    }

    private static boolean localPartChar(char c) {
        return c < 128 && (Character.isLetterOrDigit(c) || "._%+-".indexOf(c) >= 0);
    }

    private static boolean letters(String s, int from, int to) {
        for (int p = from; p < to; p++) {
            if (!Character.isLetter(s.charAt(p))) {
                return false;
            }
        }
        return to > from;
    }

    /** The end of the URL at {@code [start, end)} without sentence punctuation or an unbalanced {@code )}. */
    static int trimUrl(String line, int start, int end) {
        while (end > start + 1) {
            char last = line.charAt(end - 1);
            if (URL_TAIL.indexOf(last) >= 0) {
                end--;
            } else if (last == ')' && count(line, start, end, '(') < count(line, start, end, ')')) {
                end--;
            } else {
                break;
            }
        }
        return end;
    }

    private static int count(String s, int from, int to, char c) {
        int n = 0;
        for (int p = from; p < to; p++) {
            if (s.charAt(p) == c) {
                n++;
            }
        }
        return n;
    }

    /** Whether the URL at {@code s} is the destination of an inline link written with spaces: {@code ](  url )}. */
    private static boolean linkDestination(String line, int s) {
        int p = s - 1;
        while (p >= 0 && (line.charAt(p) == ' ' || line.charAt(p) == '\t')) {
            p--;
        }
        return p >= 1 && p < s - 1 && line.charAt(p) == '(' && line.charAt(p - 1) == ']';
    }

    /** Whether {@code line} ends inside an HTML tag that opened at or after {@code from} ({@code <a} … no {@code >}). */
    private static boolean endsInsideTag(String line, int from) {
        int lt = line.lastIndexOf('<');
        if (lt < from || lt + 1 >= line.length() || line.indexOf('>', lt) >= 0) {
            return false;
        }
        char tag = line.charAt(lt + 1);
        return Character.isLetter(tag);
    }

    /**
     * Answers "is this offset inside {@code [...]} or an HTML tag" for increasing offsets of one line in a single
     * left-to-right walk, so a line with thousands of URLs stays linear.
     */
    private static final class LineScan {
        private final String line;
        private final int lastCloseBracket;
        private final int lastGt;
        private int pos;
        private int openBracket = -1;
        private int closeBracket = -1;
        private int lt = -1;
        private int gt = -1;

        LineScan(String line) {
            this.line = line;
            this.lastCloseBracket = line.lastIndexOf(']');
            this.lastGt = line.lastIndexOf('>');
        }

        /**
         * Whether the URL starting at {@code idx} is already part of a link rather than bare: it sits inside
         * {@code [...]} (link text, as in {@code [https://x.io](https://x.io)}, or a shortcut reference) or
         * inside an HTML tag (an {@code href="https://…"} / {@code src="https://…"} attribute).
         */
        boolean inLinkTextOrTag(int idx) {
            for (; pos < idx; pos++) {
                switch (line.charAt(pos)) {
                    case '[' -> openBracket = pos;
                    case ']' -> closeBracket = pos;
                    case '<' -> lt = pos;
                    case '>' -> gt = pos;
                    default -> {}
                }
            }
            if (openBracket >= 0 && closeBracket < openBracket && lastCloseBracket >= idx) {
                return true;
            }
            if (lt < 0 || gt > lt || lastGt < idx || lt + 1 >= line.length()) {
                return false;
            }
            char tag = line.charAt(lt + 1);
            return Character.isLetter(tag) || tag == '/';
        }
    }

    /** The normalized labels of every reference definition outside code. */
    private static Set<String> definitions(MarkdownBlocks blocks) {
        Set<String> defined = new HashSet<>();
        for (int i = 0; i < blocks.lineCount(); i++) {
            if (!blocks.isText(i) || blocks.line(i).indexOf("]:") < 0) {
                continue;
            }
            Matcher m = REF_DEF.matcher(blocks.content(i));
            if (m.find()) {
                defined.add(normalizeLabel(m.group(1)));
            }
        }
        return defined;
    }

    /** MD040 and MD031 for every fenced block. */
    private static void fenceRules(Emitter emit, MarkdownBlocks blocks) {
        for (MarkdownBlocks.Fence f : blocks.fences()) {
            int open = f.openLine();
            if (f.info().isEmpty()) {
                emit.add(open + 1, f.markerOffset() + 1, f.length(), "MD040", "MD040");
            }
            int prev = neighbour(blocks, open, -1);
            if (prev >= 0 && blocks.kind(prev) != MarkdownBlocks.Kind.FRONT_MATTER && !blocks.isBlank(prev)) {
                emit.add(open + 1, f.markerOffset() + 1, f.length(), "MD031", "MD031.before");
            }
            if (f.closed()) {
                int next = neighbour(blocks, f.closeLine(), 1);
                if (next < blocks.lineCount() && !blocks.isBlank(next)) {
                    String close = blocks.line(f.closeLine());
                    int at = 0;
                    while (at < close.length() && close.charAt(at) != f.marker()) {
                        at++;
                    }
                    int end = at;
                    while (end < close.length() && close.charAt(end) == f.marker()) {
                        end++;
                    }
                    emit.add(f.closeLine() + 1, at + 1, end - at, "MD031", "MD031.after");
                }
            }
        }
    }

    /** MD041, MD025, MD001: the heading structure of the whole document. */
    private static void structureRules(Emitter emit, MarkdownBlocks blocks) {
        boolean titled = frontMatterTitle(blocks, emit.options());

        // MD041: the first content line (after front matter, blank lines and HTML comments) is an H1.
        if (!titled) {
            for (int j = blocks.frontMatterEnd() + 1; j < blocks.lineCount(); j++) {
                if (blocks.kind(j) == MarkdownBlocks.Kind.HTML_COMMENT
                        || blocks.line(j).isBlank()) {
                    continue;
                }
                if (!headingOfLevel(blocks, j, emit.options().firstLineLevel())) {
                    int off = blocks.contentStart(j);
                    emit.add(j + 1, off + 1, blocks.line(j).length() - off, "MD041", "MD041");
                }
                break; // only the first content line matters
            }
        }

        // MD001 / MD025. A heading the block scan says is code or raw HTML is not one.
        int h1Count = titled ? 1 : 0; // a front-matter title is the document's top-level heading
        int prevLevel = 0;
        for (int[] h : headings(blocks)) {
            int level = h[1];
            int lineNo = h[0] + 1;
            String c = blocks.content(h[0]);
            int hashes = leadingHashes(c);
            int column = blocks.contentStart(h[0]) + leadingSpaces(c) + 1;
            int length = hashes > 0 ? hashes : Math.max(1, c.stripTrailing().length() - leadingSpaces(c));
            if (level == emit.options().titleLevel() && ++h1Count > 1) {
                emit.add(lineNo, column, length, "MD025", "MD025");
            }
            if (prevLevel > 0 && level > prevLevel + 1) {
                emit.add(lineNo, column, length, "MD001", "MD001");
            }
            prevLevel = level;
        }
    }

    /**
     * The document's headings as {@code {line, level}}: ATX headings and setext titles ({@code ===} / {@code ---}
     * under a line of text in the same container), on the lines the block scan calls prose.
     */
    static List<int[]> headings(MarkdownBlocks blocks) {
        List<int[]> out = new ArrayList<>();
        int n = blocks.lineCount();
        for (int i = 0; i < n; i++) {
            if (!blocks.isText(i) || blocks.isBlank(i)) {
                continue;
            }
            String c = blocks.content(i);
            if (spacedHeading(c)) {
                out.add(new int[] {i, leadingHashes(c)});
                continue;
            }
            int level = i + 1 < n ? setextLevel(blocks, i) : 0;
            if (level > 0) {
                out.add(new int[] {i, level});
                i++; // the underline
            }
        }
        return out;
    }

    /** 1 or 2 when line {@code i} is text underlined by {@code ===} / {@code ---} on the next line, else 0. */
    private static int setextLevel(MarkdownBlocks blocks, int i) {
        if (!blocks.isText(i + 1) || blocks.contentStart(i + 1) != blocks.contentStart(i)) {
            return 0;
        }
        String text = blocks.content(i);
        String under = blocks.content(i + 1).stripTrailing();
        if (under.isEmpty() || leadingSpaces(under) > 3 || leadingSpaces(text) > 3) {
            return 0;
        }
        under = under.stripLeading();
        char mark = under.charAt(0);
        if ((mark != '=' && mark != '-') || !under.chars().allMatch(ch -> ch == mark)) {
            return 0;
        }
        String t = text.strip();
        boolean plainText = !t.startsWith("|")
                && !t.chars().allMatch(ch -> ch == '-' || ch == '=' || ch == '*' || ch == '_' || ch == ' ');
        return plainText ? (mark == '=' ? 1 : 2) : 0;
    }

    /**
     * Whether line {@code j} is a heading of {@code level}: {@code # Title}, a setext title ({@code ===} level 1,
     * {@code ---} level 2), or an HTML {@code <hN>}.
     */
    private static boolean headingOfLevel(MarkdownBlocks blocks, int j, int level) {
        String c = blocks.content(j);
        if (blocks.kind(j) == MarkdownBlocks.Kind.HTML_BLOCK || blocks.isText(j)) {
            if (c.stripLeading().regionMatches(true, 0, "<h" + level, 0, 3)) {
                return true;
            }
        }
        if (!blocks.isText(j)) {
            return false;
        }
        if (leadingHashes(c) == level) {
            return true;
        }
        return level <= 2 && j + 1 < blocks.lineCount() && setextLevel(blocks, j) == level;
    }

    /** Whether the front matter names the document ({@code title: …} in YAML, {@code title = …} in TOML). */
    static boolean frontMatterTitle(MarkdownBlocks blocks, MarkdownLintOptions options) {
        Pattern title = options.frontMatterTitlePattern();
        for (int j = 1; title != null && j < blocks.frontMatterEnd(); j++) {
            if (title.matcher(blocks.line(j)).find()) {
                return true;
            }
        }
        return false;
    }

    /** The 0-based index of trailing punctuation in an ATX heading whose marker ends at {@code after}, else -1. */
    static int trailingPunctuation(String line, int after) {
        return trailingPunctuation(line, after, MarkdownLintOptions.DEFAULT_PUNCTUATION);
    }

    /** As {@link #trailingPunctuation(String, int)}, for the given set of {@code punctuation} characters. */
    static int trailingPunctuation(String line, int after, String punctuation) {
        int e = line.length();
        while (e > after && (line.charAt(e - 1) == ' ' || line.charAt(e - 1) == '\t')) {
            e--;
        }
        // Drop a closing ATX hash run ("## Title ##").
        int hashEnd = e;
        while (hashEnd > after && line.charAt(hashEnd - 1) == '#') {
            hashEnd--;
        }
        if (hashEnd < e) { // there was a closing run; trim the space before it
            e = hashEnd;
            while (e > after && (line.charAt(e - 1) == ' ' || line.charAt(e - 1) == '\t')) {
                e--;
            }
        }
        if (e <= after) {
            return -1; // empty heading text
        }
        char last = line.charAt(e - 1);
        if (punctuation.indexOf(last) < 0) {
            return -1;
        }
        if (last == ';' && ENTITY_AT_END.matcher(line).region(after, e).find()) {
            return -1; // "&copy;" ends in an entity, not in punctuation
        }
        return e - 1;
    }

    private static String normalizeLabel(String label) {
        return label == null ? "" : label.strip().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
    }

    /** Count of leading {@code #} (1–6) if {@code line} is an ATX heading marker, else 0. */
    static int leadingHashes(String line) {
        int indent = leadingSpaces(line);
        if (indent > 3) {
            return 0;
        }
        int p = indent;
        int n = 0;
        while (p < line.length() && line.charAt(p) == '#') {
            p++;
            n++;
        }
        return n >= 1 && n <= 6 ? n : 0;
    }

    static int leadingSpaces(String line) {
        int n = 0;
        while (n < line.length() && line.charAt(n) == ' ') {
            n++;
        }
        return n;
    }

    static String stripCr(String line) {
        return !line.isEmpty() && line.charAt(line.length() - 1) == '\r' ? line.substring(0, line.length() - 1) : line;
    }

    /**
     * True when {@code line}'s trailing whitespace (which starts at {@code contentEnd}) is a Markdown
     * <b>hard line break</b> — exactly two spaces, on a non-blank line that is followed by a non-blank one —
     * rather than stray whitespace.
     *
     * <p>Two trailing spaces render as {@code <br>}; upstream markdownlint permits them by default
     * ({@code br_spaces: 2}). Flagging them meant {@link MarkdownLintFix} stripped them, silently reflowing
     * addresses, poems and signature blocks into one paragraph. Three or more (or a trailing tab) stay
     * flagged — those aren't a break either.
     */
    static boolean isHardLineBreak(String line, int contentEnd, String[] lines, int i) {
        return isHardLineBreak(line, contentEnd, lines, i, 2);
    }

    /** As above with {@code MD009.br_spaces}: exactly {@code brSpaces} trailing spaces (fewer than 2: never). */
    static boolean isHardLineBreak(String line, int contentEnd, String[] lines, int i, int brSpaces) {
        if (brSpaces < 2 || contentEnd == 0 || line.length() - contentEnd != brSpaces) {
            return false; // a blank/whitespace-only line, or not exactly the break's trailing chars
        }
        for (int p = contentEnd; p < line.length(); p++) {
            if (line.charAt(p) != ' ') {
                return false; // tabs don't make a break
            }
        }
        return i + 1 < lines.length && !stripCr(lines[i + 1]).isBlank(); // a break needs a line to break to
    }
}

package com.editora.ui;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import com.editora.i18n.Messages;

/**
 * Decides how loudly a status message is shown, from the catalog key it was built from.
 *
 * <p>The status bar has an error channel (danger colour, an unread marker that survives until the message
 * log is opened), but most failures were reported through the plain channel — "PDF export failed" as
 * muted echo text that the next message replaced. Rather than depend on ~90 call sites each choosing the
 * right method, the rule lives here and is applied where every plain status message lands:
 *
 * <ul>
 *   <li>a {@code status.*} key whose last segment contains {@code fail} or {@code error} is an
 *       <b>error</b> ({@code status.pdf.exportFailed}, {@code status.debug.error}, {@code status.failedOpen});
 *   <li>one containing {@code cannot} or {@code invalid} is a <b>warning</b> — the user asked for something
 *       that is not possible here ({@code status.narrow.cannot}, {@code status.gotoInvalid});
 *   <li>the keys in {@link #NOT_FAILURES} merely mention failure and stay ordinary messages.
 * </ul>
 *
 * <p>Name a new failure message accordingly and it is routed for free. The message text is mapped back to
 * its key through the active catalog (placeholders become wildcards), so the rule holds in every language
 * and for messages that reach the status bar through a callback rather than a direct call.
 */
final class StatusSeverity {

    /** {@code status.*} keys that contain a failure word without reporting a failure. */
    static final Set<String> NOT_FAILURES =
            Set.of("status.testrunner.filterFailed", "status.testrunner.rerunFailedUnsupported", "status.log.noError");

    private static final Pattern ERROR_WORD = Pattern.compile("(?i)fail|error");
    private static final Pattern WARN_WORD = Pattern.compile("(?i)cannot|invalid");

    /** Fewer literal characters than this and a pattern is too vague to identify a message by. */
    private static final int MIN_LITERAL = 6;

    private record Rule(String prefix, Pattern pattern, MessageLog.Severity severity) {}

    private record Index(int generation, Map<String, MessageLog.Severity> exact, List<Rule> rules) {}

    private static volatile Index index;

    private StatusSeverity() {}

    /** The severity a catalog key implies (see the class comment). Pure. */
    static MessageLog.Severity ofKey(String key) {
        if (key == null || !key.startsWith("status.") || NOT_FAILURES.contains(key)) {
            return MessageLog.Severity.INFO;
        }
        String last = key.substring(key.lastIndexOf('.') + 1);
        if (ERROR_WORD.matcher(last).find()) {
            return MessageLog.Severity.ERROR;
        }
        return WARN_WORD.matcher(last).find() ? MessageLog.Severity.WARN : MessageLog.Severity.INFO;
    }

    /** The severity of a message already translated by {@code tr(...)}; INFO when it is not a failure. */
    static MessageLog.Severity of(String message) {
        if (message == null || message.isBlank()) {
            return MessageLog.Severity.INFO;
        }
        Index idx = index();
        MessageLog.Severity exact = idx.exact().get(message);
        if (exact != null) {
            return exact;
        }
        for (Rule rule : idx.rules()) {
            if (message.startsWith(rule.prefix())
                    && rule.pattern().matcher(message).matches()) {
                return rule.severity();
            }
        }
        return MessageLog.Severity.INFO;
    }

    private static Index index() {
        Index idx = index;
        int generation = Messages.generation();
        if (idx == null || idx.generation() != generation) {
            Map<String, MessageLog.Severity> exact = new HashMap<>();
            List<Rule> rules = new ArrayList<>();
            for (String key : Messages.keys()) {
                MessageLog.Severity severity = ofKey(key);
                if (severity == MessageLog.Severity.INFO) {
                    continue;
                }
                String text = Messages.tr(key);
                if (text.indexOf('{') < 0) {
                    // No placeholders: tr(key) returns the text verbatim, while tr(key, args) runs it
                    // through MessageFormat, which consumes a lone apostrophe ("l'URL" → "lURL").
                    exact.put(text, severity);
                    exact.put(Messages.tr(key, ""), severity);
                } else if (literalLength(text) >= MIN_LITERAL) {
                    rules.add(new Rule(literalPrefix(text), toRegex(text), severity));
                }
            }
            idx = new Index(generation, exact, rules);
            index = idx;
        }
        return idx;
    }

    /**
     * A regex matching every message a {@link java.text.MessageFormat} pattern can produce: each
     * {@code {…}} placeholder becomes a wildcard, {@code ''} a literal apostrophe, a {@code '…'} run
     * literal text. Pure.
     */
    static Pattern toRegex(String messagePattern) {
        StringBuilder regex = new StringBuilder();
        StringBuilder literal = new StringBuilder();
        walk(messagePattern, literal, () -> {
            regex.append(Pattern.quote(literal.toString())).append(".*");
            literal.setLength(0);
        });
        regex.append(Pattern.quote(literal.toString()));
        return Pattern.compile(regex.toString(), Pattern.DOTALL);
    }

    /** The literal text before the first placeholder (a cheap pre-check before the regex). Pure. */
    static String literalPrefix(String messagePattern) {
        StringBuilder literal = new StringBuilder();
        String[] prefix = {null};
        walk(messagePattern, literal, () -> {
            if (prefix[0] == null) {
                prefix[0] = literal.toString();
            }
        });
        return prefix[0] == null ? literal.toString() : prefix[0];
    }

    private static int literalLength(String messagePattern) {
        StringBuilder literal = new StringBuilder();
        int[] total = {0};
        walk(messagePattern, literal, () -> {
            total[0] += literal.length();
            literal.setLength(0);
        });
        return total[0] + literal.length();
    }

    /** Feeds a MessageFormat pattern's literal text into {@code literal}, calling back at each placeholder. */
    private static void walk(String messagePattern, StringBuilder literal, Runnable onPlaceholder) {
        boolean quoted = false;
        for (int i = 0; i < messagePattern.length(); i++) {
            char c = messagePattern.charAt(i);
            if (c == '\'') {
                if (i + 1 < messagePattern.length() && messagePattern.charAt(i + 1) == '\'') {
                    literal.append('\'');
                    i++;
                } else {
                    quoted = !quoted;
                }
            } else if (c == '{' && !quoted) {
                int depth = 1;
                while (++i < messagePattern.length() && depth > 0) {
                    char d = messagePattern.charAt(i);
                    depth += d == '{' ? 1 : d == '}' ? -1 : 0;
                }
                i--; // the loop above stopped one past the closing brace
                onPlaceholder.run();
            } else {
                literal.append(c);
            }
        }
    }
}

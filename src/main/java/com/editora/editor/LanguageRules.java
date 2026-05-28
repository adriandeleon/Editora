package com.editora.editor;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Regex-based highlighting rules for one language: an ordered list of named token rules compiled
 * into a single alternation {@link Pattern}. The first alternative to match at a position wins, so
 * rule order is significant (e.g. comments and strings before keywords).
 */
public final class LanguageRules {

    /** A single token rule. {@code group} must be a valid regex group name (letters only). */
    public record Rule(String group, String regex, String styleClass) {
    }

    private final String name;
    private final List<Rule> rules;
    private final Pattern pattern;

    public LanguageRules(String name, List<Rule> rules) {
        this.name = name;
        this.rules = List.copyOf(rules);
        this.pattern = rules.isEmpty() ? null : compile(rules);
    }

    private static Pattern compile(List<Rule> rules) {
        StringBuilder sb = new StringBuilder();
        for (Rule rule : rules) {
            if (sb.length() > 0) {
                sb.append('|');
            }
            sb.append("(?<").append(rule.group()).append('>').append(rule.regex()).append(')');
        }
        return Pattern.compile(sb.toString());
    }

    public String name() {
        return name;
    }

    public Pattern pattern() {
        return pattern;
    }

    /** The style class for whichever rule matched the current find, or null if none. */
    public String styleFor(Matcher matcher) {
        for (Rule rule : rules) {
            if (matcher.group(rule.group()) != null) {
                return rule.styleClass();
            }
        }
        return null;
    }
}

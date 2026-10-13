package com.editora.ui;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.editora.config.Settings;
import com.editora.markdown.MarkdownLint;
import com.editora.markdown.MarkdownLintOptions;

/**
 * Which Markdown-lint rules the user has on, read from and written to {@link Settings} in one place — so the
 * Settings checklist, the rule picker and the linter cannot disagree. A rule that is on by default is off when
 * it is in {@code markdownLintDisabledRules}; a rule that is off by default (line length, inline HTML) is on
 * when it is in {@code markdownLintEnabledRules}.
 */
final class MarkdownLintRules {

    private MarkdownLintRules() {}

    /** Whether the user has rule {@code code} on. */
    static boolean enabled(Settings settings, String code) {
        return MarkdownLint.DEFAULT_OFF.contains(code.toUpperCase(Locale.ROOT))
                ? contains(settings.getMarkdownLintEnabledRules(), code)
                : !contains(settings.getMarkdownLintDisabledRules(), code);
    }

    /** Switches rule {@code code} on or off for the user (the caller saves and re-applies). */
    static void setEnabled(Settings settings, String code, boolean on) {
        String normal = code.toUpperCase(Locale.ROOT);
        boolean optIn = MarkdownLint.DEFAULT_OFF.contains(normal);
        List<String> list = new ArrayList<>(
                optIn ? safe(settings.getMarkdownLintEnabledRules()) : safe(settings.getMarkdownLintDisabledRules()));
        list.removeIf(c -> c == null || normal.equalsIgnoreCase(c.strip()));
        if (on == optIn) {
            list.add(normal);
        }
        if (optIn) {
            settings.setMarkdownLintEnabledRules(list);
        } else {
            settings.setMarkdownLintDisabledRules(list);
        }
    }

    /** Every rule the user has off: the ones switched off, and the opt-in ones not switched on. */
    static Set<String> off(Settings settings) {
        Set<String> off = new HashSet<>();
        for (String code : safe(settings.getMarkdownLintDisabledRules())) {
            if (code != null && !code.isBlank()) {
                off.add(code.strip().toUpperCase(Locale.ROOT));
            }
        }
        for (String code : MarkdownLint.DEFAULT_OFF) {
            if (!contains(settings.getMarkdownLintEnabledRules(), code)) {
                off.add(code);
            }
        }
        return off;
    }

    /**
     * {@code options} with the user's per-rule severities, wherever the project's config has not given the rule
     * one of its own.
     */
    static MarkdownLintOptions withSeverities(Settings settings, MarkdownLintOptions options) {
        Map<String, String> chosen = settings.getMarkdownLintSeverities();
        MarkdownLintOptions out = options;
        if (chosen != null) {
            for (Map.Entry<String, String> e : chosen.entrySet()) {
                String code = e.getKey() == null ? "" : e.getKey().strip().toUpperCase(Locale.ROOT);
                if (!code.isEmpty() && !options.severities().containsKey(code)) {
                    out = out.withSeverity(code, e.getValue());
                }
            }
        }
        return out;
    }

    private static boolean contains(List<String> list, String code) {
        for (String c : safe(list)) {
            if (c != null && code.equalsIgnoreCase(c.strip())) {
                return true;
            }
        }
        return false;
    }

    private static List<String> safe(List<String> list) {
        return list == null ? List.of() : list;
    }
}

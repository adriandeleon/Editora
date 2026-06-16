package com.editora.completion;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

import com.editora.snippet.Snippet;
import com.editora.snippet.SnippetManager;

/**
 * Merges completion sources for the word being typed: <b>snippets</b> (prefix-matched for the buffer's
 * language) and, in prose buffers, <b>dictionary words</b> ({@link DictionaryWords}). Results are
 * de-duplicated, ranked (snippets first; within each group, exact-case prefix before others, then
 * alphabetical), and capped. The ranking/merging helpers are pure and unit-tested; the engine itself is
 * a thin adapter over {@link SnippetManager} + the user dictionary.
 */
public final class CompletionEngine {

    /** Max rows shown in the popup. */
    public static final int MAX = 12;
    /** Don't trigger until the typed prefix is at least this long. */
    public static final int MIN_PREFIX = 2;

    private final SnippetManager snippets;
    private final Supplier<Set<String>> userWords;

    public CompletionEngine(SnippetManager snippets, Supplier<Set<String>> userWords) {
        this.snippets = snippets;
        this.userWords = userWords == null ? Set::of : userWords;
    }

    /** Signature matches {@link CompletionProvider}. */
    public List<Completion> complete(String snippetLang, String dictLang, String prefix, boolean prose) {
        if (prefix == null || prefix.length() < MIN_PREFIX) {
            return List.of();
        }
        List<Completion> snip = snippetCompletions(snippets.forLanguage(snippetLang), prefix);
        List<Completion> words = new ArrayList<>();
        if (prose) {
            for (String s : DictionaryWords.startingWith(dictLang, prefix, userWords.get(), MAX)) {
                words.add(Completion.word(s, null));
            }
        } else if ("mermaid".equalsIgnoreCase(snippetLang)) {
            // Mermaid diagram buffers: offer keyword/diagram-type completions alongside the snippets.
            for (String k : MermaidKeywords.startingWith(prefix, MAX)) {
                words.add(Completion.word(k, null));
            }
        }
        return merge(snip, words, prefix, MAX);
    }

    /** Snippets whose prefix starts with {@code prefix} (case-insensitive), ranked, mapped to completions. Pure. */
    public static List<Completion> snippetCompletions(List<Snippet> all, String prefix) {
        List<Snippet> matched = new ArrayList<>();
        for (Snippet s : all) {
            if (s.prefix() != null
                    && startsWithIgnoreCase(s.prefix(), prefix)
                    && !s.prefix().equalsIgnoreCase(prefix)) {
                matched.add(s);
            }
        }
        matched.sort((a, b) -> rankCompare(a.prefix(), b.prefix(), prefix));
        List<Completion> out = new ArrayList<>(matched.size());
        for (Snippet s : matched) {
            out.add(Completion.ofSnippet(s));
        }
        return out;
    }

    /** Combines snippet + word completions, de-dupes by insert text (snippet wins), and caps. Pure. */
    public static List<Completion> merge(List<Completion> snippets, List<Completion> words, String prefix, int max) {
        Map<String, Completion> byInsert = new LinkedHashMap<>();
        for (Completion c : snippets) {
            byInsert.putIfAbsent(c.insert(), c);
        }
        List<Completion> sortedWords = new ArrayList<>(words);
        sortedWords.sort((a, b) -> rankCompare(a.insert(), b.insert(), prefix));
        for (Completion c : sortedWords) {
            byInsert.putIfAbsent(c.insert(), c);
        }
        List<Completion> out = new ArrayList<>(byInsert.values());
        return out.size() > max ? out.subList(0, max) : out;
    }

    /**
     * Orders language-server completions by the server's relevance — preselected first, then by
     * {@code sortText} (nulls last), then alphabetically by label — matching IntelliJ (which surfaces the
     * most relevant candidate at the top). Pure/unit-tested; a stable sort, so equal-key items keep the
     * server's order. Returns a new list (the input is left untouched).
     */
    public static List<Completion> sortLspByRelevance(List<Completion> items) {
        if (items == null || items.size() < 2) {
            return items == null ? List.of() : items;
        }
        List<Completion> out = new ArrayList<>(items);
        out.sort((a, b) -> {
            if (a.preselect() != b.preselect()) {
                return a.preselect() ? -1 : 1;
            }
            String sa = a.sortText();
            String sb = b.sortText();
            if (sa == null && sb != null) {
                return 1;
            }
            if (sa != null && sb == null) {
                return -1;
            }
            if (sa != null) {
                int c = sa.compareTo(sb);
                if (c != 0) {
                    return c;
                }
            }
            String la = a.label() == null ? "" : a.label();
            String lb = b.label() == null ? "" : b.label();
            return la.compareToIgnoreCase(lb);
        });
        return out;
    }

    /** Exact-case prefix matches first, then case-insensitive alphabetical. */
    static int rankCompare(String a, String b, String prefix) {
        boolean ea = a.startsWith(prefix);
        boolean eb = b.startsWith(prefix);
        if (ea != eb) {
            return ea ? -1 : 1;
        }
        int byLen = Integer.compare(a.length(), b.length());
        if (byLen != 0 && a.length() <= prefix.length() + 1) {
            return byLen; // nudge very-close matches up
        }
        return a.toLowerCase(Locale.ROOT).compareTo(b.toLowerCase(Locale.ROOT));
    }

    private static boolean startsWithIgnoreCase(String s, String prefix) {
        return s.length() >= prefix.length() && s.regionMatches(true, 0, prefix, 0, prefix.length());
    }
}

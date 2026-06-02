package com.editora.snippet;

import java.util.List;

/**
 * The result of parsing a snippet body: the literal {@code text} to insert (with placeholders'
 * default text in place and variables resolved) plus the {@link TabStop}s, ordered for navigation
 * (1, 2, 3, … then {@code $0} last). Pure data produced by {@link SnippetParser}.
 */
public record ParsedSnippet(String text, List<TabStop> stops) {
}

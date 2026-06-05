package com.editora.search;

/** An immutable search request: the text plus the match options shared with the in-editor find bar. */
public record SearchQuery(String text, boolean caseSensitive, boolean regex, boolean wholeWord) {
}

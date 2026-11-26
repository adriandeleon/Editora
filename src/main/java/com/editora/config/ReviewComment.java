package com.editora.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.jspecify.annotations.Nullable;

/**
 * One comment in a {@link ReviewThread}: its author, its Markdown body, when it was written and last edited, and an
 * optional suggestion — the text proposed to replace the thread's lines, as a GitHub {@code suggestion} block does.
 * A Jackson-serialized record; {@code editedAt} is 0 for a comment never edited.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ReviewComment(
        String id,
        ReviewAuthor author,
        String body,
        long createdAt,
        long editedAt,
        @Nullable String suggestion) {

    public ReviewComment {
        id = id == null ? "" : id;
        author = author == null ? ReviewAuthor.user() : author;
        body = body == null ? "" : body;
    }

    /** This comment with a new body, marked edited at {@code now}. */
    public ReviewComment edited(String newBody, @Nullable String newSuggestion, long now) {
        return new ReviewComment(id, author, newBody, createdAt, now, newSuggestion);
    }
}

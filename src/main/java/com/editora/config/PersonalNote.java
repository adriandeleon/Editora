package com.editora.config;

import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * A user-authored annotation attached to a file without modifying it. Identified by a stable {@link UUID},
 * carries a {@link FileIdentity} (to re-find its file), a {@link NoteScope} + {@link TextAnchor} (where in
 * the file), a free-text {@code body}, {@code tags}, a {@link NoteStatus}, and creation/update timestamps
 * as epoch millis (kept as {@code long} so the default Jackson mapper needs no java.time module).
 *
 * <p>A Jackson-serialized record ({@code com.editora.config} is opened to jackson.databind). Persisted,
 * bucketed per project, in {@link NoteStore} ({@code notes.json}).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record PersonalNote(
        UUID id,
        FileIdentity file,
        NoteScope scope,
        TextAnchor anchor,
        String body,
        List<String> tags,
        NoteStatus status,
        long createdAt,
        long updatedAt) {

    public PersonalNote {
        id = id == null ? UUID.randomUUID() : id;
        scope = scope == null ? NoteScope.LINE : scope;
        anchor = anchor == null ? new TextAnchor(0, 0, 0, 0, "", "", "") : anchor;
        body = body == null ? "" : body;
        tags = tags == null ? List.of() : List.copyOf(tags);
        status = status == null ? NoteStatus.ACTIVE : status;
    }

    /** A fresh note created now (timestamps set to {@code System.currentTimeMillis()}). */
    public static PersonalNote create(
            FileIdentity file, NoteScope scope, TextAnchor anchor, String body, List<String> tags) {
        long now = System.currentTimeMillis();
        return new PersonalNote(UUID.randomUUID(), file, scope, anchor, body, tags, NoteStatus.ACTIVE, now, now);
    }

    public PersonalNote withBody(String newBody) {
        return new PersonalNote(id, file, scope, anchor, newBody, tags, status, createdAt, touch());
    }

    public PersonalNote withTags(List<String> newTags) {
        return new PersonalNote(id, file, scope, anchor, body, newTags, status, createdAt, touch());
    }

    public PersonalNote withStatus(NoteStatus newStatus) {
        return new PersonalNote(id, file, scope, anchor, body, tags, newStatus, createdAt, touch());
    }

    /** This note re-anchored (and, implicitly, re-activated unless caller sets a status). Keeps timestamps' createdAt. */
    public PersonalNote withAnchor(TextAnchor newAnchor) {
        return new PersonalNote(id, file, scope, newAnchor, body, tags, status, createdAt, touch());
    }

    private long touch() {
        return System.currentTimeMillis();
    }
}

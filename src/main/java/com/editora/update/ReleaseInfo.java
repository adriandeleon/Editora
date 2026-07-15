package com.editora.update;

/**
 * A published release, parsed from the GitHub releases API — the normalized {@code version} (the tag with any
 * leading {@code v} stripped, e.g. {@code 0.9.5}), the human-facing release page {@code url}, and the release
 * {@code name} (may be blank). Pure data, toolkit-free.
 */
public record ReleaseInfo(String version, String url, String name) {}

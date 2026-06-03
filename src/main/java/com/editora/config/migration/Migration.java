package com.editora.config.migration;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * One schema migration step that upgrades a config file's JSON/TOML tree from version {@code v} to
 * {@code v+1}. Steps operate on the in-memory Jackson tree (so the same mechanism works for both JSON and
 * TOML — {@code TomlMapper} produces ordinary nodes), are registered per {@link ConfigSchema}, and are
 * applied in sequence by {@link ConfigMigrations#upgrade}. Keep them pure and total: never throw on
 * unexpected-but-harmless input — return the best tree you can.
 */
@FunctionalInterface
public interface Migration {
    JsonNode apply(JsonNode input);
}

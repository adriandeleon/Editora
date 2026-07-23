package com.editora.config;

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * The persisted, app-global user abbreviation dictionary (in {@code abbreviations.json}). Kept in its own
 * file rather than {@code settings.toml} — it is data (a growing word list), not a handful of preferences —
 * mirroring {@link MacroStore} / {@code BookmarkStore}. Schema-versioned via
 * {@link com.editora.config.migration.ConfigSchema#ABBREVIATIONS}. The auto-expand <em>mode</em> is a
 * preference and stays in {@link Settings#isAbbrevMode()}.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class AbbrevStore {

    public static final int SCHEMA_VERSION = 1;

    public int schemaVersion = SCHEMA_VERSION;
    /** The abbreviations, in user order. */
    public List<Abbreviation> abbreviations = new ArrayList<>();
}

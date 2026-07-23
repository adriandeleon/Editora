package com.editora.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * One user-defined abbreviation: a short word and the text it expands to. Persisted in {@code settings.toml}
 * as an entry of {@link Settings#getAbbreviations()}; a mutable POJO so the Settings editor can edit it in
 * place, mirroring {@code externalTool.ExternalTool} / {@code todo.TodoPattern}.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public final class Abbreviation {

    private String abbreviation = "";
    private String expansion = "";

    public Abbreviation() {}

    public Abbreviation(String abbreviation, String expansion) {
        this.abbreviation = abbreviation == null ? "" : abbreviation;
        this.expansion = expansion == null ? "" : expansion;
    }

    public String getAbbreviation() {
        return abbreviation;
    }

    public void setAbbreviation(String abbreviation) {
        this.abbreviation = abbreviation == null ? "" : abbreviation;
    }

    public String getExpansion() {
        return expansion;
    }

    public void setExpansion(String expansion) {
        this.expansion = expansion == null ? "" : expansion;
    }
}

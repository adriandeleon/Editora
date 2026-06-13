package com.editora.plugin;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * One plugin listed in a remote registry's {@code index.json}. A lenient Jackson POJO (unknown fields
 * ignored), like {@link PluginManifest}. The {@code download} URL points at a {@code .zip} whose top level
 * is the plugin folder contents ({@code plugin.json} + jar + asset dirs), and {@code sha256} is that zip's
 * lowercase-hex SHA-256 (verified before unpacking). The {@code com.editora.plugin} package is opened to
 * jackson.databind in {@code module-info}.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class RegistryEntry {

    /** Stable plugin id (matches the installed folder name / the manifest id). */
    public String id = "";
    public String name = "";
    public String version = "";
    public String description = "";
    public String author = "";
    /** Project/home page (shown in the install dialog), optional. */
    public String homepage = "";
    /** HTTPS URL of the distributable {@code .zip} (e.g. a GitHub release asset). */
    public String download = "";
    /** Lowercase-hex SHA-256 of the downloaded zip — mandatory; a mismatch aborts the install. */
    public String sha256 = "";
    /** Minimum Editora version required, or blank for any. Compared to {@code AppInfo.VERSION}. */
    public String minEditoraVersion = "";
}

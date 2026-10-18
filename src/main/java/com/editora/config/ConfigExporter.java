package com.editora.config;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import com.editora.plugin.PluginInstaller;

/**
 * Exports a config directory to a timestamped {@code .zip}. Used by the Settings → Advanced
 * "Export configuration" button to back up the active config dir ({@code ~/.editora},
 * {@code ~/.editora-dev}, or a {@code --config-dir} override) into the user's home directory.
 *
 * <p>The pure helpers ({@link #zipName} and {@link #export} with an injected timestamp + destination)
 * are unit-tested; {@link ConfigManager#exportConfig()} wires them to the live config dir, home dir,
 * and clock.
 */
public final class ConfigExporter {

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd_HHmmss");

    private ConfigExporter() {}

    /**
     * Builds the export file name for {@code configDir} at {@code now}, embedding the app
     * {@code version} and {@code userName}, e.g. {@code .editora} with version {@code 1.0.0} and user
     * {@code adriandeleon} → {@code editora-config-1.0.0-adriandeleon-2026-06-04_153012.zip}
     * ({@code .editora-dev} → an {@code editora-dev-…} prefix). A leading dot is dropped so the name
     * reads cleanly and the dir name distinguishes a dev backup from a production one; version and
     * user are filename-sanitized (see {@link #sanitize}). Pure.
     */
    static String zipName(Path configDir, String version, String userName, LocalDateTime now) {
        Path name = configDir.getFileName();
        String base = name == null ? "editora" : name.toString();
        if (base.startsWith(".")) {
            base = base.substring(1);
        }
        if (base.isBlank()) {
            base = "editora";
        }
        return base + "-config-" + sanitize(version) + "-" + sanitize(userName) + "-" + now.format(STAMP) + ".zip";
    }

    /**
     * Makes {@code s} safe to embed in a file name: keeps letters/digits/{@code . _ -}, replaces any
     * other run (spaces, slashes, etc.) with a single {@code _}, trims leading/trailing separators,
     * and falls back to {@code "unknown"} when nothing usable remains. Pure.
     */
    static String sanitize(String s) {
        if (s == null) {
            return "unknown";
        }
        String cleaned = s.trim().replaceAll("[^A-Za-z0-9._-]+", "_").replaceAll("^[_.-]+|[_.-]+$", "");
        return cleaned.isEmpty() ? "unknown" : cleaned;
    }

    /** Top-level files that describe the running process rather than the user's configuration. */
    private static final Set<String> RUNTIME_FILES = Set.of(
            "instance.lock",
            "stores.lock",
            "instance.properties",
            "mcp-endpoint.json",
            "spawned-servers.txt",
            "editora-session.log");

    /**
     * Whether the file at {@code relative} (its path inside the config dir, forward-slash separated) belongs
     * in an export. Left out, because none of it is configuration and all of it is recreated on demand:
     *
     * <ul>
     *   <li>the in-app installer's folders under {@code plugins/} — {@code lsp/}, {@code dap/}, {@code typst/}
     *       and {@code tools/} (ShellCheck, shfmt): downloaded language servers, debug adapters and tools,
     *       hundreds of megabytes of binaries built for this machine's OS, which another one could not run.
     *       They are the folders {@link PluginInstaller#isReservedId} keeps plugins out of; a plugin the user
     *       installed, {@code plugins/<id>/}, is kept;
     *   <li>{@code jdtls-workspaces/} — the Java language server's index cache;
     *   <li>{@code history/} — Local History: the index and a copy of every recorded revision of the user's
     *       <em>files</em>, from every project. That is their work, not their configuration, it can be many
     *       times the size of everything else, and an export is the thing people hand to someone else;
     *   <li>{@code sync/} — settings sync's clone of the user's repository and its backup copies of files
     *       that are in the export anyway;
     *   <li>the instance lock, the single-instance and MCP endpoint files (the latter holds a live access
     *       token), the spawned-process ledger and the session log;
     *   <li>a staging file of an atomic write that is in flight ({@code .<name>-<random>.tmp});
     *   <li>{@code http-history/} — the HTTP Client's saved responses: bodies, full URLs (a query may hold a
     *       key) and whatever a server sent back — unless the user asked for it at export time
     *       ({@code includeHttpHistory}).
     * </ul>
     *
     * Pure.
     */
    static boolean included(String relative, boolean includeHttpHistory) {
        if (relative.startsWith(HTTP_HISTORY_DIR + "/") && !includeHttpHistory) {
            return false;
        }
        return includedConfig(relative);
    }

    /** The folder of the HTTP Client's saved responses, left out of an export unless asked for. */
    static final String HTTP_HISTORY_DIR = HttpResponseHistory.DIR_NAME;

    /** {@link #included(String, boolean)} without the HTTP response history. */
    static boolean included(String relative) {
        return included(relative, false);
    }

    private static boolean includedConfig(String relative) {
        if (inBuiltInToolFolder(relative)
                || relative.startsWith("jdtls-workspaces/")
                || relative.startsWith("history/")
                || relative.startsWith("sync/")
                || relative.startsWith("cache/") // rebuilt on demand (the HTTP response-handler runner)
                || relative.startsWith(ConfigImport.BACKUPS_DIR + "/")) { // copies made before an import
            return false;
        }
        String name = relative.substring(relative.lastIndexOf('/') + 1);
        if (name.startsWith(".") && name.endsWith(".tmp")) {
            return false;
        }
        return relative.contains("/") || !RUNTIME_FILES.contains(relative);
    }

    /** Whether {@code relative} lies in one of the installer's {@code plugins/<reserved id>/} folders. */
    private static boolean inBuiltInToolFolder(String relative) {
        String prefix = ConfigManager.PLUGINS_DIR_NAME + "/";
        if (!relative.startsWith(prefix)) {
            return false;
        }
        int slash = relative.indexOf('/', prefix.length());
        return slash > prefix.length() && PluginInstaller.isReservedId(relative.substring(prefix.length(), slash));
    }

    /**
     * Zips the configuration under {@code configDir} — every regular file {@link #included} accepts,
     * recursively, entries relative to it with forward-slash separators — into
     * {@code destinationDir/<zipName>} and returns the created file. A missing/empty config dir yields a valid
     * empty zip. {@code destinationDir} must exist.
     *
     * <p>The zip is created <b>owner-only</b>: it contains a copy of {@code settings.json} — and so of the AI
     * provider's API key — plus the user's private notes, and it lands in their home directory, which is
     * world-traversable. Locking down the config dir's own files while writing a world-readable archive of
     * them next door would protect nothing.
     *
     * <p>A failed export leaves nothing behind: the half-written zip is deleted, so a truncated archive is
     * never mistaken for a backup. A file that disappears while the directory is being read (a staging
     * file of a write in flight) is skipped rather than failing the export.
     *
     * @throws IOException if the destination can't be written or a file can't be read
     */
    public static Path export(Path configDir, Path destinationDir, String version, String userName, LocalDateTime now)
            throws IOException {
        return export(configDir, destinationDir, version, userName, now, false);
    }

    /** As {@link #export(Path, Path, String, String, LocalDateTime)}, with the HTTP response history when asked. */
    public static Path export(
            Path configDir,
            Path destinationDir,
            String version,
            String userName,
            LocalDateTime now,
            boolean includeHttpHistory)
            throws IOException {
        return export(configDir, destinationDir, version, userName, now, includeHttpHistory, false);
    }

    /**
     * As {@link #export(Path, Path, String, String, LocalDateTime, boolean)}; the AI provider API keys in the
     * settings file (and its backups) are written blank unless {@code includeApiKeys}. An export is the thing
     * people hand to someone else, and a key is a billable credential.
     */
    public static Path export(
            Path configDir,
            Path destinationDir,
            String version,
            String userName,
            LocalDateTime now,
            boolean includeHttpHistory,
            boolean includeApiKeys)
            throws IOException {
        return exportTo(
                configDir,
                destinationDir.resolve(zipName(configDir, version, userName, now)),
                includeHttpHistory,
                includeApiKeys);
    }

    /** As {@link #export}, into the zip file {@code zip} (the copy made before an import, for one). */
    static Path exportTo(Path configDir, Path zip, boolean includeHttpHistory, boolean includeApiKeys)
            throws IOException {
        ConfigWriter.createOwnerOnly(zip); // newOutputStream truncates it and keeps the mode
        boolean complete = false;
        try (OutputStream out = Files.newOutputStream(zip);
                ZipOutputStream zos = new ZipOutputStream(out)) {
            if (Files.isDirectory(configDir)) {
                List<Path> files;
                try (Stream<Path> walk = Files.walk(configDir)) {
                    files = walk.filter(Files::isRegularFile).sorted().toList();
                }
                for (Path file : files) {
                    String entry = configDir.relativize(file).toString().replace('\\', '/');
                    if (!included(entry, includeHttpHistory) || file.equals(zip)) {
                        continue;
                    }
                    try (InputStream in = Files.newInputStream(file)) {
                        zos.putNextEntry(new ZipEntry(entry));
                        if (isSettingsCopy(entry)) { // a settings file: no sync-URL secret, no API key unless asked
                            String text = withoutSyncCredentials(new String(in.readAllBytes(), StandardCharsets.UTF_8));
                            zos.write((includeApiKeys ? text : withoutApiKeys(text)).getBytes(StandardCharsets.UTF_8));
                        } else {
                            in.transferTo(zos);
                        }
                        zos.closeEntry();
                    } catch (NoSuchFileException vanished) {
                        // deleted since the walk listed it — nothing to back up
                    }
                }
            }
            zos.finish();
            complete = true;
        } catch (UncheckedIOException walkFailure) {
            throw walkFailure.getCause();
        } finally {
            if (!complete) {
                try {
                    Files.deleteIfExists(zip);
                } catch (IOException ignored) {
                    // the original failure is the one worth reporting
                }
            }
        }
        return zip;
    }

    /** The settings file and its copies ({@code settings.json.v104.bak}, the legacy {@code settings.toml}…). Pure. */
    static boolean isSettingsCopy(String relative) {
        return !relative.contains("/") && relative.startsWith("settings.");
    }

    private static final java.util.regex.Pattern JSON_KEY =
            java.util.regex.Pattern.compile("(\"aiApiKey\\w*\"\\s*:\\s*)\"(?:[^\"\\\\]|\\\\.)*\"");
    private static final java.util.regex.Pattern TOML_KEY =
            java.util.regex.Pattern.compile("(?m)^(\\s*aiApiKey\\w*\\s*=\\s*)(?:\"(?:[^\"\\\\]|\\\\.)*\"|'[^']*')");

    private static final java.util.regex.Pattern SYNC_URL = java.util.regex.Pattern.compile(
            "(?m)(\"syncRepoUrl\"\\s*:\\s*\"|^\\s*syncRepoUrl\\s*=\\s*\")((?:[^\"\\\\\\n]|\\\\.)*)\"");

    /**
     * {@code text} with the password or token taken out of the Settings Sync repository URL. Since E3 the Sync
     * page refuses such a URL, but one stored before that is still in the file; an export drops the secret
     * always, with or without the API keys. Pure.
     */
    static String withoutSyncCredentials(String text) {
        return SYNC_URL.matcher(text)
                .replaceAll(m -> java.util.regex.Matcher.quoteReplacement(
                        m.group(1) + com.editora.sync.SyncEngine.withoutCredentials(m.group(2)) + "\""));
    }

    /**
     * {@code text} with the value of every {@code aiApiKey…} entry blanked, in JSON or TOML form. Textual on
     * purpose: a backup that no longer parses (a {@code .corrupt.bak}) holds the key just the same. Pure.
     */
    static String withoutApiKeys(String text) {
        String json = JSON_KEY.matcher(text).replaceAll("$1\"\"");
        return TOML_KEY.matcher(json).replaceAll("$1\"\"");
    }
}

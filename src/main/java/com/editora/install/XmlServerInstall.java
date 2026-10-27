package com.editora.install;

import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.List;

import org.jspecify.annotations.Nullable;

/**
 * Editora's own copy of lemminx, the XML language server, and what an XML command left blank runs.
 *
 * <p>The installer used to write the downloaded binary's path into the XML command setting, as an override of a
 * default ({@code lemminx}) that is never on PATH under that name. "Reset to default", resetting all settings, or
 * importing settings from another OS then disconnected the copy still sitting in the config folder, and the only
 * remedy offered was a second download (review A9). Now the setting stays blank after an install, and blank — or
 * the bare default, or a path into Editora's own folder that no longer exists — means Editora's copy when there
 * is one, before anything on PATH.
 */
public final class XmlServerInstall {

    private XmlServerInstall() {}

    /** The command the registry runs for XML when the setting is blank. */
    private static final String DEFAULT_COMMAND = "lemminx";

    /** Where Editora installs lemminx under {@code configDir}. */
    public static Path folder(Path configDir) {
        return configDir.resolve(InstallCatalog.LEMMINX_DIR);
    }

    /**
     * Editora's lemminx under {@code configDir}, or null when it has none: the program the pinned install puts
     * there ({@code lemminx}, {@code lemminx.exe} on Windows), else the per-platform name an install from before
     * the pin kept ({@code lemminx-linux-x86_64}, …).
     */
    public static @Nullable Path ownCopy(@Nullable Path configDir) {
        if (configDir == null) {
            return null;
        }
        InstallCatalog.Platform platform = InstallCatalog.currentPlatform();
        Path dir = folder(configDir);
        Path pinned = dir.resolve(InstallCatalog.toolFileName(InstallCatalog.LEMMINX_STEP, platform));
        if (Files.isRegularFile(pinned)) {
            return pinned;
        }
        InstallCatalog.PinnedAsset asset = InstallCatalog.pinnedTool(InstallCatalog.LEMMINX_STEP)
                .map(t -> t.assetFor(platform))
                .orElse(null);
        String legacy = asset == null ? null : asset.member();
        if (legacy != null) {
            Path old = dir.resolve(legacy);
            if (Files.isRegularFile(old)) {
                return old;
            }
        }
        return null;
    }

    /**
     * The XML command that runs: {@code configured} as it is, unless it is blank, the bare default, or a path into
     * Editora's own lemminx folder that is gone (an update renamed the program, or the settings came from another
     * OS) — then Editora's copy, when there is one.
     */
    public static String effectiveCommand(@Nullable String configured, @Nullable Path configDir) {
        String raw = configured == null ? "" : configured.strip();
        boolean useOwn = raw.isEmpty() || raw.equals(DEFAULT_COMMAND) || staleOwnPath(raw, configDir);
        if (!useOwn) {
            return raw;
        }
        Path own = ownCopy(configDir);
        return own == null ? raw : InstallCatalog.quoteCommandPath(own.toString());
    }

    /**
     * Whether {@code configured} is not the user's own choice of server: blank, the bare default, or a path into
     * Editora's own lemminx folder (what an earlier install wrote into the setting). Such a command is replaced by
     * whatever Install puts there, so the install banner may offer Install for it.
     */
    public static boolean editorasOwn(@Nullable String configured, @Nullable Path configDir) {
        String raw = configured == null ? "" : configured.strip();
        return raw.isEmpty() || raw.equals(DEFAULT_COMMAND) || inOwnFolder(raw, configDir);
    }

    /**
     * Whether an installer's command for XML is Editora's native copy (a program in a {@code plugins/lsp/xml}
     * folder), which the setting need not hold — rather than another server it fell back to. Pure.
     */
    public static boolean isNativeInstall(@Nullable String installedCommand) {
        if (installedCommand == null || installedCommand.isBlank()) {
            return false;
        }
        List<String> argv = com.editora.run.ProgramArgs.commandLine(installedCommand);
        try {
            // the folder alone decides, so a path need not be absolute on this OS (a POSIX path on Windows)
            Path parent = argv.isEmpty() ? null : Path.of(argv.get(0)).getParent();
            return parent != null && parent.endsWith(Path.of(InstallCatalog.LEMMINX_DIR));
        } catch (InvalidPathException e) {
            return false;
        }
    }

    private static boolean staleOwnPath(String raw, @Nullable Path configDir) {
        Path exe = executable(raw);
        return exe != null && inOwnFolder(raw, configDir) && !Files.exists(exe);
    }

    private static boolean inOwnFolder(String raw, @Nullable Path configDir) {
        Path exe = executable(raw);
        if (exe == null || configDir == null) {
            return false;
        }
        return exe.toAbsolutePath()
                .normalize()
                .startsWith(folder(configDir).toAbsolutePath().normalize());
    }

    /** The program {@code command} starts, when it is an absolute path; else null. */
    private static @Nullable Path executable(@Nullable String command) {
        if (command == null || command.isBlank()) {
            return null;
        }
        List<String> argv = com.editora.run.ProgramArgs.commandLine(command);
        if (argv.isEmpty()) {
            return null;
        }
        try {
            Path p = Path.of(argv.get(0));
            return p.isAbsolute() ? p : null;
        } catch (InvalidPathException e) {
            return null;
        }
    }
}

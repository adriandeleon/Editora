package com.editora.plugin;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.jspecify.annotations.Nullable;

/**
 * Registry plugins whose feature is now part of Editora. A retired plugin never loads — not from its folder,
 * not from a registry, not from a zip — so the built-in feature and the plugin can never run side by side. At
 * startup an installed copy is removed, after what it remembered that the built-in feature can use is carried
 * over.
 *
 * <p>Today: {@code gpg-tools} (GnuPG), replaced by built-in encrypted files. Its {@code data/gpg.properties}
 * held an explicit gpg path and the last recipient.
 */
public final class RetiredPlugins {

    private static final Logger LOG = Logger.getLogger(RetiredPlugins.class.getName());

    private RetiredPlugins() {}

    /** Retired plugin id → the name the plugin had. */
    static final Map<String, String> RETIRED = Map.of("gpg-tools", "GnuPG");

    /** The GnuPG plugin's id. */
    public static final String GPG_TOOLS = "gpg-tools";

    /** Whether {@code id} is a plugin that is now built in (compared without case). */
    public static boolean isRetired(String id) {
        return id != null && RETIRED.containsKey(id.strip().toLowerCase(Locale.ROOT));
    }

    /** What the GnuPG plugin remembered: an explicit gpg path and the last recipient (both may be blank). */
    public record GpgToolsSettings(String gpgPath, String recipient) {}

    /** Reads {@code <plugin>/data/gpg.properties}; blanks when it is missing or unreadable. */
    static GpgToolsSettings readGpgTools(Path pluginDir) {
        Path file = pluginDir.resolve(PluginInstaller.DATA_DIR).resolve("gpg.properties");
        Properties p = new Properties();
        if (Files.isRegularFile(file)) {
            try (InputStream in = Files.newInputStream(file)) {
                p.load(in);
            } catch (IOException | IllegalArgumentException e) {
                LOG.log(Level.FINE, "Could not read " + file, e);
            }
        }
        return new GpgToolsSettings(
                p.getProperty("gpg.path", "").strip(),
                p.getProperty("recipient", "").strip());
    }

    /**
     * Removes every installed retired plugin under {@code pluginsDir}, handing the GnuPG plugin's settings to
     * {@code carryGpgTools} first.
     *
     * @return the names of the plugins removed (empty when there were none)
     */
    public static List<String> retire(Path pluginsDir, Consumer<GpgToolsSettings> carryGpgTools) {
        List<String> removed = new ArrayList<>();
        if (pluginsDir == null || !Files.isDirectory(pluginsDir)) {
            return removed;
        }
        for (Map.Entry<String, String> e : RETIRED.entrySet()) {
            Path dir = pluginsDir.resolve(e.getKey());
            if (!Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) {
                continue;
            }
            if (GPG_TOOLS.equals(e.getKey()) && carryGpgTools != null) {
                carryGpgTools.accept(readGpgTools(dir));
            }
            try {
                PluginInstaller.deleteRecursivelyForRetirement(dir);
                removed.add(e.getValue());
            } catch (IOException ex) {
                // Left in place, it still never loads (PluginManager skips retired folders); the next start retries.
                LOG.log(Level.WARNING, "Could not remove retired plugin " + dir, ex);
            }
        }
        if (!removed.isEmpty()) {
            NOTICE.set(List.copyOf(removed));
        }
        return removed;
    }

    private static final AtomicReference<@Nullable List<String>> NOTICE = new AtomicReference<>();

    /** The plugins this launch removed, once — the first window tells the user — then null. */
    public static @Nullable List<String> takeNotice() {
        return NOTICE.getAndSet(null);
    }
}

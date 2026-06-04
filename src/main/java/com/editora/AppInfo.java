package com.editora;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/**
 * Single source of the app name, version, and build timestamp — shared by the {@code --version} CLI
 * output and the About dialog so they never drift apart.
 */
public final class AppInfo {

    public static final String NAME = "Editora";
    public static final String VERSION = "1.0.0";
    /** Project home page (the website's custom domain). */
    public static final String HOMEPAGE = "https://editora-project.dev";
    /** Copyright notice (matches the bundled {@code LICENSE} file). */
    public static final String COPYRIGHT = "© 2026 Adrián Arturo De León Saldivar";
    /** Short license name; full terms are in the bundled {@code LICENSE} file. */
    public static final String LICENSE = "MIT License";

    private AppInfo() {
    }

    /** The Maven-filtered build timestamp; falls back gracefully for unfiltered/dev runs. */
    public static String buildTime() {
        try (InputStream in = AppInfo.class.getResourceAsStream("/com/editora/build-info.properties")) {
            if (in == null) {
                return "unknown";
            }
            Properties props = new Properties();
            props.load(in);
            String time = props.getProperty("build.time", "");
            // Unfiltered (e.g. run straight from an IDE) leaves the literal Maven placeholder.
            return time.isEmpty() || time.startsWith("${") ? "(dev build)" : time;
        } catch (IOException e) {
            return "unknown";
        }
    }

    /** A one-line version string for {@code --version}, e.g. {@code "Editora 1.0.0 (built …)"}. */
    public static String versionLine() {
        return NAME + " " + VERSION + " (built " + buildTime() + ")";
    }
}

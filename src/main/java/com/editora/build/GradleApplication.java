package com.editora.build;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads the main class a Gradle build script declares via the {@code application} plugin, so Run/Debug can
 * name what it will launch (and pre-fill a saved run configuration) without scanning sources or asking the
 * language server. A lightweight regex sniff of the common spellings — not a Gradle evaluation:
 *
 * <ul>
 *   <li>{@code mainClass = 'com.app.Main'} (Groovy / Kotlin DSL assignment)</li>
 *   <li>{@code mainClass.set("com.app.Main")} (Kotlin DSL property)</li>
 *   <li>{@code mainClassName = 'com.app.Main'} (legacy, pre-Gradle 6.4)</li>
 * </ul>
 *
 * <p>A value built from a variable or a computed expression yields {@code null} — the caller falls back to a
 * source scan / the language server. Pure and unit-tested.
 */
public final class GradleApplication {

    private GradleApplication() {}

    /** {@code mainClass = "x"} / {@code mainClassName = "x"} — quoted literal, single or double quotes. */
    private static final Pattern ASSIGN = Pattern.compile("\\bmainClass(?:Name)?\\s*=\\s*[\"']([\\w.$]+)[\"']");

    /** {@code mainClass.set("x")} — the Kotlin-DSL Property form. */
    private static final Pattern SET =
            Pattern.compile("\\bmainClass(?:Name)?\\s*\\.\\s*set\\s*\\(\\s*[\"']([\\w.$]+)[\"']\\s*\\)");

    /** The declared main class, or {@code null} when the script doesn't declare a literal one. */
    public static String mainClass(String buildScript) {
        if (buildScript == null || buildScript.isBlank()) {
            return null;
        }
        Matcher m = ASSIGN.matcher(buildScript);
        if (m.find()) {
            return m.group(1);
        }
        Matcher s = SET.matcher(buildScript);
        return s.find() ? s.group(1) : null;
    }
}

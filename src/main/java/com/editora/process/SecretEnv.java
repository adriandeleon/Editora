package com.editora.process;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Decides which inherited environment variables look like <b>secrets</b>, and removes them from a child
 * process's environment. Used for children that are fed untrusted document content — the preview render
 * CLIs — through {@link ProcessRunner#runScrubbed}: an editor is routinely launched from a shell that carries
 * {@code GITHUB_TOKEN}, {@code ANTHROPIC_API_KEY}, {@code AWS_SECRET_ACCESS_KEY} and the like, and a diagram
 * source that can read its environment (PlantUML's {@code %getenv}) or include a URL can send them anywhere.
 *
 * <p>Deliberately a name heuristic, not an allowlist: the render tools legitimately need {@code PATH},
 * {@code HOME}, {@code JAVA_HOME}, {@code DISPLAY}, {@code PUPPETEER_*}, font and locale variables, and an
 * allowlist would break them on some platform we did not test. Names are matched case-insensitively (Windows
 * environment names are). Pure apart from {@link #scrub}'s in-place removal; unit-tested.
 */
public final class SecretEnv {

    private static final List<String> SUFFIXES = List.of("_TOKEN", "_KEY", "_SECRET", "_PAT");
    private static final List<String> FRAGMENTS = List.of("PASSWORD", "PASSWD", "SECRET", "CREDENTIAL");
    private static final List<String> PREFIXES = List.of("ANTHROPIC_", "OPENAI_", "AWS_");
    private static final List<String> EXACT = List.of("GITHUB_TOKEN", "GH_TOKEN", "TOKEN");

    private SecretEnv() {}

    /** Whether an environment variable of this {@code name} should not reach an untrusted-content child. */
    public static boolean isSecretName(String name) {
        if (name == null || name.isEmpty()) {
            return false;
        }
        String n = name.toUpperCase(Locale.ROOT);
        return EXACT.contains(n)
                || SUFFIXES.stream().anyMatch(n::endsWith)
                || FRAGMENTS.stream().anyMatch(n::contains)
                || PREFIXES.stream().anyMatch(n::startsWith);
    }

    /** Removes every {@link #isSecretName secret-looking} variable from {@code env}, in place. */
    public static void scrub(Map<String, String> env) {
        List<String> doomed = new ArrayList<>();
        for (String name : env.keySet()) {
            if (isSecretName(name)) {
                doomed.add(name);
            }
        }
        doomed.forEach(env::remove);
    }
}

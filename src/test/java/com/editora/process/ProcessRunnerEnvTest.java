package com.editora.process;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * The two child environments {@link ProcessRunner} builds, as pure map transformations.
 *
 * <p>{@code LC_ALL=C} exists so git/ripgrep output parses the same in every locale. It used to be applied to
 * every child — the user's program, their build, their language servers — where it is actively harmful: a JVM
 * in the C locale decodes file names as ASCII, so {@code java año/H.java} failed with {@code invalid path for
 * source file: a??o/H.java} and anything a program printed outside ASCII came out as {@code ?}.
 */
class ProcessRunnerEnvTest {

    private static Map<String, String> inherited() {
        Map<String, String> env = new HashMap<>();
        env.put("PATH", "/usr/bin");
        env.put("LANG", "es_MX.UTF-8");
        env.put("HOME", "/home/josé");
        return env;
    }

    @Test
    void theParseStableEnvironmentForcesTheCLocale() {
        Map<String, String> env = ProcessRunner.childEnvironment(inherited(), false, Map.of());

        assertEquals("C", env.get("LC_ALL"), "output Editora parses must not vary with the user's locale");
        assertEquals(ProcessRunner.augmentedPath(), env.get("PATH"));
    }

    @Test
    void theUserEnvironmentLeavesTheLocaleExactlyAsInherited() {
        Map<String, String> env = ProcessRunner.childEnvironment(inherited(), true, Map.of());

        assertFalse(env.containsKey("LC_ALL"), "a user-facing child must not be forced into the C locale");
        assertEquals("es_MX.UTF-8", env.get("LANG"));
        assertEquals("/home/josé", env.get("HOME"));
        assertEquals(ProcessRunner.augmentedPath(), env.get("PATH"), "it still gets the augmented PATH");
    }

    @Test
    void aLocaleTheUserSetThemselvesSurvivesInTheUserEnvironment() {
        Map<String, String> inherited = inherited();
        inherited.put("LC_ALL", "fr_FR.UTF-8");

        assertEquals(
                "fr_FR.UTF-8",
                ProcessRunner.childEnvironment(inherited, true, Map.of()).get("LC_ALL"));
        // ...whereas the parse-stable environment overrides even an explicit choice.
        assertEquals(
                "C", ProcessRunner.childEnvironment(inherited, false, Map.of()).get("LC_ALL"));
    }

    @Test
    void aLaunchersOwnVariablesWinOverBoth() {
        Map<String, String> overrides =
                Map.of("PATH", "/opt/jdk/bin", "LC_ALL", "ja_JP.UTF-8", "JAVA_HOME", "/opt/jdk");

        for (boolean userLocale : new boolean[] {true, false}) {
            Map<String, String> env = ProcessRunner.childEnvironment(inherited(), userLocale, overrides);
            assertEquals("/opt/jdk/bin", env.get("PATH"), "a run configuration may replace PATH");
            assertEquals("ja_JP.UTF-8", env.get("LC_ALL"));
            assertEquals("/opt/jdk", env.get("JAVA_HOME"));
        }
    }

    @Test
    void theEnvironmentIsChangedInPlaceAndNullOverridesAreAccepted() {
        Map<String, String> inherited = inherited();

        assertSame(inherited, ProcessRunner.childEnvironment(inherited, true, null));
        assertSame(inherited, ProcessRunner.applyUserEnv(inherited, null));
    }

    @Test
    void anExistingPathKeyIsReusedWhateverItsCase() {
        // Windows spells it "Path"; adding a second "PATH" would leave the child with two.
        Map<String, String> windows = new HashMap<>(Map.of("Path", "C:\\Windows"));

        ProcessRunner.applyUserEnv(windows);

        assertEquals(ProcessRunner.augmentedPath(), windows.get("Path"));
        assertFalse(windows.containsKey("PATH"));
    }

    @Test
    void anOverrideWithANullValueRemovesTheVariable() {
        // Git's children must not inherit GIT_DIR and its relatives; an empty value would not do, git reads
        // GIT_DIR="" as a (bad) repository path.
        Map<String, String> inherited = inherited();
        inherited.put("GIT_DIR", "/elsewhere/.git");
        inherited.put("GIT_WORK_TREE", "/elsewhere");
        Map<String, String> overrides = new java.util.HashMap<>();
        overrides.put("GIT_DIR", null);
        overrides.put("GIT_WORK_TREE", null);
        overrides.put("GIT_NEVER_SET", null);
        overrides.put("GIT_PAGER", "cat");
        for (boolean userLocale : new boolean[] {true, false}) {
            Map<String, String> env =
                    ProcessRunner.childEnvironment(new java.util.HashMap<>(inherited), userLocale, overrides);
            org.junit.jupiter.api.Assertions.assertFalse(env.containsKey("GIT_DIR"));
            org.junit.jupiter.api.Assertions.assertFalse(env.containsKey("GIT_WORK_TREE"));
            org.junit.jupiter.api.Assertions.assertFalse(env.containsKey("GIT_NEVER_SET"));
            org.junit.jupiter.api.Assertions.assertEquals("cat", env.get("GIT_PAGER"));
        }
    }
}

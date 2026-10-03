package com.editora.process;

import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The secret-name heuristic and the scrubbed child environment the preview render CLIs run under. */
class SecretEnvTest {

    @Test
    void recognisesSecretLookingNames() {
        for (String name : List.of(
                "GITHUB_TOKEN",
                "GH_TOKEN",
                "NPM_TOKEN",
                "ANTHROPIC_API_KEY",
                "ANTHROPIC_BASE_URL",
                "OPENAI_API_KEY",
                "OPENAI_ORG_ID",
                "AWS_ACCESS_KEY_ID",
                "AWS_SECRET_ACCESS_KEY",
                "AWS_SESSION_TOKEN",
                "AWS_PROFILE",
                "STRIPE_SECRET",
                "CLIENT_SECRET_VALUE",
                "DB_PASSWORD",
                "PGPASSWORD",
                "MYSQL_ROOT_PASSWORD_FILE",
                "SSH_KEY",
                "GOOGLE_APPLICATION_CREDENTIALS",
                "AZURE_DEVOPS_PAT",
                "github_token",
                "Db_Password")) {
            assertTrue(SecretEnv.isSecretName(name), name);
        }
    }

    @Test
    void leavesWhatTheRenderToolsNeed() {
        for (String name : List.of(
                "PATH",
                "Path",
                "HOME",
                "USER",
                "LANG",
                "LC_ALL",
                "TMPDIR",
                "JAVA_HOME",
                "DISPLAY",
                "WAYLAND_DISPLAY",
                "XAUTHORITY",
                "XDG_RUNTIME_DIR",
                "SSH_AUTH_SOCK",
                "PUPPETEER_EXECUTABLE_PATH",
                "GRAPHVIZ_DOT",
                "PLANTUML_SECURITY_PROFILE",
                "KEYBOARD_LAYOUT",
                "TOKENIZERS_PARALLELISM",
                "")) {
            assertFalse(SecretEnv.isSecretName(name), name);
        }
        assertFalse(SecretEnv.isSecretName(null));
    }

    @Test
    void scrubRemovesOnlyTheSecretsInPlace() {
        Map<String, String> env = new LinkedHashMap<>();
        env.put("PATH", "/usr/bin");
        env.put("GITHUB_TOKEN", "ghp_x");
        env.put("HOME", "/home/u");
        env.put("ANTHROPIC_API_KEY", "sk-ant");
        env.put("db_password", "hunter2");
        SecretEnv.scrub(env);
        assertEquals(List.of("PATH", "HOME"), List.copyOf(env.keySet()));
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void aScrubbedChildInheritsNoSecretLookingVariableButKeepsItsExtraEnv(@TempDir Path dir) {
        // extraEnv is the caller's explicit choice, so it survives even when its name looks secret; everything
        // secret-looking the test JVM happens to carry (CI runners carry several *_TOKEN variables) does not.
        ProcessRunner.Result r = ProcessRunner.runScrubbed(
                dir, Duration.ofSeconds(10), List.of("env"), Map.of("EDITORA_PROBE_API_KEY", "explicit"));
        assertEquals(0, r.exit(), r.err());
        boolean sawExplicit = false;
        boolean sawPath = false;
        for (String line : r.out().split("\n")) {
            int eq = line.indexOf('=');
            if (eq <= 0) {
                continue; // a continuation line of a multi-line value
            }
            String name = line.substring(0, eq);
            if (name.equals("EDITORA_PROBE_API_KEY")) {
                sawExplicit = true;
                continue;
            }
            sawPath |= name.equals("PATH");
            if (System.getenv(name) != null) { // a real inherited variable, not a fragment of a value
                assertFalse(SecretEnv.isSecretName(name), "leaked into the render child: " + name);
            }
        }
        assertTrue(sawExplicit, "extraEnv is applied after the scrub");
        assertTrue(sawPath, "ordinary variables are still inherited");
    }
}

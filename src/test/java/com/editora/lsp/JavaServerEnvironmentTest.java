package com.editora.lsp;

import java.util.HashMap;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class JavaServerEnvironmentTest {
    @Test
    void enablesLifecycleOrderingWithoutReplacingExistingJvmOptions() {
        var env = new HashMap<String, String>();
        env.put("JDK_JAVA_OPTIONS", "-Xmx2g");
        JavaServerEnvironment.configure("java", List.of("jdtls"), env);
        assertEquals("-Xmx2g -Djava.lsp.joinOnCompletion=true", env.get("JDK_JAVA_OPTIONS"));
        JavaServerEnvironment.configure("java", List.of("jdtls"), env);
        assertEquals("-Xmx2g -Djava.lsp.joinOnCompletion=true", env.get("JDK_JAVA_OPTIONS"));
    }

    @Test
    void respectsCommandAndEnvironmentOverridesAndOtherServers() {
        var env = new HashMap<String, String>();
        JavaServerEnvironment.configure("java", List.of("jdtls", "--jvm-arg=-Djava.lsp.joinOnCompletion=false"), env);
        assertTrue(env.isEmpty());
        JavaServerEnvironment.configure("python", List.of("pylsp"), env);
        assertTrue(env.isEmpty());
        for (String key : List.of("JAVA_TOOL_OPTIONS", "_JAVA_OPTIONS", "JDK_JAVA_OPTIONS")) {
            env.clear();
            env.put(key, "-Djava.lsp.joinOnCompletion=false");
            var before = new HashMap<>(env);
            JavaServerEnvironment.configure("java", List.of("java", "-jar", "server.jar"), env);
            assertEquals(before, env);
        }
    }
}

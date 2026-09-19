package com.editora.lsp;

import java.util.List;
import java.util.Map;

/** Launch settings for ordered JDT document lifecycle/completion processing. */
final class JavaServerEnvironment {
    private static final String JOIN = "-Djava.lsp.joinOnCompletion";

    private JavaServerEnvironment() {}

    static void configure(String serverId, List<String> command, Map<String, String> environment) {
        if (!"java".equals(serverId)) return;
        // A command or inherited JVM option is an intentional override, including an explicit false.
        if (command.stream().anyMatch(arg -> arg.contains(JOIN))) return;
        for (String key : List.of("JDK_JAVA_OPTIONS", "JAVA_TOOL_OPTIONS", "_JAVA_OPTIONS")) {
            if (environment.getOrDefault(key, "").contains(JOIN)) return;
        }
        // Works with both the jdtls launcher and direct java commands. JDT joins pending lifecycle
        // jobs inside the server process; the editor never waits on the FX thread.
        String options = environment.getOrDefault("JDK_JAVA_OPTIONS", "");
        environment.put("JDK_JAVA_OPTIONS", options.isBlank() ? JOIN + "=true" : options + " " + JOIN + "=true");
    }
}

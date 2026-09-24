package com.editora.run;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/** Resolves a configured JDK home into the launcher and environment used by Java run/debug flows. */
public final class JdkToolchain {

    private JdkToolchain() {}

    /** A per-configuration JDK wins; blank means inherit the default JDK. */
    public static String effectiveHome(String configurationHome, String globalHome) {
        String local = clean(configurationHome);
        return local.isEmpty() ? clean(globalHome) : local;
    }

    /** The selected JDK's {@code java} executable, or blank to let the existing resolver choose. */
    public static String javaExecutable(String jdkHome) {
        String home = clean(jdkHome);
        if (home.isEmpty()) {
            return "";
        }
        Path bin = Path.of(home).resolve("bin");
        Path executable = bin.resolve(isWindows() ? "java.exe" : "java");
        return executable.toString();
    }

    /** The compiler alongside a selected Java launcher, or {@code javac} when PATH supplies Java. */
    public static String compilerForJavaExecutable(String javaExecutable) {
        if (javaExecutable == null || javaExecutable.isBlank()) {
            return "javac";
        }
        Path parent = Path.of(javaExecutable).getParent();
        return parent == null
                ? "javac"
                : parent.resolve(isWindows() ? "javac.exe" : "javac").toString();
    }

    /**
     * Environment for Maven and before-launch commands: {@code JAVA_HOME} plus that JDK first on
     * {@code PATH}. Empty when the system/default JDK is selected.
     */
    public static Map<String, String> environment(String jdkHome, String inheritedPath) {
        String home = clean(jdkHome);
        if (home.isEmpty()) {
            return Map.of();
        }
        LinkedHashMap<String, String> env = new LinkedHashMap<>();
        env.put("JAVA_HOME", home);
        String bin = Path.of(home).resolve("bin").toString();
        env.put(
                pathKey(),
                inheritedPath == null || inheritedPath.isBlank() ? bin : bin + File.pathSeparator + inheritedPath);
        return Map.copyOf(env);
    }

    /** True when the path still looks like a complete JDK (not merely a JRE/runtime image). */
    public static boolean isJdkHome(String jdkHome) {
        String home = clean(jdkHome);
        if (home.isEmpty()) {
            return false;
        }
        Path bin = Path.of(home).resolve("bin");
        return Files.isRegularFile(bin.resolve(isWindows() ? "javac.exe" : "javac"));
    }

    private static String clean(String value) {
        return value == null ? "" : value.strip();
    }

    private static String pathKey() {
        if (!isWindows()) {
            return "PATH";
        }
        for (String key : System.getenv().keySet()) {
            if ("PATH".equalsIgnoreCase(key)) {
                return key;
            }
        }
        return "Path";
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "")
                .toLowerCase(java.util.Locale.ROOT)
                .contains("win");
    }
}

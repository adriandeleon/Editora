package com.editora.run;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.jspecify.annotations.Nullable;

/**
 * Moves the JavaFX jars of a Java launch from the class path to the module path. A program whose main class
 * extends {@code javafx.application.Application} and finds JavaFX only on the class path stops before {@code
 * main} with "JavaFX runtime components are missing, and are required to run this application": the {@code
 * java} launcher looks for the {@code javafx.graphics} module in the boot layer, and Maven and Gradle (and
 * jdtls) hand a non-modular project its JavaFX jars as plain class path entries. Putting those jars on
 * {@code --module-path} and naming their modules in {@code --add-modules} is what {@code javafx:run} does, and
 * it launches the same program from Run and Debug. Pure.
 *
 * <p>A launch whose VM options already choose modules ({@code --module-path}, {@code -p}, {@code
 * --add-modules}, {@code --upgrade-module-path}) is left exactly as the user wrote it.
 */
public final class JavaFxModules {

    private JavaFxModules() {}

    /** The JavaFX modules a jar can hold (javafx-swt is not a module). */
    private static final Set<String> MODULES = Set.of("base", "controls", "fxml", "graphics", "media", "swing", "web");

    /**
     * A Maven or Gradle artifact ({@code javafx-controls-25.jar}, {@code javafx-controls-25-linux.jar}) or a
     * JavaFX SDK jar ({@code javafx.controls.jar}).
     */
    private static final Pattern JAR = Pattern.compile("javafx[-.]([a-z]+)(?:-(\\d+)[^/\\\\]*)?\\.jar");

    /** The modules that load native code, so JDK 24 and later warn about them unless native access is granted. */
    private static final Set<String> NATIVE = Set.of("javafx.graphics", "javafx.media", "javafx.web");

    /**
     * The first JavaFX release that needs JDK 22 or later: from it on, {@code --enable-native-access} is known to the
     * JDK it runs on, and JDK 24 warns without it ("A restricted method in java.lang.System has been called").
     */
    static final int NATIVE_ACCESS_FROM = 24;

    /**
     * A launch's paths with the JavaFX jars taken out.
     *
     * @param jars the JavaFX jars, in their original order ({@code []}: none, or the user chose modules)
     * @param modules the modules those jars name, sorted ({@code javafx.base, javafx.controls, …})
     * @param rest every other path, in its original order
     * @param nativeAccess the modules to grant native access ({@code --enable-native-access}): those of {@link
     *     #NATIVE} present, when every JavaFX jar names its version and it is {@link #NATIVE_ACCESS_FROM} or later
     */
    public record Split(List<String> jars, List<String> modules, List<String> rest, List<String> nativeAccess) {
        /** Whether there is anything to put on the module path. */
        public boolean any() {
            return !jars.isEmpty();
        }

        /** {@code --add-modules} with this split's modules, as one comma-separated value. */
        public String addModules() {
            return String.join(",", modules);
        }

        /** The {@code --enable-native-access=…} option for {@link #nativeAccess}, or null when there is none. */
        public @Nullable String enableNativeAccess() {
            return nativeAccess.isEmpty() ? null : "--enable-native-access=" + String.join(",", nativeAccess);
        }
    }

    /** {@code paths} split into its JavaFX jars and the rest; nothing moves when {@code vmArgs} choose modules. */
    public static Split split(@Nullable List<String> paths, @Nullable List<String> vmArgs) {
        List<String> all = paths == null ? List.of() : paths;
        if (choosesModules(vmArgs)) {
            return new Split(List.of(), List.of(), List.copyOf(all), List.of());
        }
        List<String> jars = new ArrayList<>();
        Set<String> modules = new TreeSet<>();
        List<String> rest = new ArrayList<>();
        boolean recentEnough = true;
        for (String path : all) {
            String module = moduleOf(path);
            if (module != null) {
                jars.add(path);
                modules.add(module);
                int version = versionOf(path);
                recentEnough &= version >= NATIVE_ACCESS_FROM;
            } else {
                rest.add(path);
            }
        }
        List<String> nativeAccess = !recentEnough || grantsNativeAccess(vmArgs)
                ? List.of()
                : modules.stream().filter(NATIVE::contains).toList();
        return new Split(List.copyOf(jars), List.copyOf(modules), List.copyOf(rest), nativeAccess);
    }

    /** As {@link #split(List, List)}, with the VM options as java-debug takes them: one string. */
    public static Split split(@Nullable List<String> paths, @Nullable String vmArgs) {
        return split(
                paths,
                vmArgs == null || vmArgs.isBlank()
                        ? List.of()
                        : List.of(vmArgs.strip().split("\\s+")));
    }

    /** The JavaFX module the jar at {@code path} holds ({@code javafx.controls}), or null for any other path. */
    public static @Nullable String moduleOf(@Nullable String path) {
        if (path == null) {
            return null;
        }
        int slash = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        String name = path.substring(slash + 1).toLowerCase(Locale.ROOT);
        Matcher m = JAR.matcher(name);
        return m.matches() && MODULES.contains(m.group(1)) ? "javafx." + m.group(1) : null;
    }

    /** The JavaFX major version a Maven or Gradle jar's name carries ({@code 25}), or 0 for none (an SDK jar). */
    static int versionOf(@Nullable String path) {
        if (path == null) {
            return 0;
        }
        int slash = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        Matcher m = JAR.matcher(path.substring(slash + 1).toLowerCase(Locale.ROOT));
        if (!m.matches() || m.group(2) == null) {
            return 0;
        }
        try {
            return Integer.parseInt(m.group(2));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** Whether the VM options already grant native access: theirs stands. */
    static boolean grantsNativeAccess(@Nullable List<String> vmArgs) {
        return vmArgs != null && vmArgs.stream().anyMatch(a -> a != null && a.startsWith("--enable-native-access"));
    }

    /** Whether the VM options already say where modules come from or which to add. */
    static boolean choosesModules(@Nullable List<String> vmArgs) {
        if (vmArgs == null) {
            return false;
        }
        for (String a : vmArgs) {
            if (a == null) {
                continue;
            }
            if (a.equals("-p")
                    || a.startsWith("--module-path")
                    || a.startsWith("--add-modules")
                    || a.startsWith("--upgrade-module-path")) {
                return true;
            }
        }
        return false;
    }
}

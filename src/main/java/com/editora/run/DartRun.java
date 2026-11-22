package com.editora.run;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Predicate;
import java.util.regex.Pattern;

import org.jspecify.annotations.Nullable;

/**
 * How Run File starts a Dart file: a plain Dart program with {@code dart run <file> args…}, in its package's folder
 * (the nearest one up with a {@code pubspec.yaml}) or else the file's own; a file of a Flutter app with {@code flutter
 * run -d <device> -t <file> --dart-entrypoint-args=…} in the app's folder, on this computer's desktop unless a device
 * is named. Debug File decides the same way ({@code dap/DartLaunch}). Pure: the folders are looked at through the
 * predicate it is given, and the pubspec is text read by the caller (off the FX thread).
 */
public final class DartRun {

    private DartRun() {}

    /** The file a Dart package is: its folder is the package's root. */
    public static final String PUBSPEC = "pubspec.yaml";

    /** A top-level {@code main} at the start of its line: {@code main(}, {@code void main(}, {@code Future<void> main(}. */
    private static final Pattern MAIN =
            Pattern.compile("^(?:(?:void|Future<void>|FutureOr<void>|dynamic)\\s+)?main\\s*\\(");

    /** The 0-based line of {@code text}'s top-level {@code main}, where Run File's ▶ sits; -1 when it has none. */
    public static int runLine(@Nullable String text) {
        if (text == null) {
            return -1;
        }
        String[] lines = text.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            if (MAIN.matcher(lines[i]).find()) {
                return i;
            }
        }
        return -1;
    }

    /** The nearest folder up from {@code file} holding a {@code pubspec.yaml} ({@code exists} says), or null. */
    public static @Nullable Path packageRoot(Path file, Predicate<Path> exists) {
        Path abs = file.toAbsolutePath();
        for (Path dir = abs.getParent(); dir != null; dir = dir.getParent()) {
            if (exists.test(dir.resolve(PUBSPEC))) {
                return dir;
            }
        }
        return null;
    }

    /**
     * Whether the package whose {@code pubspec.yaml} reads {@code pubspec} is a Flutter app: its {@code dependencies}
     * name Flutter's SDK ({@code flutter:} then {@code sdk: flutter}, or {@code flutter: {sdk: flutter}}). A
     * {@code flutter_test} among the dev dependencies alone does not make one.
     */
    public static boolean isFlutter(@Nullable String pubspec) {
        if (pubspec == null) {
            return false;
        }
        String[] lines = pubspec.split("\r?\n", -1);
        boolean inDependencies = false;
        for (int i = 0; i < lines.length; i++) {
            String line = withoutComment(lines[i]);
            if (line.isBlank()) {
                continue;
            }
            if (!Character.isWhitespace(line.charAt(0))) { // a top-level key: which section follows
                inDependencies = line.strip().equals("dependencies:");
                continue;
            }
            String entry = line.strip();
            if (!inDependencies || !entry.startsWith("flutter:")) {
                continue;
            }
            String inline = entry.substring("flutter:".length()).strip();
            if (!inline.isEmpty()) {
                return inline.replace(" ", "").equals("{sdk:flutter}");
            }
            for (int j = i + 1; j < lines.length; j++) { // its first nested line
                String next = withoutComment(lines[j]).strip();
                if (!next.isEmpty()) {
                    return next.replace(" ", "").equals("sdk:flutter");
                }
            }
        }
        return false;
    }

    private static String withoutComment(String line) {
        int hash = line.indexOf(" #");
        String out = line.stripLeading().startsWith("#") ? "" : hash >= 0 ? line.substring(0, hash) : line;
        return out.stripTrailing();
    }

    /** This computer's Flutter desktop device for operating system {@code osName}: {@code linux}, {@code macos} or {@code windows}. */
    public static String desktopDevice(@Nullable String osName) {
        String os = osName == null ? "" : osName.toLowerCase(Locale.ROOT);
        if (os.contains("mac") || os.contains("darwin")) {
            return "macos";
        }
        return os.contains("win") ? "windows" : "linux";
    }

    /** The device a Flutter app runs on: {@code setting} when it names one, else this computer's desktop. */
    public static String device(@Nullable String setting) {
        return setting == null || setting.isBlank()
                ? (com.editora.Os.isMac() ? "macos" : com.editora.Os.isWindows() ? "windows" : "linux")
                : setting.strip();
    }

    /** {@code dart run <file> args…}. */
    public static List<String> dartArgv(String dart, Path file, List<String> args) {
        List<String> argv = new ArrayList<>(List.of(dart, "run", file.toString()));
        argv.addAll(args);
        return argv;
    }

    /** {@code flutter run -d <device> -t <target> --dart-entrypoint-args=<arg>…}: one option per argument. */
    public static List<String> flutterArgv(String flutter, String device, String target, List<String> args) {
        List<String> argv = new ArrayList<>(List.of(flutter, "run", "-d", device, "-t", target));
        for (String a : args) {
            argv.add("--dart-entrypoint-args=" + a);
        }
        return argv;
    }

    /** {@code file} as {@code root} names it, with forward slashes ({@code lib/main.dart}); absolute outside it. */
    public static String relativeTarget(Path root, Path file) {
        Path abs = file.toAbsolutePath().normalize();
        Path base = root.toAbsolutePath().normalize();
        return abs.startsWith(base) ? base.relativize(abs).toString().replace('\\', '/') : abs.toString();
    }

    /**
     * How a Dart file is run.
     *
     * @param flutter whether it is a Flutter app's ({@code flutter run})
     * @param root its package's folder, or null outside any
     * @param cwd where it runs: the package's folder, else the file's
     * @param argv the command
     */
    public record Plan(boolean flutter, @Nullable Path root, Path cwd, List<String> argv) {}

    /**
     * The plan for {@code file} with {@code args}: in package {@code root} (null: none) whose {@code pubspec.yaml}
     * reads {@code pubspec}, by {@code dart} or — a Flutter app's file — {@code flutter} on {@code device}.
     */
    public static Plan plan(
            Path file,
            @Nullable Path root,
            @Nullable String pubspec,
            List<String> args,
            String dart,
            String flutter,
            String device) {
        Path abs = file.toAbsolutePath();
        Path folder = abs.getParent() == null ? abs : abs.getParent();
        if (root != null && isFlutter(pubspec)) {
            return new Plan(true, root, root, flutterArgv(flutter, device, relativeTarget(root, abs), args));
        }
        return new Plan(false, root, root != null ? root : folder, dartArgv(dart, abs, args));
    }
}

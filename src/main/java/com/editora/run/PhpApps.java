package com.editora.run;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;
import java.util.function.IntPredicate;
import java.util.function.Predicate;
import java.util.regex.Pattern;

import org.jspecify.annotations.Nullable;

/**
 * What Run File and Debug File do with a PHP file, as VS Code's PHP Debug configurations "Launch currently open
 * script" and "Launch Built-in web server" do. Pure apart from the disk and port lookups the caller supplies.
 *
 * <ul>
 *   <li>A Laravel app — its {@code artisan}, its {@code public/index.php}, or a class file of it — is served as
 *       {@code artisan serve} serves it: {@code php -S} in {@code public/} with Laravel's router script. Run
 *       directly rather than through {@code artisan serve}, whose server is a child process a debug session's Stop
 *       would leave running, and which is given none of the debugger's {@code -d} switches.</li>
 *   <li>Another web app — an {@code index.php}, or a class file under a folder with {@code public/index.php} — is
 *       served by PHP's built-in server on its document root ({@code php -S 127.0.0.1:8000 -t public}).</li>
 *   <li>A PHPUnit test file ({@code *Test.php} with a {@code TestCase} class) runs its tests under the project's
 *       PHPUnit ({@code vendor/bin/phpunit}, else {@code phpunit} on the PATH), in the folder of its
 *       {@code phpunit.xml} or {@code composer.json}; Debug Test at Caret narrows it to one test
 *       ({@link PhpUnitTests}).</li>
 *   <li>Anything else runs as a script, {@code php file}, in its folder.</li>
 * </ul>
 *
 * A server takes the first free port from {@link #FIRST_PORT}.
 */
public final class PhpApps {

    private PhpApps() {}

    /** What a PHP file is run as. */
    public enum Kind {
        /** {@code php file}. */
        SCRIPT,
        /** PHP's built-in web server on a document root. */
        SERVER,
        /** A Laravel app on PHP's built-in web server, with Laravel's router. */
        LARAVEL,
        /** PHPUnit on a test file. */
        TESTS
    }

    /** The port a server is tried on first ({@code artisan serve}'s and Symfony's default). */
    public static final int FIRST_PORT = 8000;

    /** How many ports from {@link #FIRST_PORT} are tried. */
    static final int PORTS_TRIED = 100;

    /** The host a server listens on: this machine alone. */
    public static final String HOST = "127.0.0.1";

    /** The folders a web app's {@code index.php} is served from, under the app's root. */
    static final List<String> DOCUMENT_ROOTS = List.of("public", "web", "htdocs", "public_html", "www");

    /** Laravel's router for the built-in server, as {@code artisan serve} runs it from the framework. */
    public static final String LARAVEL_ROUTER =
            "vendor/laravel/framework/src/Illuminate/Foundation/resources/server.php";

    /** The files that mark the folder PHPUnit runs in. */
    static final List<String> TEST_ROOT_FILES =
            List.of("phpunit.xml", "phpunit.xml.dist", "phpunit.dist.xml", "composer.json");

    /** {@code class X extends TestCase} (PHPUnit's, or an app's own {@code Tests\TestCase}). */
    private static final Pattern TEST_CLASS =
            Pattern.compile("\\bclass\\s+\\w+\\s+extends\\s+\\\\?(?:\\w+\\\\)*\\w*TestCase\\b");

    /**
     * How a PHP file is run.
     *
     * @param kind what it is run as
     * @param command the program and its arguments: {@code php …}, or a PHPUnit runner that is not a PHP script
     *     ({@code phpunit.bat}, a shell wrapper)
     * @param php {@code command}'s program is {@code php}: a debug launch adds Xdebug's switches after it
     * @param cwd the folder it runs in
     * @param url the address a server serves, or null
     * @param missing what is missing to run it ({@code vendor/…/server.php} before {@code composer install}), or null
     */
    public record Plan(
            Kind kind,
            List<String> command,
            boolean php,
            Path cwd,
            @Nullable String url,
            @Nullable String missing) {
        public Plan {
            command = List.copyOf(command);
        }
    }

    /** What {@link #plan} reads: whether a file exists, a file's first line, a program on the PATH, a free port. */
    public record Lookups(
            Predicate<Path> isFile,
            Function<Path, @Nullable String> firstLine,
            Function<String, @Nullable Path> onPath,
            IntPredicate portFree) {}

    /**
     * The plan for {@code file} (text {@code text}) with program arguments {@code args}; {@code filter} narrows a
     * test file to one test ({@link PhpUnitTests#filter}), else null. {@code top} bounds the walk up for a web app
     * (the open project, or null); {@code php} is the PHP to run ({@code php}, or the one Settings names).
     */
    public static Plan plan(
            Path file,
            String text,
            List<String> args,
            @Nullable String filter,
            @Nullable Path top,
            String php,
            Lookups look) {
        Path dir = parentOf(file);
        String name = file.getFileName() == null ? "" : file.getFileName().toString();
        if (isArtisan(name)) {
            return laravel(dir, php, look);
        }
        if (isTestFile(name, text)) {
            return tests(file, args, filter, php, look);
        }
        if (name.equalsIgnoreCase("index.php")) {
            Path parent = dir.getParent();
            if (parent != null
                    && DOCUMENT_ROOTS.contains(dir.getFileName().toString().toLowerCase(Locale.ROOT))
                    && look.isFile().test(parent.resolve("artisan"))) {
                return laravel(parent, php, look);
            }
            return server(dir, php, look);
        }
        if (!PhpRunLines.hasTopLevelCode(text)) { // a class file: the web app it is part of, if any
            for (Path d = dir; d != null; d = d.getParent()) {
                if (look.isFile().test(d.resolve("artisan")) && look.isFile().test(d.resolve("public/index.php"))) {
                    return laravel(d, php, look);
                }
                for (String root : DOCUMENT_ROOTS) {
                    if (look.isFile().test(d.resolve(root).resolve("index.php"))) {
                        return server(d.resolve(root), php, look);
                    }
                }
                if (d.equals(top)) {
                    break;
                }
            }
        }
        List<String> command = new ArrayList<>(List.of(php, file.toString()));
        command.addAll(args);
        return new Plan(Kind.SCRIPT, command, true, dir, null, null);
    }

    /** Whether {@code name} is Laravel's console script. */
    public static boolean isArtisan(String name) {
        return "artisan".equals(name);
    }

    /** Whether file {@code name} with text {@code text} is a PHPUnit test file. */
    public static boolean isTestFile(String name, String text) {
        return name.endsWith("Test.php") && TEST_CLASS.matcher(text).find();
    }

    private static Plan server(Path root, String php, Lookups look) {
        int port = port(look.portFree());
        String address = HOST + ":" + port;
        return new Plan(
                Kind.SERVER, List.of(php, "-S", address, "-t", root.toString()), true, root, "http://" + address, null);
    }

    private static Plan laravel(Path root, String php, Lookups look) {
        Path own = root.resolve("server.php"); // an app's own router (Laravel 10 and older)
        Path router = look.isFile().test(own) ? own : root.resolve(LARAVEL_ROUTER);
        int port = port(look.portFree());
        String address = HOST + ":" + port;
        return new Plan(
                Kind.LARAVEL,
                List.of(php, "-S", address, router.toString()),
                true,
                root.resolve("public"),
                "http://" + address,
                look.isFile().test(router) ? null : LARAVEL_ROUTER);
    }

    private static Plan tests(Path file, List<String> args, @Nullable String filter, String php, Lookups look) {
        Path root = testRoot(file, look.isFile());
        Path vendor = root.resolve("vendor/bin/phpunit");
        Path runner = look.isFile().test(vendor) ? vendor : look.onPath().apply("phpunit");
        List<String> rest = new ArrayList<>();
        if (filter != null) {
            rest.add("--filter");
            rest.add(filter);
        }
        rest.add(file.toString());
        rest.addAll(args);
        List<String> command = new ArrayList<>();
        boolean viaPhp = runner == null || isPhpScript(look.firstLine().apply(runner));
        if (viaPhp) {
            command.add(php);
        }
        command.add(runner == null ? "phpunit" : runner.toString());
        command.addAll(rest);
        return new Plan(Kind.TESTS, command, viaPhp, root, null, runner == null ? "phpunit" : null);
    }

    /** The folder PHPUnit runs a test file in: the nearest with a {@code phpunit.xml} or {@code composer.json}. */
    static Path testRoot(Path file, Predicate<Path> isFile) {
        Path dir = parentOf(file);
        for (Path d = dir; d != null; d = d.getParent()) {
            for (String marker : TEST_ROOT_FILES) {
                if (isFile.test(d.resolve(marker))) {
                    return d;
                }
            }
        }
        return dir;
    }

    /** Whether a runner whose first line is {@code firstLine} is a PHP script ({@code php runner} runs it). */
    static boolean isPhpScript(@Nullable String firstLine) {
        if (firstLine == null) {
            return true; // unread: vendor/bin/phpunit, Composer's PHP proxy
        }
        String line = firstLine.strip();
        return line.startsWith("<?php") || (line.startsWith("#!") && line.contains("php"));
    }

    /** The first port from {@link #FIRST_PORT} that {@code free} says is free, else {@link #FIRST_PORT}. */
    static int port(IntPredicate free) {
        for (int p = FIRST_PORT; p < FIRST_PORT + PORTS_TRIED; p++) {
            if (free.test(p)) {
                return p;
            }
        }
        return FIRST_PORT;
    }

    private static Path parentOf(Path file) {
        Path abs = file.toAbsolutePath();
        Path parent = abs.getParent();
        return parent == null ? abs : parent;
    }
}

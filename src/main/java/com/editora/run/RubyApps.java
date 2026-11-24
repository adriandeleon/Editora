package com.editora.run;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.IntPredicate;
import java.util.function.Predicate;
import java.util.regex.Pattern;

import org.jspecify.annotations.Nullable;

/**
 * What Run File and Debug File do with a Ruby file, as VS Code's rdbg configurations and RubyMine's run configurations
 * do. Pure apart from the disk and port lookups the caller supplies. Every command runs in the project's folder (the
 * nearest with a {@code Gemfile}), and under {@code bundle exec} when the bundle is installed ({@code Gemfile.lock}):
 *
 * <ul>
 *   <li>an RSpec file ({@code *_spec.rb}): {@code rspec spec/x_spec.rb}, or {@code :line} for the example or group
 *       there;</li>
 *   <li>a Minitest file ({@code *_test.rb}, {@code test_*.rb} with a test class): in a Rails app {@code bin/rails test
 *       file[:line]}, else {@code ruby -Itest file}, or {@code -n name} for one test;</li>
 *   <li>a Rails app's file under {@code app/} or {@code config/}, its {@code config.ru} or {@code bin/rails}: the app's
 *       development server, {@code bin/rails server} on the first free port from 3000;</li>
 *   <li>anything else: {@code ruby file}.</li>
 * </ul>
 *
 * Probed under rdbg 1.11 ({@code rdbg --command}) with Ruby 4.0, RSpec 3.13 and Rails 8.1: breakpoints stop in a spec,
 * in a controller under {@code bin/rails test file:line}, in a request to {@code bin/rails server}, and under {@code
 * bundle exec} with no {@code debug} gem in the bundle.
 */
public final class RubyApps {

    private RubyApps() {}

    /** What a Ruby file is run as. */
    public enum Kind {
        /** {@code ruby file}. */
        SCRIPT,
        /** RSpec on a spec file. */
        RSPEC,
        /** Minitest on a test file ({@code bin/rails test} in a Rails app). */
        MINITEST,
        /** A Rails app's development server. */
        RAILS_SERVER
    }

    /** The port a Rails server is tried on first. */
    public static final int FIRST_PORT = 3000;

    /** How many ports from {@link #FIRST_PORT} are tried. */
    static final int PORTS_TRIED = 100;

    /** The host a server listens on: this machine alone. */
    public static final String HOST = "127.0.0.1";

    /** A Minitest (or Test::Unit, Rails) test class. */
    private static final Pattern TEST_CLASS = Pattern.compile(
            "\\bclass\\s+\\w+\\s*<\\s*(?:[\\w:]*Test(?:Case)?\\b|Minitest::|Test::Unit::)|\\bMinitest::Test\\b");

    /**
     * How a Ruby file is run.
     *
     * @param kind what it is run as
     * @param command the program and its arguments ({@code bundle exec rspec spec/x_spec.rb:3}, {@code ruby bin/rails
     *     server …}): what rdbg runs after its {@code --}, or Run runs
     * @param cwd the folder it runs in
     * @param url the address a server serves, or null
     */
    public record Plan(
            Kind kind,
            List<String> command,
            Path cwd,
            @Nullable String url) {
        public Plan {
            command = List.copyOf(command);
        }
    }

    /** What {@link #plan} reads: whether a file exists, and whether a port is free. */
    public record Lookups(Predicate<Path> isFile, IntPredicate portFree) {}

    /**
     * The plan for {@code file} (text {@code text}) with program arguments {@code args}. For a test file, {@code
     * line} (0-based, or -1) narrows RSpec and Rails to the test there, and {@code test} (or null) names a plain
     * Minitest test. {@code top} bounds the walk up for the project (the open project, or null).
     */
    public static Plan plan(
            Path file,
            String text,
            List<String> args,
            int line,
            @Nullable String test,
            @Nullable Path top,
            Lookups look) {
        Path abs = file.toAbsolutePath();
        Path dir = abs.getParent() == null ? abs : abs.getParent();
        String name = abs.getFileName() == null ? "" : abs.getFileName().toString();
        Path root = projectRoot(dir, top, look.isFile());
        boolean bundled = root != null && look.isFile().test(root.resolve("Gemfile.lock"));
        Path app = root != null
                        && look.isFile().test(root.resolve("config/application.rb"))
                        && look.isFile().test(root.resolve("bin/rails"))
                ? root
                : null; // a Rails app's folder
        List<String> command = new ArrayList<>();
        if (isSpec(name)) {
            Path cwd = root != null ? root : specRoot(abs);
            if (bundled) {
                command.addAll(List.of("bundle", "exec"));
            }
            command.add("rspec");
            command.add(relative(cwd, abs) + (line >= 0 ? ":" + (line + 1) : ""));
            command.addAll(args);
            return new Plan(Kind.RSPEC, command, cwd, null);
        }
        if (isMinitest(name, text)) {
            if (app != null) {
                command.addAll(
                        List.of("ruby", "bin/rails", "test", relative(app, abs) + (line >= 0 ? ":" + (line + 1) : "")));
                command.addAll(args);
                return new Plan(Kind.MINITEST, command, app, null);
            }
            Path cwd = root != null ? root : testRoot(abs);
            if (bundled) {
                command.addAll(List.of("bundle", "exec"));
            }
            command.addAll(List.of("ruby", "-Itest", relative(cwd, abs)));
            if (test != null) {
                command.addAll(List.of("-n", test));
            }
            command.addAll(args);
            return new Plan(Kind.MINITEST, command, cwd, null);
        }
        if (app != null && servesRails(app, abs)) {
            int port = port(look.portFree());
            String address = HOST + ":" + port;
            command.addAll(List.of("ruby", "bin/rails", "server", "-p", String.valueOf(port), "-b", HOST));
            command.addAll(args);
            return new Plan(Kind.RAILS_SERVER, command, app, "http://" + address);
        }
        if (bundled) {
            command.addAll(List.of("bundle", "exec"));
        }
        command.addAll(List.of("ruby", abs.toString()));
        command.addAll(args);
        return new Plan(Kind.SCRIPT, command, dir, null);
    }

    /** Whether {@code name} is an RSpec file. */
    public static boolean isSpec(String name) {
        return name.endsWith("_spec.rb");
    }

    /** Whether file {@code name} with text {@code text} is a Minitest (or Test::Unit, Rails) test file. */
    public static boolean isMinitest(String name, String text) {
        boolean named = name.endsWith("_test.rb") || (name.startsWith("test_") && name.endsWith(".rb"));
        return named && TEST_CLASS.matcher(text).find();
    }

    /** Whether {@code file} of the Rails app in {@code root} serves it: under {@code app/} or {@code config/}, its rackup. */
    static boolean servesRails(Path root, Path file) {
        if (!file.startsWith(root)) {
            return false;
        }
        Path rel = root.relativize(file);
        String first = rel.getNameCount() > 0 ? rel.getName(0).toString() : "";
        return first.equals("app")
                || first.equals("config")
                || rel.toString().equals("config.ru")
                || rel.equals(Path.of("bin", "rails"));
    }

    /** The nearest folder from {@code dir} up (to {@code top}) with a {@code Gemfile}, or null. */
    static @Nullable Path projectRoot(Path dir, @Nullable Path top, Predicate<Path> isFile) {
        for (Path d = dir; d != null; d = d.getParent()) {
            if (isFile.test(d.resolve("Gemfile"))) {
                return d;
            }
            if (d.equals(top)) {
                break;
            }
        }
        return null;
    }

    /** Without a {@code Gemfile}: the folder above the {@code spec} folder a spec is in, else the spec's folder. */
    static Path specRoot(Path spec) {
        return above(spec, "spec");
    }

    /** Without a {@code Gemfile}: the folder above the {@code test} folder a test is in, else the test's folder. */
    static Path testRoot(Path test) {
        return above(test, "test");
    }

    private static Path above(Path file, String folder) {
        for (Path d = file.getParent(); d != null; d = d.getParent()) {
            if (d.getFileName() != null && d.getFileName().toString().equals(folder) && d.getParent() != null) {
                return d.getParent();
            }
        }
        Path parent = file.getParent();
        return parent == null ? file : parent;
    }

    /** {@code file} relative to {@code dir} with {@code /} separators (as RSpec and Rails print and read it). */
    private static String relative(Path dir, Path file) {
        return file.startsWith(dir) ? dir.relativize(file).toString().replace('\\', '/') : file.toString();
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
}

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
 *   <li>another app with a {@code config.ru} (Sinatra, Roda, a bare Rack app): its {@code config.ru}, a Ruby file at
 *       its top or under {@code app/} or {@code config/} runs its server, rackup ({@link #RACKUP}) on the first free
 *       port from 9292;</li>
 *   <li>anything else: {@code ruby file}.</li>
 * </ul>
 *
 * Probed under rdbg 1.11 ({@code rdbg --command}) with Ruby 4.0, RSpec 3.13 and Rails 8.1: breakpoints stop in a spec,
 * in a controller under {@code bin/rails test file:line}, in a request to {@code bin/rails server}, and under {@code
 * bundle exec} with no {@code debug} gem in the bundle.
 *
 * <p>{@code bin/rails} sets the bundle up itself, but under rdbg that is too late: rdbg's DAP server loads Ruby's
 * built-in {@code json} first, and a bundle that locks another {@code json} then refuses to start ("You have already
 * activated json 2.7.2, but your Gemfile requires json 3.2.0", system Ruby 3.3). {@code bundle exec} puts {@code
 * -rbundler/setup} ahead of what rdbg adds to {@code RUBYOPT}, so a Rails app runs under it like everything else.
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
        RAILS_SERVER,
        /** A Rack app's server ({@code rackup}): Sinatra, Roda, Hanami, a bare Rack app — a {@code config.ru}. */
        RACK_SERVER
    }

    /** The port a Rails server is tried on first. */
    public static final int FIRST_PORT = 3000;

    /**
     * {@code rackup}, run by {@code ruby} itself (rackup 2's {@code Rackup::Server}, else rack 2's {@code Rack::Server}),
     * with the options after {@code --}: under {@code bundle exec}, the {@code rackup} executable is loaded into Bundler's
     * own process, where rdbg's breakpoints never stopped (probed with rdbg 1.11, Ruby 4.0, rackup 2.3, Sinatra 4.2),
     * while {@code ruby} runs as a program of its own, as {@code ruby bin/rails} does. Nor does it need the gem's
     * executable on the {@code PATH}.
     */
    static final String RACKUP = "begin; require \"rackup\"; Rackup::Server.start;"
            + " rescue LoadError; require \"rack\"; Rack::Server.start; end";

    /** The port a Rack server is tried on first ({@code rackup}'s own default). */
    public static final int RACK_FIRST_PORT = 9292;

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
                if (bundled) {
                    command.addAll(List.of("bundle", "exec"));
                }
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
            if (bundled) {
                command.addAll(List.of("bundle", "exec"));
            }
            command.addAll(List.of("ruby", "bin/rails", "server", "-p", String.valueOf(port), "-b", HOST));
            command.addAll(args);
            return new Plan(Kind.RAILS_SERVER, command, app, "http://" + address);
        }
        if (app == null && root != null && look.isFile().test(root.resolve("config.ru")) && servesRack(root, abs)) {
            int port = port(look.portFree(), RACK_FIRST_PORT);
            if (bundled) {
                command.addAll(List.of("bundle", "exec"));
            }
            command.addAll(List.of("ruby", "-e", RACKUP, "--", "-p", String.valueOf(port), "-o", HOST));
            command.addAll(args);
            return new Plan(Kind.RACK_SERVER, command, root, "http://" + HOST + ":" + port);
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

    /**
     * Whether {@code file} of the Rack app in {@code root} (it has a {@code config.ru}) serves it: its {@code config.ru},
     * a Ruby file at its top ({@code app.rb}, as Sinatra apps are laid out), or one under {@code app/} or
     * {@code config/}. Its scripts, specs and tests elsewhere run as themselves.
     */
    static boolean servesRack(Path root, Path file) {
        if (!file.startsWith(root)) {
            return false;
        }
        Path rel = root.relativize(file);
        String first = rel.getNameCount() > 0 ? rel.getName(0).toString() : "";
        return rel.toString().equals("config.ru")
                || (rel.getNameCount() == 1 && first.endsWith(".rb"))
                || first.equals("app")
                || first.equals("config");
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
        return port(free, FIRST_PORT);
    }

    /** The first port from {@code first} that {@code free} says is free, else {@code first}. */
    static int port(IntPredicate free, int first) {
        for (int p = first; p < first + PORTS_TRIED; p++) {
            if (free.test(p)) {
                return p;
            }
        }
        return first;
    }
}

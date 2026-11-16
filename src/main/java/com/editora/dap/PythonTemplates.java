package com.editora.dap;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.regex.Pattern;

import org.jspecify.annotations.Nullable;

/**
 * Breakpoints in Django and Jinja templates (PyCharm's template debugging, VS Code's {@code "django"} and {@code
 * "jinja"}): debugpy's own {@code django} and {@code jinja} switches, put in a Python launch or attach request. With
 * them, a breakpoint on a template line stops there — the call stack shows the template ({@code Django Template},
 * {@code template}) and Variables its context ({@code name}, {@code items}, a loop's {@code i} and {@code forloop}).
 * Without them a Django template's breakpoint is never hit, and a Jinja one stops at the wrong place: the line of
 * that number in the Python Jinja compiled the template to (probed: {@code PythonTemplatesProbeFxTest}).
 *
 * <p>Settings' Template breakpoints ({@code debugPythonTemplates}) is {@link Mode#AUTO} by default: each switch is on
 * when the project uses its engine ({@link #detect}) — Django where there is a {@code manage.py} or the project's
 * requirements name it, Jinja where they name Jinja2 or Flask. {@link Mode#ON} turns both on, {@link Mode#OFF} neither.
 * A launch.json's own values are kept. Pure, but for the files {@link #detect} is given to read.
 */
public final class PythonTemplates {

    /** Settings' Template breakpoints. */
    public enum Mode {
        /** Each engine's switch on when the project uses it. */
        AUTO,
        /** Both on. */
        ON,
        /** Neither. */
        OFF;

        /** The mode a Settings value names; anything else is {@link #AUTO}. */
        public static Mode of(@Nullable String setting) {
            if (setting == null) {
                return AUTO;
            }
            return switch (setting.strip().toLowerCase(Locale.ROOT)) {
                case "on" -> ON;
                case "off" -> OFF;
                default -> AUTO;
            };
        }

        /** The Settings value. */
        public String setting() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /** What a project uses. */
    public record Found(boolean django, boolean jinja) {
        public static final Found NONE = new Found(false, false);
    }

    /** The files that name a Python project's requirements. */
    static final List<String> REQUIREMENTS = List.of(
            "requirements.txt",
            "requirements-dev.txt",
            "requirements/base.txt",
            "requirements/prod.txt",
            "pyproject.toml",
            "Pipfile",
            "setup.cfg",
            "setup.py",
            "environment.yml");

    /** How many folders up from the program's the project is looked for. */
    static final int LEVELS = 4;

    private static final Pattern DJANGO = Pattern.compile("(?i)(?<![\\w-])django(?![\\w-])");
    private static final Pattern JINJA = Pattern.compile("(?i)(?<![\\w-])(jinja2|flask|quart)(?![\\w-])");

    private PythonTemplates() {}

    /**
     * What the project at or above {@code start} (the launch's folder) uses: Django where a {@code manage.py} is or the
     * requirements name it, Jinja where they name Jinja2, Flask or Quart. {@code read} gives a file's text (null when
     * there is none), {@code exists} whether a file is there; folders up to {@link #LEVELS} above are looked in.
     */
    public static Found detect(@Nullable Path start, Function<Path, @Nullable String> read, Predicate<Path> exists) {
        boolean django = false;
        boolean jinja = false;
        Path dir = start;
        for (int level = 0; dir != null && level <= LEVELS; level++, dir = dir.getParent()) {
            if (exists.test(dir.resolve("manage.py"))) {
                django = true;
            }
            for (String name : REQUIREMENTS) {
                String text = read.apply(dir.resolve(name));
                if (text == null) {
                    continue;
                }
                django |= DJANGO.matcher(text).find();
                jinja |= JINJA.matcher(text).find();
            }
        }
        return new Found(django, jinja);
    }

    /**
     * {@code args}, a Python launch or attach request, with {@code django} and {@code jinja} put as {@code mode} says;
     * {@code found} tells what the project at a folder uses ({@link Mode#AUTO}). Another adapter's request is sent as
     * it is.
     */
    public static Map<String, Object> decorate(
            Map<String, Object> args, Mode mode, Function<@Nullable Path, Found> found) {
        if (args == null || !("python".equals(args.get("type")) || "debugpy".equals(args.get("type")))) {
            return args;
        }
        Found use = switch (mode) {
            case OFF -> Found.NONE;
            case ON -> new Found(true, true);
            case AUTO -> found.apply(folder(args));
        };
        if (!use.django() && !use.jinja()) {
            return args;
        }
        Map<String, Object> out = new LinkedHashMap<>(args);
        if (use.django()) {
            out.putIfAbsent("django", true);
        }
        if (use.jinja()) {
            out.putIfAbsent("jinja", true);
        }
        return out;
    }

    /** The folder a request runs in: its {@code cwd}, else its program's; null when it names neither. */
    static @Nullable Path folder(Map<String, Object> args) {
        try {
            if (args.get("cwd") instanceof String cwd && !cwd.isBlank()) {
                return Path.of(cwd);
            }
            if (args.get("program") instanceof String program && !program.isBlank()) {
                return Path.of(program).toAbsolutePath().getParent();
            }
        } catch (RuntimeException e) {
            return null; // not a path here (a remote host's)
        }
        return null;
    }
}

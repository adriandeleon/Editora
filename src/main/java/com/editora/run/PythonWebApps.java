package com.editora.run;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.jspecify.annotations.Nullable;

/**
 * Python web apps — a Django project's {@code manage.py}, a file that makes a Flask or FastAPI app — and the
 * development server that serves them, for Run File, Debug File and the configuration Add proposes. Pure apart from
 * the package lookup the caller supplies.
 *
 * <ul>
 *   <li>{@code manage.py} with no program arguments runs {@code manage.py runserver}: on its own it only prints
 *       its help. With arguments ({@code migrate}) it runs them, as before.</li>
 *   <li>A Flask app's file runs {@code python -m flask --app module:app run --reload}; a FastAPI app's
 *       {@code python -m uvicorn module:app --reload} — unless the file starts a server itself under
 *       {@code if __name__ == "__main__"}, which then runs as it is. On its own such a file defines the app and
 *       exits. The module is named from the first folder up that is not a package, where the server runs, so a
 *       package's relative imports work.</li>
 *   <li>Each of these servers reloads in a process of its own (Django's autoreloader, Werkzeug's, uvicorn's):
 *       the process that serves is a child. A debug launch of one ({@link #servesWeb}) follows subprocesses, so
 *       breakpoints in views stop, and keep stopping after each reload (probed with Django 6.1, Flask 3.1,
 *       uvicorn 0.54). Where children cannot be followed (a remote host) {@link #withoutReload} turns the reloader
 *       off instead.</li>
 * </ul>
 */
public final class PythonWebApps {

    private PythonWebApps() {}

    /** The framework an app is written in. */
    public enum Framework {
        DJANGO,
        FLASK,
        FASTAPI
    }

    /**
     * What serves an app file.
     *
     * @param framework the app's framework
     * @param module the module {@code python -m} runs ({@code flask}, {@code uvicorn}), or null to run the file
     *     itself ({@code manage.py})
     * @param args the arguments after the module or file
     * @param folder where the server runs: for a module, the folder its app module is named from
     */
    public record Server(Framework framework, @Nullable String module, List<String> args, Path folder) {
        public Server {
            args = List.copyOf(args);
        }
    }

    /** A module-level {@code app = Flask(…)} / {@code api: FastAPI = fastapi.FastAPI(…)}. */
    private static final Pattern APP = Pattern.compile(
            "(?m)^([A-Za-z_]\\w*)\\s*(?::\\s*[\\w.\\[\\], ]+)?=\\s*(?:[A-Za-z_]\\w*\\.)?(Flask|FastAPI)\\s*\\(");

    private static final Pattern MAIN_GUARD = Pattern.compile("(?m)^if\\s+__name__\\s*==\\s*(['\"])__main__\\1\\s*:");

    /** Modules {@code python -m} runs as a web server: what makes a launch {@link #servesWeb}. */
    private static final Set<String> SERVER_MODULES =
            Set.of("flask", "uvicorn", "fastapi", "hypercorn", "gunicorn", "django", "daphne", "granian");

    /** The framework of the app {@code fileName} with {@code content} is, or null when it is no web app. */
    public static @Nullable Framework framework(@Nullable String fileName, @Nullable String content) {
        if (content == null) {
            return null;
        }
        if ("manage.py".equals(fileName)) {
            return content.toLowerCase(Locale.ROOT).contains("django") ? Framework.DJANGO : null;
        }
        Matcher m = APP.matcher(content);
        if (!m.find()) {
            return null;
        }
        return "Flask".equals(m.group(2)) ? Framework.FLASK : Framework.FASTAPI;
    }

    /**
     * The server Run File and Debug File start for {@code file}, or null when the file runs as a script.
     *
     * @param file the file (in its own file system)
     * @param content its text
     * @param fileArgs its program arguments (Run → Program Arguments): a {@code manage.py} with some runs them; a
     *     Flask or FastAPI server gets them after its own
     * @param isPackage whether a folder is a package (holds {@code __init__.py}); read where the file is
     */
    public static @Nullable Server serve(
            Path file, @Nullable String content, List<String> fileArgs, Predicate<Path> isPackage) {
        Path name = file.getFileName();
        Path dir = file.getParent();
        Framework framework = framework(name == null ? null : name.toString(), content);
        if (framework == null || dir == null || content == null) {
            return null;
        }
        if (framework == Framework.DJANGO) {
            return fileArgs.isEmpty() ? new Server(framework, null, List.of("runserver"), dir) : null;
        }
        String stem = name.toString().replaceFirst("\\.pyw?$", "");
        if (MAIN_GUARD.matcher(content).find() || isTestFile(stem)) {
            return null; // it starts its own server (app.run(), uvicorn.run()); a test file's tests run
        }
        Matcher m = APP.matcher(content);
        if (!m.find()) {
            return null;
        }
        // A package's __init__.py is the package itself.
        StringBuilder module = new StringBuilder("__init__".equals(stem) ? "" : stem);
        Path folder = dir;
        while (folder.getParent() != null && folder.getFileName() != null && isPackage.test(folder)) {
            module.insert(0, folder.getFileName() + (module.isEmpty() ? "" : "."));
            folder = folder.getParent();
        }
        if (module.isEmpty()) {
            return null; // an __init__.py outside any package: nothing to name
        }
        String app = module + ":" + m.group(1);
        List<String> args = new ArrayList<>();
        if (framework == Framework.FLASK) {
            args.addAll(List.of("--app", app, "run", "--reload"));
            args.addAll(fileArgs);
            return new Server(framework, "flask", args, folder);
        }
        args.addAll(List.of(app, "--reload"));
        args.addAll(fileArgs);
        return new Server(framework, "uvicorn", args, folder);
    }

    /** A pytest or unittest file's name ({@code test_x}, {@code x_test}, {@code conftest}), without {@code .py}. */
    private static boolean isTestFile(String stem) {
        return stem.startsWith("test_") || stem.endsWith("_test") || stem.equals("conftest");
    }

    /**
     * {@code server} (found with no program arguments) once the file's program arguments are known: a Flask or
     * FastAPI server with them after its own, null for a {@code manage.py} given some (it runs them).
     */
    public static @Nullable Server withArgs(@Nullable Server server, List<String> fileArgs) {
        if (server == null || fileArgs.isEmpty()) {
            return server;
        }
        if (server.module() == null) {
            return null;
        }
        List<String> args = new ArrayList<>(server.args());
        args.addAll(fileArgs);
        return new Server(server.framework(), server.module(), args, server.folder());
    }

    /**
     * Whether a launch serves a web app — {@code python -m} a server module, {@code manage.py} — so its debug
     * launch follows subprocesses: the reloader's child is the process that serves.
     *
     * @param module the module {@code python -m} runs, or null
     * @param program the script that runs, or null
     */
    public static boolean servesWeb(@Nullable String module, @Nullable String program) {
        if (module != null && !module.isBlank()) {
            String top = module.strip();
            int dot = top.indexOf('.');
            return SERVER_MODULES.contains(dot < 0 ? top : top.substring(0, dot));
        }
        if (program == null) {
            return false;
        }
        String p = program.replace('\\', '/');
        String base = p.substring(p.lastIndexOf('/') + 1);
        return base.equals("manage.py") || base.equals("django-admin");
    }

    /**
     * {@code args} with the server's reloader turned off, for a debug launch that cannot follow its child: Django's
     * {@code runserver --noreload}, Flask's {@code run --no-reload}, uvicorn's without {@code --reload}. Any other
     * launch's arguments as they are.
     */
    public static List<String> withoutReload(@Nullable String module, @Nullable String program, List<String> args) {
        List<String> out = new ArrayList<>(args);
        String m = module == null ? "" : module.strip();
        if (m.isEmpty() && servesWeb(null, program)) {
            if (out.contains("runserver") && !out.contains("--noreload")) {
                out.add(out.indexOf("runserver") + 1, "--noreload");
            }
            return out;
        }
        switch (m) {
            case "flask" -> {
                out.remove("--reload");
                if (out.contains("run") && !out.contains("--no-reload")) {
                    out.add(out.indexOf("run") + 1, "--no-reload");
                }
            }
            case "uvicorn", "hypercorn" -> out.removeIf(a -> a.equals("--reload")); // --reload-dir alone: a warning
            default -> {}
        }
        return out;
    }
}

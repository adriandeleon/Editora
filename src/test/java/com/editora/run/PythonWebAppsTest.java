package com.editora.run;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import com.editora.config.RunConfiguration;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PythonWebAppsTest {

    private static final Path ROOT = Path.of("/work/site");

    private static final String FLASK = """
            from flask import Flask

            app = Flask(__name__)


            @app.route("/")
            def index():
                return "hi"
            """;

    private static final String FASTAPI = """
            import fastapi

            api: fastapi.FastAPI = fastapi.FastAPI(title="shop")


            @api.get("/")
            def index():
                return {}
            """;

    private static final String MANAGE = """
            import os
            import sys

            if __name__ == "__main__":
                os.environ.setdefault("DJANGO_SETTINGS_MODULE", "site.settings")
                from django.core.management import execute_from_command_line
                execute_from_command_line(sys.argv)
            """;

    private static PythonWebApps.Server serve(Path file, String content, List<String> args, Set<Path> packages) {
        return PythonWebApps.serve(file, content, args, packages::contains);
    }

    @Test
    void recognisesEachFrameworksApp() {
        assertEquals(PythonWebApps.Framework.DJANGO, PythonWebApps.framework("manage.py", MANAGE));
        assertEquals(PythonWebApps.Framework.FLASK, PythonWebApps.framework("app.py", FLASK));
        assertEquals(PythonWebApps.Framework.FASTAPI, PythonWebApps.framework("main.py", FASTAPI));
        assertNull(PythonWebApps.framework("manage.py", "print('a manage.py of something else')\n"));
        assertNull(PythonWebApps.framework("tool.py", "print('hi')\n"));
        assertNull(PythonWebApps.framework("app.py", "    app = Flask(__name__)  # inside a factory\n"));
        assertNull(PythonWebApps.framework("app.py", null));
    }

    @Test
    void manageWithoutArgumentsRunsTheServerAndWithSomeRunsThem() {
        Path manage = ROOT.resolve("manage.py");
        PythonWebApps.Server server = serve(manage, MANAGE, List.of(), Set.of());
        assertNotNull(server);
        assertNull(server.module(), "the file itself runs");
        assertEquals(List.of("runserver"), server.args());
        assertEquals(ROOT, server.folder());
        assertNull(serve(manage, MANAGE, List.of("migrate"), Set.of()), "its own arguments, as before");
    }

    @Test
    void aFlaskAppRunsUnderFlaskWithTheReloader() {
        PythonWebApps.Server server = serve(ROOT.resolve("app.py"), FLASK, List.of("--port", "5001"), Set.of());
        assertNotNull(server);
        assertEquals("flask", server.module());
        assertEquals(List.of("--app", "app:app", "run", "--reload", "--port", "5001"), server.args());
        assertEquals(ROOT, server.folder());
    }

    @Test
    void aFastApiAppInAPackageIsNamedFromTheFolderAboveIt() {
        Path pkg = ROOT.resolve("shop");
        PythonWebApps.Server server = serve(pkg.resolve("main.py"), FASTAPI, List.of(), Set.of(pkg));
        assertNotNull(server);
        assertEquals("uvicorn", server.module());
        assertEquals(List.of("shop.main:api", "--reload"), server.args(), "its variable, its dotted module");
        assertEquals(ROOT, server.folder(), "where `shop` imports, relative imports and all");
    }

    @Test
    void anAppMadeInAPackagesInitIsThePackageAndATestFileStaysATest() {
        Path pkg = ROOT.resolve("shop");
        PythonWebApps.Server server = serve(pkg.resolve("__init__.py"), FLASK, List.of(), Set.of(pkg));
        assertNotNull(server);
        assertEquals(List.of("--app", "shop:app", "run", "--reload"), server.args());
        assertEquals(ROOT, server.folder());
        assertNull(serve(ROOT.resolve("__init__.py"), FLASK, List.of(), Set.of()), "no package to name");
        assertNull(serve(ROOT.resolve("test_api.py"), FASTAPI, List.of(), Set.of()), "its tests run");
        assertNull(serve(ROOT.resolve("conftest.py"), FASTAPI, List.of(), Set.of()));
    }

    @Test
    void aFileThatStartsItsOwnServerRunsAsItIs() {
        String own = FLASK + "\n\nif __name__ == '__main__':\n    app.run(debug=True)\n";
        assertNull(serve(ROOT.resolve("app.py"), own, List.of(), Set.of()));
        assertEquals(PythonWebApps.Framework.FLASK, PythonWebApps.framework("app.py", own), "still a web app");
    }

    @Test
    void argumentsKnownLaterJoinAModuleServerAndCancelManageRunserver() {
        PythonWebApps.Server flask = serve(ROOT.resolve("app.py"), FLASK, List.of(), Set.of());
        assertEquals(
                List.of("--app", "app:app", "run", "--reload", "-h", "0.0.0.0"),
                PythonWebApps.withArgs(flask, List.of("-h", "0.0.0.0")).args());
        PythonWebApps.Server manage = serve(ROOT.resolve("manage.py"), MANAGE, List.of(), Set.of());
        assertNull(PythonWebApps.withArgs(manage, List.of("migrate")));
        assertEquals(manage, PythonWebApps.withArgs(manage, List.of()));
        assertNull(PythonWebApps.withArgs(null, List.of("x")));
    }

    @Test
    void serversAreRecognisedByTheirModuleOrManagePy() {
        assertTrue(PythonWebApps.servesWeb("flask", null));
        assertTrue(PythonWebApps.servesWeb("uvicorn", null));
        assertTrue(PythonWebApps.servesWeb("gunicorn.app.wsgiapp", null));
        assertTrue(PythonWebApps.servesWeb(null, "/work/site/manage.py"));
        assertTrue(PythonWebApps.servesWeb(null, "C:\\work\\site\\manage.py"));
        assertFalse(PythonWebApps.servesWeb("pytest", null));
        assertFalse(PythonWebApps.servesWeb(null, "/work/site/tool.py"));
        assertFalse(PythonWebApps.servesWeb(null, null));
    }

    @Test
    void withoutReloadTurnsEachReloaderOff() {
        assertEquals(
                List.of("runserver", "--noreload", "8001"),
                PythonWebApps.withoutReload(null, "/s/manage.py", List.of("runserver", "8001")));
        assertEquals(
                List.of("runserver", "--noreload"),
                PythonWebApps.withoutReload(null, "/s/manage.py", List.of("runserver", "--noreload")));
        assertEquals(
                List.of("--app", "app:app", "run", "--no-reload"),
                PythonWebApps.withoutReload("flask", null, List.of("--app", "app:app", "run", "--reload")));
        assertEquals(
                List.of("main:app", "--port", "9"),
                PythonWebApps.withoutReload("uvicorn", null, List.of("main:app", "--reload", "--port", "9")));
        assertEquals(List.of("-k", "x"), PythonWebApps.withoutReload("pytest", null, List.of("-k", "x")));
    }

    @Test
    void addProposesTheServerAsAConfiguration() {
        Path pkg = ROOT.resolve("shop");
        RunConfiguration fastapi = RunConfigDefaults.forWebApp(
                serve(pkg.resolve("main.py"), FASTAPI, List.of(), Set.of(pkg)), pkg.resolve("main.py"), ROOT);
        assertEquals("main.py (FastAPI)", fastapi.name());
        assertEquals(ScriptRunCommand.PYTHON_MODULE, fastapi.type());
        assertEquals("uvicorn", fastapi.target());
        assertEquals("shop.main:api --reload", fastapi.args());
        assertEquals("", fastapi.workingDir(), "the project root");

        Path manage = ROOT.resolve("web/manage.py");
        RunConfiguration django = RunConfigDefaults.forWebApp(serve(manage, MANAGE, List.of(), Set.of()), manage, ROOT);
        assertEquals("manage.py (Django)", django.name());
        assertEquals(ScriptRunCommand.PYTHON, django.type());
        assertEquals(Path.of("web/manage.py").toString(), django.target());
        assertEquals("runserver", django.args());
    }
}

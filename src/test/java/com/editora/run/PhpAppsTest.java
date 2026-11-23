package com.editora.run;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PhpAppsTest {

    private static final Path ROOT = Path.of("/work/shop").toAbsolutePath();

    /** Lookups over a fixed set of files, a PATH with {@code onPath}, and ports free from {@code firstFree}. */
    private static PhpApps.Lookups look(Set<Path> files, Path onPath, String firstLine, int firstFree) {
        return new PhpApps.Lookups(
                files::contains, p -> firstLine, tool -> "phpunit".equals(tool) ? onPath : null, p -> p >= firstFree);
    }

    private static PhpApps.Lookups look(Set<Path> files) {
        return look(files, null, null, PhpApps.FIRST_PORT);
    }

    private static PhpApps.Plan plan(Path file, String text, Set<Path> files) {
        return PhpApps.plan(file, text, List.of(), null, ROOT, "php", look(files));
    }

    @Test
    void aScriptRunsAsPhpFileInItsFolder() {
        Path file = ROOT.resolve("bin/job.php");
        PhpApps.Plan plan = PhpApps.plan(file, "<?php\necho 1;\n", List.of("a"), null, ROOT, "php", look(Set.of()));
        assertEquals(PhpApps.Kind.SCRIPT, plan.kind());
        assertEquals(List.of("php", file.toString(), "a"), plan.command());
        assertTrue(plan.php());
        assertEquals(ROOT.resolve("bin"), plan.cwd());
        assertNull(plan.url());
    }

    @Test
    void anIndexIsServedFromItsFolderOnTheFirstFreePort() {
        Path index = ROOT.resolve("public/index.php");
        PhpApps.Plan plan =
                PhpApps.plan(index, "<?php echo 1;", List.of(), null, ROOT, "php", look(Set.of(), null, null, 8002));
        assertEquals(PhpApps.Kind.SERVER, plan.kind());
        assertEquals(
                List.of(
                        "php",
                        "-S",
                        "127.0.0.1:8002",
                        "-t",
                        ROOT.resolve("public").toString()),
                plan.command());
        assertEquals("http://127.0.0.1:8002", plan.url());
        assertEquals(ROOT.resolve("public"), plan.cwd());
    }

    @Test
    void aLaravelAppIsServedThroughItsRouterFromArtisanItsIndexOrAClass() {
        Path router = ROOT.resolve(PhpApps.LARAVEL_ROUTER);
        Set<Path> files = Set.of(ROOT.resolve("artisan"), ROOT.resolve("public/index.php"), router);
        List<String> command = List.of("php", "-S", "127.0.0.1:8000", router.toString());
        for (Path file : List.of(ROOT.resolve("artisan"), ROOT.resolve("public/index.php"))) {
            PhpApps.Plan plan = plan(file, "<?php\n", files);
            assertEquals(PhpApps.Kind.LARAVEL, plan.kind(), file.toString());
            assertEquals(command, plan.command());
            assertEquals(ROOT.resolve("public"), plan.cwd());
            assertNull(plan.missing());
        }
        PhpApps.Plan fromClass = plan(
                ROOT.resolve("app/Http/Controllers/HomeController.php"),
                "<?php\nnamespace App\\Http\\Controllers;\n\nclass HomeController extends Controller {}\n",
                files);
        assertEquals(PhpApps.Kind.LARAVEL, fromClass.kind());
        assertEquals(command, fromClass.command());
    }

    @Test
    void anAppsOwnServerPhpIsItsRouterAndAMissingVendorIsSaid() {
        Path own = ROOT.resolve("server.php");
        PhpApps.Plan old = plan(ROOT.resolve("artisan"), "", Set.of(own, ROOT.resolve("artisan")));
        assertEquals(own.toString(), old.command().getLast());
        assertNull(old.missing());
        PhpApps.Plan fresh = plan(ROOT.resolve("artisan"), "", Set.of(ROOT.resolve("artisan")));
        assertEquals(PhpApps.LARAVEL_ROUTER, fresh.missing(), "composer install not run yet");
    }

    @Test
    void aClassFileOfAnotherWebAppServesItsDocumentRootAndAloneRunsAsAScript() {
        Path cls = ROOT.resolve("src/Cart.php");
        String text = "<?php\nnamespace Shop;\n\nfinal class Cart {}\n";
        PhpApps.Plan served = plan(cls, text, Set.of(ROOT.resolve("public/index.php")));
        assertEquals(PhpApps.Kind.SERVER, served.kind());
        assertEquals(ROOT.resolve("public").toString(), served.command().getLast());
        assertEquals(PhpApps.Kind.SCRIPT, plan(cls, text, Set.of()).kind(), "no web app around it");
    }

    @Test
    void aTestFileRunsTheProjectsPhpunitOnItNarrowedByAFilter() {
        Path test = ROOT.resolve("tests/Unit/CartTest.php");
        String text = "<?php\nuse PHPUnit\\Framework\\TestCase;\nfinal class CartTest extends TestCase {}\n";
        Path vendor = ROOT.resolve("vendor/bin/phpunit");
        Set<Path> files = Set.of(ROOT.resolve("phpunit.xml"), vendor);
        PhpApps.Plan plan = PhpApps.plan(test, text, List.of(), "/::testAdds(?: .*)?$/", ROOT, "php", look(files));
        assertEquals(PhpApps.Kind.TESTS, plan.kind());
        assertEquals(
                List.of("php", vendor.toString(), "--filter", "/::testAdds(?: .*)?$/", test.toString()),
                plan.command());
        assertEquals(ROOT, plan.cwd(), "where phpunit.xml is");
        assertTrue(plan.php());
    }

    @Test
    void aPhpunitOnThePathIsRunByPhpWhenItIsAScriptAndDirectlyWhenItIsAWrapper() {
        Path test = ROOT.resolve("tests/CartTest.php");
        String text = "<?php\nclass CartTest extends \\PHPUnit\\Framework\\TestCase {}\n";
        Path global = Path.of("/usr/bin/phpunit").toAbsolutePath();
        Set<Path> files = Set.of(ROOT.resolve("composer.json"));
        PhpApps.Plan script =
                PhpApps.plan(test, text, List.of(), null, ROOT, "php", look(files, global, "#!/usr/bin/php", 8000));
        assertEquals(List.of("php", global.toString(), test.toString()), script.command());
        PhpApps.Plan wrapper =
                PhpApps.plan(test, text, List.of(), null, ROOT, "php", look(files, global, "#!/bin/sh", 8000));
        assertEquals(List.of(global.toString(), test.toString()), wrapper.command());
        assertFalse(wrapper.php(), "Xdebug reaches its PHP by the environment alone");
        PhpApps.Plan none = PhpApps.plan(test, text, List.of(), null, ROOT, "php", look(files));
        assertEquals("phpunit", none.missing());
    }

    @Test
    void testFilesAreNamedTestAndExtendATestCase() {
        assertTrue(PhpApps.isTestFile("CartTest.php", "class CartTest extends TestCase {"));
        assertTrue(PhpApps.isTestFile("HomeTest.php", "class HomeTest extends \\Tests\\TestCase {"));
        assertFalse(PhpApps.isTestFile("Cart.php", "class Cart extends TestCase {"));
        assertFalse(PhpApps.isTestFile("CartTest.php", "class CartTest {"));
    }

    @Test
    void theFirstFreePortFromEightThousandElseEightThousand() {
        assertEquals(8000, PhpApps.port(p -> true));
        assertEquals(8003, PhpApps.port(p -> p > 8002));
        assertEquals(8000, PhpApps.port(p -> false));
    }

    @Test
    void phpScriptsAreToldFromWrappers() {
        assertTrue(PhpApps.isPhpScript("#!/usr/bin/env php"));
        assertTrue(PhpApps.isPhpScript("<?php"));
        assertTrue(PhpApps.isPhpScript(null), "unread: Composer's proxy");
        assertFalse(PhpApps.isPhpScript("#!/bin/sh"));
        assertFalse(PhpApps.isPhpScript("@ECHO OFF"));
    }
}

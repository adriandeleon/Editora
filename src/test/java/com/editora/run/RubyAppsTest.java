package com.editora.run;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RubyAppsTest {

    private static final Path ROOT = Path.of("/work/shop").toAbsolutePath();

    private static final String MINITEST = "class CalcTest < Minitest::Test\n  def test_adds; end\nend\n";

    private static RubyApps.Plan plan(Path file, String text, int line, String test, Set<Path> files) {
        return RubyApps.plan(
                file, text, List.of(), line, test, ROOT, new RubyApps.Lookups(files::contains, p -> p >= 3001));
    }

    private static Set<Path> rails() {
        return Set.of(
                ROOT.resolve("Gemfile"),
                ROOT.resolve("Gemfile.lock"),
                ROOT.resolve("config/application.rb"),
                ROOT.resolve("bin/rails"));
    }

    @Test
    void aSpecRunsUnderRspecFromTheProjectNarrowedToTheCaretsLine() {
        Path spec = ROOT.resolve("spec/models/cart_spec.rb");
        RubyApps.Plan plan = plan(spec, "", 6, null, Set.of(ROOT.resolve("Gemfile"), ROOT.resolve("Gemfile.lock")));
        assertEquals(RubyApps.Kind.RSPEC, plan.kind());
        assertEquals(List.of("bundle", "exec", "rspec", "spec/models/cart_spec.rb:7"), plan.command());
        assertEquals(ROOT, plan.cwd());
        RubyApps.Plan whole = plan(spec, "", -1, null, Set.of());
        assertEquals(List.of("rspec", "spec/models/cart_spec.rb"), whole.command(), "no bundle: rspec itself");
        assertEquals(ROOT, whole.cwd(), "above its spec folder");
    }

    @Test
    void aRailsTestRunsUnderBinRailsTestByItsLine() {
        Path test = ROOT.resolve("test/controllers/hello_controller_test.rb");
        String text = "class HelloControllerTest < ActionDispatch::IntegrationTest\nend\n";
        RubyApps.Plan plan = plan(test, text, 8, "test_x", rails());
        assertEquals(RubyApps.Kind.MINITEST, plan.kind());
        assertEquals(
                List.of("ruby", "bin/rails", "test", "test/controllers/hello_controller_test.rb:9"), plan.command());
        assertEquals(ROOT, plan.cwd());
    }

    @Test
    void aPlainMinitestFileRunsWithItsTestFolderAndANamedTest() {
        Path test = ROOT.resolve("test/calc_test.rb");
        RubyApps.Plan plan = plan(test, MINITEST, 2, "test_adds", Set.of());
        assertEquals(List.of("ruby", "-Itest", "test/calc_test.rb", "-n", "test_adds"), plan.command());
        assertEquals(ROOT, plan.cwd());
        RubyApps.Plan bundled =
                plan(test, MINITEST, -1, null, Set.of(ROOT.resolve("Gemfile"), ROOT.resolve("Gemfile.lock")));
        assertEquals(List.of("bundle", "exec", "ruby", "-Itest", "test/calc_test.rb"), bundled.command());
    }

    @Test
    void aRailsAppsFilesServeTheAppOnTheFirstFreePort() {
        for (String file :
                List.of("app/controllers/hello_controller.rb", "config/routes.rb", "config.ru", "bin/rails")) {
            RubyApps.Plan plan = plan(ROOT.resolve(file), "class X\nend\n", -1, null, rails());
            assertEquals(RubyApps.Kind.RAILS_SERVER, plan.kind(), file);
            assertEquals(List.of("ruby", "bin/rails", "server", "-p", "3001", "-b", "127.0.0.1"), plan.command());
            assertEquals(ROOT, plan.cwd());
            assertEquals("http://127.0.0.1:3001", plan.url());
        }
        assertEquals(
                RubyApps.Kind.SCRIPT,
                plan(ROOT.resolve("lib/tasks/seed.rb"), "puts 1", -1, null, rails())
                        .kind());
    }

    @Test
    void anythingElseRunsAsAScriptUnderTheBundleWhenThereIsOne() {
        Path script = ROOT.resolve("bin/job.rb");
        RubyApps.Plan plan = RubyApps.plan(
                script,
                "puts 1",
                List.of("a"),
                -1,
                null,
                ROOT,
                new RubyApps.Lookups(
                        Set.of(ROOT.resolve("Gemfile"), ROOT.resolve("Gemfile.lock"))::contains, p -> true));
        assertEquals(List.of("bundle", "exec", "ruby", script.toString(), "a"), plan.command());
        assertEquals(ROOT.resolve("bin"), plan.cwd());
        assertNull(plan.url());
        RubyApps.Plan notInstalled = plan(script, "puts 1", -1, null, Set.of(ROOT.resolve("Gemfile")));
        assertEquals(List.of("ruby", script.toString()), notInstalled.command(), "bundle install not run");
    }

    @Test
    void specAndTestFilesAreToldByNameAndClass() {
        assertTrue(RubyApps.isSpec("cart_spec.rb"));
        assertFalse(RubyApps.isSpec("spec_helper.rb"));
        assertTrue(RubyApps.isMinitest("calc_test.rb", MINITEST));
        assertTrue(RubyApps.isMinitest("test_calc.rb", "class TestCalc < Test::Unit::TestCase\nend"));
        assertTrue(RubyApps.isMinitest("cart_test.rb", "class CartTest < ActiveSupport::TestCase\nend"));
        assertFalse(RubyApps.isMinitest("test_helper.rb", "require 'minitest'"), "no test class");
        assertFalse(RubyApps.isMinitest("calc.rb", MINITEST));
    }
}

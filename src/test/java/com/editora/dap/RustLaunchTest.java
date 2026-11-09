package com.editora.dap;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** What CodeLLDB is asked to debug for a Rust file, and which executable Cargo built for it. */
class RustLaunchTest {

    private static final String LIB = """
            //! A library.
            pub fn add(a: i32, b: i32) -> i32 {
                a + b // a { brace in a comment
            }

            #[cfg(test)]
            mod tests {
                use super::*;

                /// Adds.
                #[test]
                fn adds() {
                    let s = "}{";
                    assert_eq!(add(1, 2), 3);
                }

                #[test]
                #[should_panic]
                fn panics() { panic!("no") }

                mod nested {
                    #[tokio::test]
                    async fn awaits() {
                        let c = '{';
                    }
                }

                fn helper() {}
            }
            """;

    @Test
    void theTestAtTheCaretIsItsPathInTheFile() {
        assertEquals("tests::adds", RustLaunch.testAt(LIB, 10), "on its attribute");
        assertEquals("tests::adds", RustLaunch.testAt(LIB, 11), "on its declaration");
        assertEquals("tests::adds", RustLaunch.testAt(LIB, 13), "in its body, past a brace in a string");
        assertEquals("tests::adds", RustLaunch.testAt(LIB, 14), "on its closing brace");
        assertEquals("tests::panics", RustLaunch.testAt(LIB, 18), "a one-line test under two attributes");
        assertEquals("tests::nested::awaits", RustLaunch.testAt(LIB, 23), "an async runtime's test, nested");
        assertNull(RustLaunch.testAt(LIB, 2), "in a plain function");
        assertNull(RustLaunch.testAt(LIB, 15), "between tests");
        assertNull(RustLaunch.testAt(LIB, 27), "in a helper of the test module");
        assertNull(RustLaunch.testAt(LIB, 99));
        assertNull(RustLaunch.testAt(LIB, -1));
        assertNull(RustLaunch.testAt(null, 0));
        assertTrue(RustLaunch.hasTests(LIB));
        assertFalse(RustLaunch.hasTests("fn main() {}\n"));
    }

    @Test
    void aFilesModulePathFollowsItsPlaceInTheCrate() {
        assertEquals("", RustLaunch.modulePath(Path.of("src/lib.rs")));
        assertEquals("", RustLaunch.modulePath(Path.of("src/main.rs")));
        assertEquals("", RustLaunch.modulePath(Path.of("src/bin/tool.rs")));
        assertEquals("net", RustLaunch.modulePath(Path.of("src/net.rs")));
        assertEquals("net", RustLaunch.modulePath(Path.of("src/net/mod.rs")));
        assertEquals("net::http", RustLaunch.modulePath(Path.of("src/net/http.rs")));
        assertEquals("", RustLaunch.modulePath(Path.of("tests/it.rs")));
        assertEquals("", RustLaunch.modulePath(Path.of("build.rs")));
    }

    @Test
    void thePlanIsTheTestAtTheCaretElseTheProgramElseTheFilesTests(@TempDir Path root) {
        Path net = root.resolve("src/net/http.rs");
        RustLaunch.Plan test = RustLaunch.plan(root, net, LIB, 13, false);
        assertEquals(RustLaunch.Mode.TEST, test.mode());
        assertEquals(List.of("test", "--no-run"), test.buildArgs());
        assertEquals(List.of("net::http::tests::adds", "--exact"), test.testArgs());
        assertEquals("adds", test.sessionName());
        assertEquals(List.of("test", "--no-run", "--message-format=json"), test.jsonArgs());

        RustLaunch.Plan program = RustLaunch.plan(root, root.resolve("src/main.rs"), LIB, 2, true);
        assertEquals(RustLaunch.Mode.PROGRAM, program.mode());
        assertEquals(List.of("build"), program.buildArgs());
        assertEquals(List.of(), program.testArgs());
        assertEquals("main.rs", program.sessionName());

        RustLaunch.Plan fileTests = RustLaunch.plan(root, net, LIB, 2, false);
        assertEquals(RustLaunch.Mode.TEST, fileTests.mode(), "a library has no program: the file's tests");
        assertEquals(List.of("net::http::"), fileTests.testArgs(), "every test of the module, not exactly");

        RustLaunch.Plan integration = RustLaunch.plan(root, root.resolve("tests/it.rs"), "fn x() {}\n", 0, true);
        assertEquals(List.of("test", "--no-run", "--test", "it"), integration.buildArgs());
        assertEquals(List.of(), integration.testArgs(), "all of the integration test's tests");
        RustLaunch.Plan oneIntegration = RustLaunch.plan(root, root.resolve("tests/it.rs"), LIB, 13, true);
        assertEquals(List.of("tests::adds", "--exact"), oneIntegration.testArgs(), "its own crate: no module prefix");

        RustLaunch.Plan example = RustLaunch.plan(root, root.resolve("examples/demo.rs"), "fn main() {}\n", 0, true);
        assertEquals(List.of("build", "--example", "demo"), example.buildArgs());
    }

    private static final String MESSAGES = """
            {"reason":"compiler-artifact","package_id":"shelf","target":{"kind":["lib"],"name":"shelf","src_path":"/p/src/lib.rs"},"profile":{"test":false},"executable":null}
            {"reason":"compiler-artifact","target":{"kind":["bin"],"name":"shelf","src_path":"/p/src/main.rs"},"profile":{"test":false},"executable":"/p/target/debug/shelf"}
            {"reason":"compiler-artifact","target":{"kind":["bin"],"name":"tool","src_path":"/p/src/bin/tool.rs"},"profile":{"test":false},"executable":"/p/target/debug/tool"}
            {"reason":"compiler-artifact","target":{"kind":["lib"],"name":"shelf","src_path":"/p/src/lib.rs"},"profile":{"test":true},"executable":"/p/target/debug/deps/shelf-1a2b"}
            {"reason":"compiler-artifact","target":{"kind":["test"],"name":"it","src_path":"/p/tests/it.rs"},"profile":{"test":true},"executable":"/p/target/debug/deps/it-3c4d"}
            {"reason":"compiler-message","message":{"rendered":"warning"}}
            not json
            {"reason":"build-finished","success":true}
            """;

    @Test
    void cargosMessagesSayWhereEachExecutableWent() {
        List<RustLaunch.Artifact> built = RustLaunch.artifacts(MESSAGES);
        assertEquals(4, built.size(), "a library itself is no executable; other messages are skipped");
        assertEquals(Path.of("/p/target/debug/shelf"), built.getFirst().executable());
        assertEquals(List.of("bin"), built.getFirst().kinds());

        assertEquals(
                Path.of("/p/target/debug/shelf"),
                RustLaunch.pick(built, Path.of("/p/src/net.rs"), RustLaunch.Mode.PROGRAM)
                        .executable(),
                "the program of src/main.rs");
        assertEquals(
                Path.of("/p/target/debug/tool"),
                RustLaunch.pick(built, Path.of("/p/src/bin/tool.rs"), RustLaunch.Mode.PROGRAM)
                        .executable(),
                "a binary's own file");
        assertEquals(
                Path.of("/p/target/debug/deps/shelf-1a2b"),
                RustLaunch.pick(built, Path.of("/p/src/net.rs"), RustLaunch.Mode.TEST)
                        .executable(),
                "a module's tests are the library's");
        assertEquals(
                Path.of("/p/target/debug/deps/it-3c4d"),
                RustLaunch.pick(built, Path.of("/p/tests/it.rs"), RustLaunch.Mode.TEST)
                        .executable());
        assertNull(RustLaunch.pick(List.of(), Path.of("/p/src/main.rs"), RustLaunch.Mode.PROGRAM));
        assertNull(
                RustLaunch.pick(built.subList(0, 2), Path.of("/q/x.rs"), RustLaunch.Mode.TEST), "no test binary built");

        List<RustLaunch.Artifact> twoBins = List.of(
                new RustLaunch.Artifact("a", List.of("bin"), Path.of("/p/src/bin/a.rs"), Path.of("/a"), false),
                new RustLaunch.Artifact("b", List.of("bin"), Path.of("/p/src/bin/b.rs"), Path.of("/b"), false));
        assertNull(RustLaunch.pick(twoBins, Path.of("/p/src/lib.rs"), RustLaunch.Mode.PROGRAM), "which one is unsaid");
        List<RustLaunch.Artifact> binTests =
                List.of(new RustLaunch.Artifact("a", List.of("bin"), Path.of("/p/src/main.rs"), Path.of("/t"), true));
        assertEquals(
                Path.of("/t"),
                RustLaunch.pick(binTests, Path.of("/p/src/x.rs"), RustLaunch.Mode.TEST)
                        .executable());
    }

    @Test
    void theLaunchRequestIsCodelldbs() {
        Map<String, Object> launch = RustLaunch.launch(
                Path.of("/p/target/debug/shelf"), List.of("-v"), Path.of("/p"), Map.of(), false, false);
        assertEquals("lldb", launch.get("type"));
        assertEquals(Path.of("/p/target/debug/shelf").toString(), launch.get("program"));
        assertEquals(List.of("rust"), launch.get("sourceLanguages"), "Rust's formatters and panic stop");
        assertEquals("console", launch.get("terminal"));
        assertEquals(Path.of("/p").toString(), launch.get("cwd"));
        assertEquals(
                "integrated",
                RustLaunch.launch(Path.of("/x"), List.of(), Path.of("/p"), Map.of(), false, true)
                        .get("terminal"),
                "the program in a terminal Editora starts, with its input");
    }

    @Test
    void theNearestCargoTomlIsThePackage(@TempDir Path dir) throws Exception {
        Path pkg = Files.createDirectories(dir.resolve("ws/crates/shelf/src"));
        Files.writeString(dir.resolve("ws/Cargo.toml"), "[workspace]\n");
        Files.writeString(dir.resolve("ws/crates/shelf/Cargo.toml"), "[package]\nname = \"shelf\"\n");
        Path main = Files.writeString(pkg.resolve("main.rs"), "fn main() {}\n");
        assertEquals(dir.resolve("ws/crates/shelf").toAbsolutePath(), RustLaunch.packageRoot(main));
        assertNull(RustLaunch.packageRoot(Files.writeString(dir.resolve("loose.rs"), "fn main() {}\n")));
    }
}

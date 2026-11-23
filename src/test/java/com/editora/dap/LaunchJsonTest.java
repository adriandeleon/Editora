package com.editora.dap;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A launch.json and tasks.json read as VS Code reads them. */
class LaunchJsonTest {

    private static final String LAUNCH = """
            {
              // comments, as VS Code allows
              "version": "0.2.0", /* a block, too */
              "configurations": [
                {
                  "name": "App",
                  "type": "lldb",
                  "request": "launch",
                  "program": "${workspaceFolder}/build/${fileBasenameNoExtension}",
                  "args": ["--url", "http://example.com/a,b", "${input:level}"],
                  "port": 9229,
                  "ratio": 1.5,
                  "stopOnEntry": false,
                  "linux": { "program": "${workspaceFolder}/build/app-linux" },
                  "windows": { "program": "${workspaceFolder}\\\\build\\\\app.exe" },
                },
                "not an object",
              ],
              "inputs": [
                { "id": "level", "type": "pickString", "description": "Level", "options": ["low", {"label": "High", "value": "high"}], "default": "low" },
                { "id": "name", "type": "promptString", "default": "you" },
                "not an object",
              ],
            }
            """;

    @Test
    void commentsAndTrailingCommasAreReadAsVsCodeReadsThem() throws Exception {
        LaunchJson.File f = LaunchJson.parse(LAUNCH);
        assertEquals(1, f.configurations().size());
        LaunchJson.Configuration c = f.configurations().getFirst();
        assertEquals("App", c.name());
        assertEquals("lldb", c.type());
        assertEquals("launch", c.request());
        assertEquals(9229L, c.body().get("port"), "a whole number is a long");
        assertEquals(1.5, c.body().get("ratio"));
        assertEquals(false, c.body().get("stopOnEntry"));
        assertEquals("http://example.com/a,b", ((List<?>) c.body().get("args")).get(1), "commas in strings stay");
        assertEquals(List.of("low", "high"), f.inputs().get("level").options(), "an option's value");
        assertEquals("you", f.inputs().get("name").defaultValue());
        assertEquals(2, f.inputs().size());
        assertThrows(LaunchJson.LaunchJsonException.class, () -> LaunchJson.parse("[1, 2]"));
        assertThrows(LaunchJson.LaunchJsonException.class, () -> LaunchJson.parse("{ broken"));
        assertEquals(List.of(), LaunchJson.parse("{}").configurations());
        assertEquals("{\"a\": [1, 2  ]}", LaunchJson.withoutCommentsAndTrailingCommas("{\"a\": [1, 2, /* x */]}"));
        assertEquals(
                "{\"a\": \"//not a comment\"}",
                LaunchJson.withoutCommentsAndTrailingCommas("{\"a\": \"//not a comment\"}"));
        assertEquals("{\"a\": 1 \n}", LaunchJson.withoutCommentsAndTrailingCommas("{\"a\": 1, // last\n}"));
    }

    @Test
    void eachPlatformsPartIsLaidOverTheConfiguration() throws Exception {
        Map<String, Object> body =
                LaunchJson.parse(LAUNCH).configurations().getFirst().body();
        Map<String, Object> linux = LaunchJson.forPlatform(body, "linux");
        assertEquals("${workspaceFolder}/build/app-linux", linux.get("program"));
        assertTrue(!linux.containsKey("linux") && !linux.containsKey("windows"));
        assertEquals(body.get("program"), LaunchJson.forPlatform(body, "osx").get("program"), "no osx part");
        assertEquals("linux", LaunchJson.osKey(com.editora.Os.parse("Linux")));
        assertEquals("osx", LaunchJson.osKey(com.editora.Os.parse("Mac OS X")));
        assertEquals("windows", LaunchJson.osKey(com.editora.Os.parse("Windows 11")));
    }

    @Test
    void vsCodesVariablesArePutIn(@TempDir Path ws) throws Exception {
        Path file = Files.createDirectories(ws.resolve("src")).resolve("main.c");
        Map<String, String> answers = new HashMap<>();
        int[] asked = {0};
        LaunchJson.Context ctx =
                new LaunchJson.Context(ws, file, 12, "sel", Map.of("HOME_DIR", "/h"), Path.of("/home/u"), in -> {
                    asked[0]++;
                    return in.id().equals("level") ? "high" : null;
                });
        Map<String, LaunchJson.Input> inputs = Map.of(
                "level", new LaunchJson.Input("level", "pickString", "", "", List.of("low", "high")),
                "name", new LaunchJson.Input("name", "promptString", "", "", List.of()));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("a", "${workspaceFolder}|${workspaceFolderBasename}|${file}|${fileBasename}");
        body.put("b", "${fileBasenameNoExtension}|${fileExtname}|${fileDirname}|${fileDirnameBasename}");
        body.put(
                "c",
                "${relativeFile}|${relativeFileDirname}|${lineNumber}|${selectedText}|${env:HOME_DIR}|${env:NONE}");
        body.put("d", List.of("${userHome}", "${input:level}", "${input:level}", 7, true));
        body.put("e", Map.of("x", "${cwd}${pathSeparator}${fileWorkspaceFolder}"));
        @SuppressWarnings("unchecked")
        Map<String, Object> out = (Map<String, Object>) LaunchJson.substitute(body, ctx, inputs, answers);
        String sep = java.io.File.separator;
        assertEquals(ws + "|" + ws.getFileName() + "|" + file + "|main.c", out.get("a"));
        assertEquals("main|.c|" + file.getParent() + "|src", out.get("b"));
        assertEquals("src" + sep + "main.c|src|12|sel|/h|", out.get("c"));
        assertEquals(List.of(Path.of("/home/u").toString(), "high", "high", 7, true), out.get("d"));
        assertEquals(1, asked[0], "an input is asked once a launch");
        assertEquals(Map.of("x", ws + sep + ws), out.get("e"));

        assertThrows(LaunchJson.Cancelled.class, () -> LaunchJson.substitute("${input:name}", ctx, inputs, answers));
        assertThrows(
                LaunchJson.LaunchJsonException.class,
                () -> LaunchJson.substitute("${input:other}", ctx, inputs, answers),
                "an input launch.json does not have");
        assertThrows(
                LaunchJson.LaunchJsonException.class,
                () -> LaunchJson.substitute("${command:pickProcess}", ctx, inputs, answers));
        LaunchJson.Context noFile = new LaunchJson.Context(ws, null, 1, "", Map.of(), ws, in -> "x");
        assertThrows(
                LaunchJson.LaunchJsonException.class, () -> LaunchJson.substitute("${file}", noFile, inputs, answers));
        assertEquals("plain", LaunchJson.substitute("plain", noFile, inputs, answers));
        assertEquals(
                "client_port=${port}",
                LaunchJson.substitute("client_port=${port}", noFile, inputs, answers),
                "vscode-php-debug's own variable is left for it");
        assertNull(LaunchJson.substitute(null, noFile, inputs, answers));
        assertEquals(
                List.of("level", "name"),
                LaunchJson.inputsUsed(Map.of("a", List.of("${input:level}", "${input:name}${input:level}"))));
    }

    @Test
    void theLaunchJsonIsTheProjectsElseTheNearestAboveTheFile(@TempDir Path dir) throws Exception {
        Path project = Files.createDirectories(dir.resolve("p"));
        Path nested = Files.createDirectories(project.resolve("sub/.vscode"));
        Path file = Files.createDirectories(project.resolve("sub/src")).resolve("a.c");
        assertNull(LaunchJson.find(project, file));
        Path sub = Files.writeString(nested.resolve("launch.json"), "{}");
        assertEquals(sub, LaunchJson.find(project, file), "the nearest above the file");
        Files.createDirectories(project.resolve(".vscode"));
        Path own = Files.writeString(project.resolve(".vscode/launch.json"), "{}");
        assertEquals(own, LaunchJson.find(project, file), "the project's first");
        assertEquals(sub, LaunchJson.find(null, file));
        assertNull(LaunchJson.find(null, null));
    }

    @Test
    void aShellTasksCommandAndArgumentsWithSpacesAreQuoted() throws Exception {
        LaunchJson.File tasks = LaunchJson.parse("""
                { "tasks": [
                  { "label": "tool", "type": "shell", "command": "/opt/my tools/run", "args": ["a b", "x"] },
                  { "label": "win", "type": "shell", "command": "C:\\\\Program Files\\\\t.exe", "args": ["my file.txt"] },
                  { "label": "line", "type": "shell", "command": "npm run build && echo done" },
                  { "label": "home", "type": "shell", "command": "$HOME/bin/x", "args": ["y"] }
                ] }
                """);
        assertEquals(
                List.of("sh", "-c", "'/opt/my tools/run' 'a b' x"),
                LaunchJson.taskArgv(LaunchJson.task(tasks, "tool"), false));
        assertEquals(
                List.of("cmd", "/d", "/s", "/c", "\"\"C:\\Program Files\\t.exe\" \"my file.txt\"\""),
                LaunchJson.taskArgv(LaunchJson.task(tasks, "win"), true));
        assertEquals(
                List.of("sh", "-c", "npm run build && echo done"),
                LaunchJson.taskArgv(LaunchJson.task(tasks, "line"), false),
                "a command alone is a whole command line");
        assertEquals(
                List.of("cmd", "/d", "/s", "/c", "\"npm run build && echo done\""),
                LaunchJson.taskArgv(LaunchJson.task(tasks, "line"), true));
        assertEquals(
                List.of("sh", "-c", "$HOME/bin/x y"),
                LaunchJson.taskArgv(LaunchJson.task(tasks, "home"), false),
                "a command without spaces still expands");
        assertEquals(
                List.of("cmd", "/d", "/s", "/c", "\"\"C:\\My Tools\\gcc.exe\" -o \"out dir\\a.exe\"\""),
                LaunchJson.taskArgv(
                        Map.of(
                                "type",
                                "shell",
                                "command",
                                "C:\\My Tools\\gcc.exe",
                                "args",
                                List.of("-o", "out dir\\a.exe")),
                        true),
                "a quoted command and a quoted last argument keep their quotes: cmd /s drops only the outer pair");
        assertEquals("plain", LaunchJson.cmdQuote("plain"));
        assertEquals("\"\"", LaunchJson.cmdQuote(""));
        assertEquals("\"a&b\"", LaunchJson.cmdQuote("a&b"));
        assertEquals("\"say \\\"hi\\\"\"", LaunchJson.cmdQuote("say \"hi\""));
    }

    @Test
    void aTasksPlainCommandIsItsCommandLine() throws Exception {
        LaunchJson.File tasks = LaunchJson.parse("""
                { "tasks": [
                  { "label": "build", "type": "shell", "command": "make", "args": ["all", "it's", {"value": "a b"}] },
                  { "label": "run", "type": "process", "command": "./run", "args": ["x"] },
                  { "taskName": "old", "command": "true" },
                  { "label": "cargo", "type": "cargo", "command": "build" },
                  { "label": "both", "dependsOn": ["build", "run"], "command": "true" },
                  { "label": "empty", "type": "shell" }
                ] }
                """);
        assertEquals(
                List.of("sh", "-c", "make all 'it'\"'\"'s' 'a b'"),
                LaunchJson.taskArgv(LaunchJson.task(tasks, "build"), false));
        assertEquals(
                List.of("cmd", "/d", "/s", "/c", "\"make all it's \"a b\"\""),
                LaunchJson.taskArgv(LaunchJson.task(tasks, "build"), true));
        assertEquals(List.of("./run", "x"), LaunchJson.taskArgv(LaunchJson.task(tasks, "run"), false));
        assertEquals(List.of("true"), LaunchJson.taskArgv(LaunchJson.task(tasks, "old"), false), "process by default");
        assertNull(LaunchJson.taskArgv(LaunchJson.task(tasks, "cargo"), false), "an extension's task type");
        assertNull(LaunchJson.taskArgv(LaunchJson.task(tasks, "both"), false), "dependsOn");
        assertNull(LaunchJson.taskArgv(LaunchJson.task(tasks, "empty"), false), "no command");
        assertNull(LaunchJson.task(tasks, "missing"));
        assertEquals("plain-word_1.0", LaunchJson.shellQuote("plain-word_1.0"));
        assertEquals("''", LaunchJson.shellQuote(""));
        assertEquals(
                List.of("it's", "a b", "plain"),
                com.editora.run.ProgramArgs.parse(String.join(
                        " ",
                        LaunchJson.shellQuote("it's"),
                        LaunchJson.shellQuote("a b"),
                        LaunchJson.shellQuote("plain"))),
                "quoted as Editora's argument parser reads them back");
    }

    @Test
    void theSamplesLaunchJsonsAreRead() throws Exception {
        Path cpp = Path.of("samples/debug/cpp/.vscode");
        org.junit.jupiter.api.Assumptions.assumeTrue(Files.isDirectory(cpp), "run from the source tree");
        LaunchJson.File c = LaunchJson.parse(Files.readString(cpp.resolve("launch.json")));
        assertEquals(
                List.of("lldb", "gdb", "cppdbg"),
                c.configurations().stream().map(LaunchJson.Configuration::type).toList());
        LaunchJson.File tasks = LaunchJson.parse(Files.readString(cpp.resolve("tasks.json")));
        assertEquals(
                "sh",
                LaunchJson.taskArgv(LaunchJson.task(tasks, "build"), false).getFirst());
        LaunchJson.File rust = LaunchJson.parse(Files.readString(Path.of("samples/debug/rust/.vscode/launch.json")));
        assertEquals(2, rust.configurations().size());
        assertTrue(rust.configurations().getFirst().body().containsKey("cargo"));
        LaunchJson.File cs = LaunchJson.parse(Files.readString(Path.of("samples/debug/csharp/.vscode/launch.json")));
        assertEquals(
                LaunchRoute.Kind.NETCOREDBG,
                LaunchRoute.of(cs.configurations().getFirst().body(), Map.of()).kind());
        LaunchJson.File csTasks =
                LaunchJson.parse(Files.readString(Path.of("samples/debug/csharp/.vscode/tasks.json")));
        assertEquals(
                "dotnet",
                LaunchJson.taskArgv(LaunchJson.task(csTasks, "build"), false).getFirst());
        LaunchJson.File rb = LaunchJson.parse(Files.readString(Path.of("samples/debug/ruby/.vscode/launch.json")));
        assertEquals(
                List.of(LaunchRoute.Kind.RDBG, LaunchRoute.Kind.RDBG),
                rb.configurations().stream()
                        .map(conf -> LaunchRoute.of(conf.body(), Map.of()).kind())
                        .toList());
    }

    @Test
    void compoundsAreReadWithTheirConfigurationsInOrder() throws Exception {
        LaunchJson.File f = LaunchJson.parse("""
                {
                  "configurations": [
                    { "name": "Server", "type": "debugpy", "request": "launch" },
                    { "name": "Client", "type": "node", "request": "launch" },
                  ],
                  "compounds": [
                    { "name": "Both", "configurations": ["Server", { "name": "Client", "folder": "web" }],
                      "stopAll": true, "preLaunchTask": "build" },
                    { "name": "Bare", "configurations": ["Client"] },
                    "not an object",
                    { "configurations": "not a list" },
                  ],
                }
                """);
        assertEquals(
                List.of(
                        new LaunchJson.Compound("Both", List.of("Server", "Client"), true, "build"),
                        new LaunchJson.Compound("Bare", List.of("Client"), false, ""),
                        new LaunchJson.Compound("", List.of(), false, "")),
                f.compounds());
        assertEquals("node", f.configuration("Client").type());
        assertNull(f.configuration("Gone"));
        assertTrue(LaunchJson.parse("{}").compounds().isEmpty(), "none: no compounds");
    }

    @Test
    void entriesOfTheWrongShapeAreSkipped() throws Exception {
        LaunchJson.File f = LaunchJson.parse("""
                {
                  "configurations": [1, {"name": 2, "type": "go"}],
                  "inputs": [3, {"id": "pick", "type": ["x"], "options": ["a", {"value": "b"}, {"label": "c"}, [1]]}],
                  "tasks": ["t", {"label": "build"}],
                  "compounds": [4, {"name": "both", "configurations": ["a", {"name": "b"}, {"folder": "c"}, [1]],
                                    "stopAll": {}, "preLaunchTask": ["x"]},
                                {"name": "two", "stopAll": true}]
                }
                """);
        assertEquals(1, f.configurations().size());
        assertEquals("", f.configurations().get(0).name(), "a name that is no string reads as none");
        assertEquals("go", f.configurations().get(0).type());
        LaunchJson.Input pick = f.inputs().get("pick");
        assertEquals("", pick.type());
        assertEquals(List.of("a", "b"), pick.options());
        assertEquals(List.of(Map.of("label", "build")), f.tasks());
        assertEquals(2, f.compounds().size());
        assertEquals(List.of("a", "b"), f.compounds().get(0).configurations());
        assertEquals(false, f.compounds().get(0).stopAll());
        assertEquals("", f.compounds().get(0).preLaunchTask());
        assertEquals(true, f.compounds().get(1).stopAll());
        assertThrows(LaunchJson.LaunchJsonException.class, () -> LaunchJson.parse("[1]"));
        assertThrows(LaunchJson.LaunchJsonException.class, () -> LaunchJson.parse("{"));
    }

    @Test
    void commentsAndTrailingCommasInEveryPlaceTheyCanBe() {
        assertEquals("{\"a\": \"x//y\"\n}", LaunchJson.withoutCommentsAndTrailingCommas("{\"a\": \"x//y\"// c\n}"));
        assertEquals(
                "{\"a\": \"q\\\"/*\" }", LaunchJson.withoutCommentsAndTrailingCommas("{\"a\": \"q\\\"/*\"/* c */}"));
        assertEquals("[1   ]", LaunchJson.withoutCommentsAndTrailingCommas("[1, /* last */ ]"));
        assertEquals("[1 \n]", LaunchJson.withoutCommentsAndTrailingCommas("[1, // last\n]"));
        assertEquals("[1,2]", LaunchJson.withoutCommentsAndTrailingCommas("[1,2]"));
        assertEquals(
                "[1,", LaunchJson.withoutCommentsAndTrailingCommas("[1,"), "a comma at the end is no trailing one");
        assertEquals(
                "[1, ", LaunchJson.withoutCommentsAndTrailingCommas("[1,/* open"), "an unclosed comment ends the text");
        assertEquals("\"open", LaunchJson.withoutCommentsAndTrailingCommas("\"open"), "an unclosed string");
        assertEquals("a/b", LaunchJson.withoutCommentsAndTrailingCommas("a/b"));
        assertEquals("[1,/x]", LaunchJson.withoutCommentsAndTrailingCommas("[1,/x]"));
        assertEquals("[1,\n", LaunchJson.withoutCommentsAndTrailingCommas("[1,// to the end"));
    }

    @Test
    void jsonValuesBecomePlainJava() {
        assertNull(LaunchJson.toJava(null));
        assertNull(LaunchJson.toJava(com.google.gson.JsonNull.INSTANCE));
        assertEquals(1.5, LaunchJson.toJava(new com.google.gson.JsonPrimitive(1.5)));
        assertEquals(3L, LaunchJson.toJava(new com.google.gson.JsonPrimitive(3)));
        assertEquals(1e16, LaunchJson.toJava(new com.google.gson.JsonPrimitive(1e16)), "past a long's exact range");
        assertEquals(
                Double.POSITIVE_INFINITY,
                LaunchJson.toJava(new com.google.gson.JsonPrimitive(Double.POSITIVE_INFINITY)));
    }

    @Test
    void fileVariablesOfAFileOutsideTheWorkspaceOrWithoutAnExtension(@TempDir Path ws) throws Exception {
        Path outside = ws.getRoot().resolve("elsewhere").resolve("Makefile"); // absolute here (a drive on Windows)
        LaunchJson.Context ctx = new LaunchJson.Context(ws, outside, 1, "", Map.of(), ws, in -> "x");
        Map<String, String> answers = new HashMap<>();
        assertEquals(
                "Makefile|",
                LaunchJson.substitute("${fileBasenameNoExtension}|${fileExtname}", ctx, Map.of(), answers));
        assertEquals(outside.toString(), LaunchJson.substitute("${relativeFile}", ctx, Map.of(), answers));
        assertEquals(
                String.valueOf(outside.getParent()),
                LaunchJson.substitute("${relativeFileDirname}", ctx, Map.of(), answers));
        assertEquals(java.io.File.separator, LaunchJson.substitute("${/}", ctx, Map.of(), answers));
        assertEquals(ws.toString(), LaunchJson.substitute("${workspaceRoot}", ctx, Map.of(), answers));
        assertEquals(
                "pre-set", LaunchJson.substitute("${input:id}", ctx, Map.of(), new HashMap<>(Map.of("id", "pre-set"))));
        LaunchJson.Context root = new LaunchJson.Context(ws, ws.getRoot(), 1, "", Map.of(), ws, in -> "x");
        assertEquals(
                "",
                LaunchJson.substitute(
                        "${fileDirname}${fileDirnameBasename}${relativeFileDirname}", root, Map.of(), answers));
        Path atRoot = ws.getRoot().resolve("notes.txt"); // a file directly in the root: its folder has no name
        LaunchJson.Context rootFile = new LaunchJson.Context(ws, atRoot, 1, "", Map.of(), ws, in -> "x");
        assertEquals(ws.getRoot().toString(), LaunchJson.substitute("${fileDirname}", rootFile, Map.of(), answers));
        assertEquals("", LaunchJson.substitute("${fileDirnameBasename}", rootFile, Map.of(), answers));
        assertThrows(
                LaunchJson.LaunchJsonException.class,
                () -> LaunchJson.substitute("${fileNope}", ctx, Map.of(), answers),
                "a file variable VS Code does not have");
    }

    @Test
    void aTasksArgumentsMayBeQuotedObjectsAndATaskWithoutACommandRunsNothing() {
        assertEquals(
                List.of("make", "a b", "c", "{quoting=weak}"),
                LaunchJson.taskArgv(
                        Map.of(
                                "command",
                                "make",
                                "args",
                                List.of(Map.of("value", "a b", "quoting", "strong"), "c", Map.of("quoting", "weak"))),
                        false));
        assertNull(LaunchJson.taskArgv(Map.of("command", " "), false));
        assertNull(LaunchJson.taskArgv(Map.of("args", List.of()), false));
        assertNull(LaunchJson.taskArgv(Map.of("command", "make", "dependsOn", "x"), false));
    }
}

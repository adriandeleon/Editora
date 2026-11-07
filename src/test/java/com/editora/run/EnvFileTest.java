package com.editora.run;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** {@code .env} files: the {@code @file} entry of a run configuration's environment and the dotenv format. */
class EnvFileTest {

    @Test
    void aDotEnvFileIsReadAsDotenvToolsReadIt() {
        String file = String.join(
                "\n",
                "# a comment",
                "",
                "export HOST=db.local",
                "PORT = 5432 # trailing comment",
                "URL=\"postgres://${HOST}:$PORT/app\\n\"",
                "LITERAL='$HOST stays'",
                "EMPTY=",
                "HOME_BIN=$HOME/bin",
                "not a pair",
                "=nameless");
        Map<String, String> vars = EnvVars.parseDotEnv(file, name -> "HOME".equals(name) ? "/home/u" : null);
        assertEquals(List.of("HOST", "PORT", "URL", "LITERAL", "EMPTY", "HOME_BIN"), List.copyOf(vars.keySet()));
        assertEquals("db.local", vars.get("HOST"));
        assertEquals("5432", vars.get("PORT"));
        assertEquals("postgres://db.local:5432/app\n", vars.get("URL"));
        assertEquals("$HOST stays", vars.get("LITERAL"));
        assertEquals("", vars.get("EMPTY"));
        assertEquals("/home/u/bin", vars.get("HOME_BIN"));
    }

    @Test
    void anAtEntryLoadsAnEnvFileWhereItStands(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve(".env"), "A=from-file\nB=from-file\n");
        Files.writeString(dir.resolve("my vars.env"), "C=spaced\n");
        Map<String, String> vars = EnvVars.resolve("B=before @.env B=after D=$A \"@my vars.env\"", name -> null, dir);
        assertEquals("from-file", vars.get("A"));
        assertEquals("after", vars.get("B"), "a later entry overrides the file");
        assertEquals("from-file", vars.get("D"), "and can use its variables");
        assertEquals("spaced", vars.get("C"), "a quoted path may have spaces");

        Path absolute = dir.resolve(".env").toAbsolutePath();
        assertEquals(
                "from-file", EnvVars.resolve("@" + absolute, name -> null, null).get("A"));
    }

    @Test
    void aFileThatCannotBeReadStopsTheLaunchAndNamesIt(@TempDir Path dir) {
        EnvVars.EnvFileException e = assertThrows(
                EnvVars.EnvFileException.class, () -> EnvVars.resolve("A=1 @missing.env", name -> null, dir));
        assertEquals(dir.resolve("missing.env"), e.file());
    }

    @Test
    void withoutAnAtEntryNothingChanges() {
        assertEquals(Map.of("A", "1"), EnvVars.resolve("A=1 @ =x", name -> null, null));
    }
}

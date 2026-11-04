package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import com.editora.doctor.DoctorCheck;
import com.editora.doctor.DoctorStatus;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Doctor's TypeScript / JavaScript row: a TypeScript 6 beside the server is a warning with Update (JS review B101),
 * Editora's own TypeScript 5.9 is OK and named, and a row that offers no Update never says "Click Update…" (B113).
 */
class DoctorTypeScriptRowTest {

    @BeforeAll
    static void english() {
        com.editora.i18n.Messages.init("en");
    }

    private static Path server(Path dir, String besideVersion, boolean tsserver) throws Exception {
        Path global = dir.resolve("lib/node_modules");
        Path cli = global.resolve("typescript-language-server/lib/cli.mjs");
        Files.createDirectories(cli.getParent());
        Files.writeString(cli, "");
        Path lib = Files.createDirectories(global.resolve("typescript/lib"));
        Files.writeString(lib.getParent().resolve("package.json"), "{ \"version\": \"" + besideVersion + "\" }");
        if (tsserver) {
            Files.writeString(lib.resolve("tsserver.js"), "");
        }
        return cli;
    }

    private static String tip(DoctorCheck c) {
        return com.editora.i18n.Messages.tr(c.tipKey(), c.tipArgs().toArray());
    }

    private static DoctorCheck base() {
        return DoctorCheck.checking("lsp.typescript", "lsp", "TypeScript / JavaScript", "typescript-language-server")
                .withInstall(DoctorCheck.Install.LANG, "JAVASCRIPT");
    }

    @Test
    void typescriptSixBesideTheServerAsksForEditorasTypescript(@TempDir Path dir) throws Exception {
        Path cli = server(dir, "6.0.3", true);
        DoctorCheck row = DoctorCoordinator.typeScriptServerRow(
                base(), List.of(cli.toString(), "--stdio"), cli.toString(), dir.resolve("cfg"), false);
        assertEquals(DoctorStatus.WARN, row.status());
        assertTrue(row.update(), "Update installs TypeScript 5.9 into Editora's folder");
        assertTrue(tip(row).contains("6.0.3") && tip(row).contains("Update"), tip(row));
        DoctorCheck own = DoctorCoordinator.typeScriptServerRow(
                base(), List.of(cli.toString(), "--stdio"), cli.toString(), dir.resolve("cfg"), true);
        assertEquals(DoctorStatus.WARN, own.status());
        assertFalse(tip(own).contains("Click Update"), tip(own));
        assertTrue(tip(own).contains("Install…"), tip(own));
    }

    @Test
    void editorasOwnTypescriptIsOkAndNamed(@TempDir Path dir) throws Exception {
        Path cli = server(dir, "7.0.2", false);
        Path cfg = dir.resolve("cfg");
        Path lib = Files.createDirectories(cfg.resolve("plugins/lsp/typescript/node_modules/typescript/lib"));
        Files.writeString(lib.getParent().resolve("package.json"), "{ \"version\": \"5.9.3\" }");
        Files.writeString(lib.resolve("tsserver.js"), "");
        DoctorCheck row = DoctorCoordinator.typeScriptServerRow(
                base(), List.of(cli.toString(), "--stdio"), cli.toString(), cfg, false);
        assertEquals(DoctorStatus.OK, row.status());
        assertTrue(row.detail().contains("5.9.3") && row.detail().contains("tsserver.js"), row.detail());
    }

    @Test
    void typescriptSevenWithAnOwnCommandDoesNotPromiseAnUpdateButton(@TempDir Path dir) throws Exception {
        Path cli = server(dir, "7.0.2", false);
        DoctorCheck row = DoctorCoordinator.typeScriptServerRow(
                base(), List.of(cli.toString(), "--stdio"), cli.toString(), dir.resolve("cfg"), true);
        assertEquals(DoctorStatus.WARN, row.status());
        assertFalse(tip(row).contains("Click Update"), tip(row));
        assertFalse(tip(row).contains("npm install -g"), tip(row));
    }
}

package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import com.editora.config.TrustStore;
import com.editora.doctor.DoctorCheck;
import com.editora.doctor.DoctorService;
import com.editora.doctor.DoctorStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Doctor's ESLint, Prettier and Biome rows for a project (JS review F). */
class JsToolDoctorRowsTest {

    @TempDir
    Path project;

    private static Map<String, DoctorCheck> resolved(List<DoctorService.CheckSpec> specs) {
        return specs.stream()
                .map(s -> s.probe() == null ? s.placeholder() : s.probe().get())
                .collect(Collectors.toMap(DoctorCheck::id, c -> c));
    }

    @Test
    void noPackageJsonNoRows() {
        assertTrue(
                JsToolDoctorRows.rows(project, new TrustStore(), true, "auto").isEmpty());
        assertTrue(JsToolDoctorRows.rows(null, new TrustStore(), true, "auto").isEmpty());
    }

    @Test
    void aConfigurationWithoutEslintSaysToInstallItAndAnInstalledOneShowsItsVersion() throws Exception {
        Files.writeString(project.resolve("package.json"), "{\"devDependencies\":{\"prettier\":\"3.3.3\"}}");
        Files.writeString(project.resolve("eslint.config.js"), "export default [];");
        TrustStore trust = new TrustStore();
        Map<String, DoctorCheck> rows = resolved(JsToolDoctorRows.rows(project, trust, true, "auto"));
        assertEquals(DoctorStatus.WARN, rows.get("js.eslint").status());
        assertEquals("doctor.tip.eslintNotInstalled", rows.get("js.eslint").tipKey());
        assertEquals(
                "doctor.tip.jsFormatterNotInstalled", rows.get("js.prettier").tipKey(), "Prettier is used");
        assertEquals(DoctorStatus.OPTIONAL, rows.get("js.biome").status(), "Biome is not");

        Path eslint = Files.createDirectories(project.resolve("node_modules/eslint/bin"))
                .resolve("eslint.js");
        Files.writeString(eslint, "");
        Files.writeString(project.resolve("node_modules/eslint/package.json"), "{\"version\":\"9.12.0\"}");
        DoctorCheck untrusted =
                resolved(JsToolDoctorRows.rows(project, trust, true, "auto")).get("js.eslint");
        assertEquals(DoctorStatus.WARN, untrusted.status());
        assertEquals("doctor.tip.jsToolUntrusted", untrusted.tipKey());
        trust.trust(project);
        DoctorCheck ok =
                resolved(JsToolDoctorRows.rows(project, trust, true, "auto")).get("js.eslint");
        assertEquals(DoctorStatus.OK, ok.status());
        assertEquals("9.12.0 — " + eslint, ok.detail());
        assertEquals(
                DoctorStatus.DISABLED,
                resolved(JsToolDoctorRows.rows(project, trust, false, "auto"))
                        .get("js.eslint")
                        .status());
    }
}

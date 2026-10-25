package com.editora.doctor;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DoctorCheckTest {

    @Test
    void checkingPlaceholderIsNeutral() {
        DoctorCheck c = DoctorCheck.checking("git", "vcs", "Git", "git");
        assertEquals(DoctorStatus.CHECKING, c.status());
        assertEquals("", c.detail());
        assertEquals("", c.tipKey());
        assertEquals(DoctorCheck.Install.NONE, c.install());
        assertEquals("", c.settingsKey());
    }

    @Test
    void outcomeCopiesPreserveInstallAndSettingsActions() {
        DoctorCheck base = DoctorCheck.checking("lsp.json", "lsp", "JSON", "vscode-json-language-server")
                .withSettings("lsp")
                .withInstall(DoctorCheck.Install.SERVER, "json");
        DoctorCheck missing = base.missing("doctor.tip.missing", "vscode-json-language-server");
        assertEquals(DoctorStatus.MISSING, missing.status());
        assertEquals(DoctorCheck.Install.SERVER, missing.install());
        assertEquals("json", missing.installArg());
        assertEquals("lsp", missing.settingsKey());
        assertEquals(List.of("vscode-json-language-server"), missing.tipArgs());

        DoctorCheck ok = base.ok("/usr/local/bin/vscode-json-language-server");
        assertEquals(DoctorStatus.OK, ok.status());
        assertEquals("/usr/local/bin/vscode-json-language-server", ok.detail());
        assertEquals("", ok.tipKey());
        assertEquals(DoctorCheck.Install.SERVER, ok.install());
    }

    @Test
    void disabledCarriesTheGenericTip() {
        DoctorCheck c =
                DoctorCheck.checking("mmdc", "preview", "Mermaid CLI", "mmdc").disabled();
        assertEquals(DoctorStatus.DISABLED, c.status());
        assertEquals("doctor.tip.disabled", c.tipKey());
    }

    @Test
    void nullsNormalizeToEmpty() {
        DoctorCheck c = new DoctorCheck(
                "id", "sec", "L", null, DoctorStatus.OK, null, null, null, null, null, null, null, null, false);
        assertEquals("", c.command());
        assertEquals("", c.detail());
        assertEquals("", c.tipKey());
        assertTrue(c.tipArgs().isEmpty());
        assertEquals(DoctorCheck.Install.NONE, c.install());
        assertEquals("", c.installArg());
        assertEquals("", c.settingsKey());
        assertEquals("", c.enableKey());
        assertEquals("", c.helpUrl());
    }

    @Test
    void outdatedIsAWarningWhoseInstallReadsUpdate() {
        DoctorCheck base = DoctorCheck.checking("lsp.groovy", "lsp", "Groovy", "gls")
                .withInstall(DoctorCheck.Install.SERVER, "groovy");

        DoctorCheck c = base.outdated("0.4.7 — /x/gls-0.4.7.jar", "doctor.tip.pinOutdated", "0.4.7", "0.4.8");

        assertEquals(DoctorStatus.WARN, c.status());
        assertTrue(c.update());
        assertEquals(DoctorCheck.Install.SERVER, c.install());
        assertEquals("groovy", c.installArg());
        assertEquals(List.of("0.4.7", "0.4.8"), c.tipArgs());
        assertFalse(base.update());
        assertFalse(c.ok("0.4.8").update(), "a fresh outcome is not an update");
        assertFalse(base.warn("", "doctor.tip.serverFailing").update(), "a plain warning offers no Update");
    }

    @Test
    void optionalIsTheNeutralAbsentStateAndKeepsItsActions() {
        DoctorCheck c = DoctorCheck.checking("lsp.lua", "lsp", "Lua", "lua-language-server")
                .withInstall(DoctorCheck.Install.SERVER, "lua")
                .withSettings("lsp")
                .optional("doctor.tip.serverOptional");
        assertEquals(DoctorStatus.OPTIONAL, c.status());
        assertFalse(c.status().needsAttention(), "a tool nothing needs is not a problem");
        assertEquals(DoctorCheck.Install.SERVER, c.install());
        assertEquals("lsp", c.settingsKey());
        assertTrue(DoctorStatus.MISSING.needsAttention());
        assertTrue(DoctorStatus.WARN.needsAttention());
    }

    @Test
    void actionCopiesKeepEverythingElse() {
        DoctorCheck c = DoctorCheck.checking("debug.java", "debug", "Java debug plugin", "")
                .withInstall(DoctorCheck.Install.LANG, "JAVA")
                .missing("doctor.tip.needsLsp")
                .withoutInstall()
                .withEnable("lsp")
                .withHelp("https://example.invalid/help")
                .withCommand("jdtls");
        assertEquals(DoctorCheck.Install.NONE, c.install());
        assertEquals("lsp", c.enableKey());
        assertEquals("https://example.invalid/help", c.helpUrl());
        assertEquals("jdtls", c.command());
        assertEquals(DoctorStatus.MISSING, c.status());
        assertEquals("doctor.tip.needsLsp", c.tipKey());
    }
}

package com.editora.ui;

import java.nio.file.Path;
import java.util.List;

import com.editora.i18n.Messages;
import com.editora.toolchain.JkitCli;
import com.editora.toolchain.ToolInstall;
import com.editora.toolchain.ToolRequest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The words and names behind Tools ▸ Java Toolchain, with no window. */
class ToolchainCommandsTest {

    @BeforeAll
    static void english() {
        Messages.init("en");
    }

    private static ToolInstall install(
            String tool, String version, String vendor, boolean fx, String source, String id) {
        return new ToolInstall(tool, version, vendor, fx, Path.of("/t/" + id), source, id);
    }

    @Test
    void sdkmanrcNamesEachInstallTheWaySdkmanDoes() {
        assertEquals(
                "21.0.9-tem",
                ToolchainCommands.sdkmanValue(install("jdk", "21.0.9", "temurin", false, "sdkman", "21.0.9-tem")));
        assertEquals(
                "21.0.9-tem",
                ToolchainCommands.sdkmanValue(install("jdk", "21.0.9+10", "temurin", false, "jkit", "x")));
        assertEquals(
                "21.0.9.fx-zulu", ToolchainCommands.sdkmanValue(install("jdk", "21.0.9", "zulu", true, "other", "x")));
        assertNull(ToolchainCommands.sdkmanValue(install("jdk", "21.0.9", "redhat", false, "other", "x")));
        assertEquals(
                "3.9.11", ToolchainCommands.sdkmanValue(install("maven", "3.9.11", null, false, "jkit", "3.9.11")));
        assertEquals("8.14", ToolchainCommands.sdkmanValue(install("gradle", "8.14+1", null, false, "jkit", "x")));
        assertNull(ToolchainCommands.sdkmanValue(install("maven", "3.9.11", null, false, "other", "x")));
    }

    @Test
    void projectSettingsNameEachInstallSoAnotherMachineFindsIt() {
        assertEquals(
                "21.0.9+10-temurin",
                ToolchainCommands.settingsValue(
                        install("jdk", "21.0.9+10", "temurin", false, "jkit", "21.0.9+10-temurin")));
        assertEquals(
                "21.0.9-zulu-fx",
                ToolchainCommands.settingsValue(install("jdk", "21.0.9", "zulu", true, "other", "z")));
        assertEquals(
                "21.0.9-redhat",
                ToolchainCommands.settingsValue(install("jdk", "21.0.9", "redhat", false, "other", "r")));
        assertEquals(
                Path.of("/t/j").toString(),
                ToolchainCommands.settingsValue(install("jdk", "21", null, false, "other", "j")));
        assertEquals(
                Path.of("/t/m").toString(),
                ToolchainCommands.settingsValue(install("maven", "3", null, false, "other", "m")));
    }

    @Test
    void jkitInstallsOnlyWhatItCanName() {
        assertEquals(
                List.of("install", "jdk@21.0.9", "temurin"),
                ToolchainCommands.jkitArgs(ToolRequest.parse("jdk", "21.0.9-tem")));
        assertNull(ToolchainCommands.jkitArgs(ToolRequest.parse("jdk", "22.1.0-xyz"))); // a vendor jkit cannot name
        assertNull(ToolchainCommands.jkitArgs(ToolRequest.parse("jdk", "/opt/jdk")));
        assertEquals(List.of("install", "jdk@21"), ToolchainCommands.jkitArgs(ToolRequest.parse("jdk", "21")));
        assertEquals(
                List.of("install", "maven@3.9.11"), ToolchainCommands.jkitArgs(ToolRequest.parse("maven", "3.9.11")));
    }

    @Test
    void theQuestionsNameWhatTheyAreAbout() {
        ToolInstall jdk = install("jdk", "21.0.9+10", "temurin", false, "jkit", "21.0.9+10-temurin");
        Confirm.Question remove = ToolchainCommands.removeQuestion(jdk);
        assertTrue(remove.header().contains("JDK 21.0.9+10 · temurin"));
        assertTrue(remove.message().contains(jdk.home().toString()));
        assertEquals("Remove", remove.action());

        Confirm.Choice where = ToolchainCommands.whereQuestion(jdk, "21.0.9-tem");
        assertTrue(where.message().contains("java=21.0.9-tem"));
        assertEquals(2, where.answers().size());

        Confirm.Question upgrade = ToolchainCommands.upgradeQuestion(List.of(
                new JkitCli.UpgradeLine("jdk", 21, "temurin", false, "21.0.5+11", "21.0.9+10", "available", null),
                new JkitCli.UpgradeLine("maven", 3, null, false, "3.9.9", "3.9.11", "available", null)));
        assertTrue(upgrade.header().startsWith("2 newer builds"));
        assertTrue(upgrade.message().contains("• JDK 21 temurin: 21.0.5+11 → 21.0.9+10"));
        assertTrue(upgrade.message().contains("• Maven 3: 3.9.9 → 3.9.11"));
        assertTrue(ToolchainCommands.upgradeQuestion(
                        List.of(new JkitCli.UpgradeLine("gradle", 8, null, false, "8.1", "8.14", "available", null)))
                .header()
                .startsWith("One newer build"));
    }

    @Test
    void availableBuildsReadAsAPickerRow() {
        assertEquals(
                "JDK 25 · temurin · 25.0.1+8 · LTS",
                ToolchainCommands.availableLabel(
                        new JkitCli.Available("jdk", "25.0.1+8", 25, "temurin", false, true, true, false, false)));
        assertEquals(
                "JDK 23 · zulu · 23.0.2 · end of life",
                ToolchainCommands.availableLabel(
                        new JkitCli.Available("jdk", "23.0.2", 23, "zulu", false, false, false, false, false)));
        assertEquals(
                "Maven · 3.9.11",
                ToolchainCommands.availableLabel(
                        new JkitCli.Available("maven", "3.9.11", 3, null, false, false, true, false, true)));
        assertEquals("Gradle", ToolchainCommands.toolName("gradle"));
        assertEquals("ant", ToolchainCommands.toolName("ant"));
    }
}

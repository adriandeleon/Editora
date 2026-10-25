package com.editora.lsp;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import com.editora.editorconfig.EditorConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JavaProjectSettingsTest {

    @TempDir
    Path root;

    @AfterEach
    void forget() {
        EditorConfig.clearCache();
    }

    static final String PROFILE = """
            <?xml version="1.0" encoding="UTF-8"?>
            <profiles version="23">
            <profile kind="CodeFormatterProfile" name="Acme" version="23">
            <setting id="org.eclipse.jdt.core.formatter.tabulation.char" value="space"/>
            </profile>
            </profiles>
            """;

    @Test
    void intellijsImportLayoutBecomesJdtlsImportOrder() {
        assertEquals(List.of("", "javax", "java", "#"), JavaProjectSettings.importOrder("*,|,javax.**,java.**,|,$*"));
        assertEquals(List.of("java", "", "#org.junit"), JavaProjectSettings.importOrder("java.*, *, $org.junit.**"));
        assertNull(JavaProjectSettings.importOrder(" "));
        assertNull(JavaProjectSettings.importOrder("|"));
    }

    @Test
    void aProjectsEditorConfigAndSpotlessProfileAreRead() throws Exception {
        Files.writeString(root.resolve(".editorconfig"), """
                root = true
                [*.java]
                ij_java_imports_layout = *,|,javax.**,java.**,|,$*
                ij_java_class_count_to_use_import_on_demand = 3
                ij_java_names_count_to_use_import_on_demand = 2
                [*.md]
                ij_java_class_count_to_use_import_on_demand = 50
                """);
        Files.createDirectories(root.resolve("build-tools"));
        Files.writeString(root.resolve("build-tools/style.xml"), PROFILE);
        Files.writeString(root.resolve("pom.xml"), """
                <project><build><plugins><plugin><artifactId>spotless-maven-plugin</artifactId>
                <configuration><java><eclipse><file>${project.basedir}/build-tools/style.xml</file></eclipse></java>
                </configuration></plugin></plugins></build></project>
                """);

        Map<String, Object> java = JavaProjectSettings.forRoot(root);

        assertEquals(Map.of("importOrder", List.of("", "javax", "java", "#")), java.get("completion"));
        assertEquals(
                Map.of("organizeImports", Map.of("starThreshold", 3, "staticStarThreshold", 2)), java.get("sources"));
        assertEquals(
                Map.of(
                        "settings",
                        Map.of(
                                "url",
                                JavaProjectSettings.contentAddressed(root.resolve("build-tools/style.xml"))
                                        .toUri()
                                        .toString(),
                                "profile",
                                "Acme")),
                java.get("format"));
    }

    /** An edited profile gets a new URL, which is what makes jdtls read it again (third review AN5). */
    @Test
    void anEditedProfileIsSentUnderANewNameAndAnUnchangedOneUnderTheSame() throws Exception {
        Path profile = root.resolve("eclipse-formatter.xml");
        Files.writeString(profile, PROFILE);
        Path first = JavaProjectSettings.contentAddressed(profile);
        assertEquals(first, JavaProjectSettings.contentAddressed(profile), "unchanged: the same name");
        assertEquals(PROFILE, Files.readString(first));
        Files.writeString(profile, PROFILE.replace("Acme", "Acme2"));
        Path second = JavaProjectSettings.contentAddressed(profile);
        assertTrue(!second.equals(first), "edited: a new name");
        assertTrue(Files.readString(second).contains("Acme2"));
    }

    @Test
    void aConventionalProfileCountsOnlyWhenItIsOneAndNothingIsSentForAPlainProject() throws Exception {
        assertTrue(JavaProjectSettings.forRoot(root).isEmpty(), "nothing stated: jdtls's defaults");
        Files.writeString(root.resolve("formatter.xml"), "<formatter>not Eclipse's</formatter>");
        assertNull(JavaProjectSettings.formatterProfile(root));
        Files.writeString(root.resolve("eclipse-formatter.xml"), PROFILE);
        assertEquals(root.resolve("eclipse-formatter.xml"), JavaProjectSettings.formatterProfile(root));
    }

    @Test
    void aGradleSpotlessProfileIsRead() throws Exception {
        Files.writeString(root.resolve("acme.xml"), PROFILE);
        Files.writeString(root.resolve("build.gradle.kts"), "spotless { java { eclipse().configFile(\"acme.xml\") } }");
        assertEquals(root.resolve("acme.xml"), JavaProjectSettings.formatterProfile(root));
    }

    @Test
    void nestedSettingsMergeKeyByKey() {
        Map<String, Object> base = Map.of("completion", Map.of("filteredTypes", List.of("sun.*")), "autobuild", false);
        Map<String, Object> merged =
                JavaProjectSettings.merge(base, Map.of("completion", Map.of("importOrder", List.of("java"))));
        assertEquals(
                Map.of("filteredTypes", List.of("sun.*"), "importOrder", List.of("java")), merged.get("completion"));
        assertEquals(false, merged.get("autobuild"));
    }
}

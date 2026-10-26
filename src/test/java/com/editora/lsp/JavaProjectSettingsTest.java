package com.editora.lsp;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
        String own = PROFILE.replace("Acme", "AcmeEdited"); // no other test's profile shares its copy
        Files.writeString(profile, own);
        Path first = JavaProjectSettings.contentAddressed(profile);
        assertEquals(first, JavaProjectSettings.contentAddressed(profile), "unchanged: the same name");
        assertEquals(own, Files.readString(first));
        Files.writeString(profile, PROFILE.replace("Acme", "Acme2"));
        Path second = JavaProjectSettings.contentAddressed(profile);
        assertTrue(!second.equals(first), "edited: a new name");
        assertTrue(Files.readString(second).contains("Acme2"));
        assertTrue(!Files.exists(first), "the old copy goes: they piled up (fourth review DF2)");
    }

    /**
     * The copies are this process's own: a private folder no other user can guess or write, and a copy that does
     * not hold what its name says is written again (fourth review DF2).
     */
    @Test
    void theCopiesArePrivateAndCheckedBeforeUse() throws Exception {
        Path profile = root.resolve("eclipse-formatter.xml");
        Files.writeString(profile, PROFILE);
        Path copy = JavaProjectSettings.contentAddressed(profile);
        Path dir = copy.getParent();
        assertTrue(dir.getFileName().toString().startsWith("editora-formatter-profiles-"), dir.toString());
        assertTrue(!dir.getFileName().toString().equals("editora-formatter-profiles"), "not the shared name");
        if (dir.getFileSystem().supportedFileAttributeViews().contains("posix")) {
            assertEquals(
                    java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"),
                    Files.getPosixFilePermissions(dir));
        }
        Files.writeString(copy, "<profiles>planted</profiles>");
        assertEquals(copy, JavaProjectSettings.contentAddressed(profile));
        assertEquals(PROFILE, Files.readString(copy), "rewritten from the profile");
    }

    /**
     * A server in a container or WSL gets the project's own profile by its name for it (it cannot read this
     * machine's temporary folder, DF2), and a profile removed is replaced by an empty one, which puts jdtls's
     * defaults back (DN4).
     */
    @Test
    void aMappedServerGetsTheProfileByItsOwnNameAndARemovedProfileGivesTheDefaults() throws Exception {
        Path profile = root.resolve("eclipse-formatter.xml");
        Files.writeString(profile, PROFILE);
        Map<String, Object> mapped = JavaProjectSettings.forRoot(root, uri -> uri.replace("file:///", "file:///work/"));
        assertEquals(
                profile.toUri().toString().replace("file:///", "file:///work/"),
                ((Map<?, ?>) ((Map<?, ?>) mapped.get("format")).get("settings")).get("url"));

        JavaProjectSettings.forRoot(root);
        Files.delete(profile);
        Map<?, ?> settings =
                (Map<?, ?>) ((Map<?, ?>) JavaProjectSettings.forRoot(root).get("format")).get("settings");
        assertEquals(JavaProjectSettings.EMPTY_NAME, settings.get("profile"));
        String empty = Files.readString(Path.of(java.net.URI.create((String) settings.get("url"))));
        assertTrue(empty.contains("CodeFormatterProfile") && !empty.contains("<setting"), empty);
        assertEquals(
                settings,
                ((Map<?, ?>) JavaProjectSettings.forRoot(root.resolve("never-profiled"))
                                .get("format"))
                        .get("settings"),
                "a project with none gets the defaults too: jdtls keeps a profile removed while Editora was closed (DV2)");
    }

    @Test
    void aConventionalProfileCountsOnlyWhenItIsOneAndNothingIsSentForAPlainProject() throws Exception {
        assertEquals(Set.of("format"), JavaProjectSettings.forRoot(root).keySet(), "nothing stated: jdtls's defaults");
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

    /** A style folder that comes or goes is a style change: a checkout writes it with its profile (sixth DN4). */
    @Test
    void theStyleFoldersAreStyleSources() {
        assertTrue(JavaProjectSettings.isStyleSource(root.resolve(".settings")));
        assertTrue(JavaProjectSettings.isStyleSource(root.resolve("config")));
        assertTrue(!JavaProjectSettings.isStyleSource(root.resolve("src")));
    }
}

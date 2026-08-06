package com.editora.template;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;

import com.editora.editor.LanguageRegistry;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the "New ▸" catalog's invariants — the ones that would otherwise fail silently in the menu:
 * a duplicate id, a translated label with no i18n key, or an entry for a file type Editora does not
 * actually understand (which would create a file that opens as plain text).
 */
class NewFileCatalogTest {

    private static Properties messages() {
        Properties props = new Properties();
        try (InputStream in = NewFileCatalogTest.class.getResourceAsStream("/com/editora/i18n/messages.properties")) {
            assertNotNull(in, "base message catalog not on the test classpath");
            props.load(new InputStreamReader(in, StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new AssertionError(e);
        }
        return props;
    }

    @Test
    void idsAreUniqueAcrossTheWholeCatalog() {
        Set<String> seen = new HashSet<>();
        for (NewFileType type : NewFileCatalog.all()) {
            assertTrue(seen.add(type.id()), "duplicate type id: " + type.id());
        }
    }

    @Test
    void everyTypeIsReachableByIdAndKnowsItsCategory() {
        for (NewFileType type : NewFileCatalog.all()) {
            assertEquals(type, NewFileCatalog.byId(type.id()), type.id() + " is not reachable via byId");
        }
        for (NewFileCatalog.Category category : NewFileCatalog.categories()) {
            for (NewFileType type : category.types()) {
                assertEquals(category.id(), NewFileCatalog.categoryOf(type), type.id());
            }
        }
        // The generic and top-level entries sit above the submenus, so they have no category.
        assertEquals(null, NewFileCatalog.categoryOf(NewFileCatalog.PLAIN));
        assertEquals(null, NewFileCatalog.categoryOf(NewFileCatalog.TEXT));
    }

    @Test
    void everyTranslatedLabelHasAnI18nKey() {
        Properties messages = messages();
        List<String> missing = new ArrayList<>();
        for (NewFileType type : NewFileCatalog.all()) {
            if (!type.hasLiteralLabel() && !messages.containsKey(type.labelKey())) {
                missing.add(type.labelKey());
            }
        }
        for (NewFileCatalog.Category category : NewFileCatalog.categories()) {
            if (!messages.containsKey(category.labelKey())) {
                missing.add(category.labelKey());
            }
        }
        assertTrue(missing.isEmpty(), "catalog entries with no message key: " + missing);
        // The catalogs are key-parity-checked by MessagesTest, so the base is enough here.
    }

    @Test
    void aLiteralLabelIsANameAndATranslatedOneIsNotEmpty() {
        for (NewFileType type : NewFileCatalog.all()) {
            if (type.hasLiteralLabel()) {
                assertFalse(type.label().isBlank(), type.id() + " has a blank literal label");
            }
        }
    }

    /**
     * The point of the catalog is that the file it creates opens as something. An entry whose suggested
     * name resolves to plaintext means the extension is wrong or the language was never registered — the
     * user would get a file that looks like an unrecognized blob.
     */
    @Test
    void everyTypeCreatesAFileEditoraRecognizes() {
        List<String> unrecognized = new ArrayList<>();
        for (NewFileType type : NewFileCatalog.all()) {
            if (type == NewFileCatalog.PLAIN || type == NewFileCatalog.TEXT) {
                continue; // plain text on purpose
            }
            String language = LanguageRegistry.forFileName(type.suggestedFileName());
            if (LanguageRegistry.PLAINTEXT.equals(language)) {
                unrecognized.add(type.id() + " (" + type.suggestedFileName() + ")");
            }
        }
        assertTrue(unrecognized.isEmpty(), "types whose file would open as plain text: " + unrecognized);
    }

    @Test
    void theJavaCategoryOffersTheSourceKindsAnIdeDoes() {
        List<String> ids = NewFileCatalog.categories().stream()
                .filter(c -> c.id().equals("java"))
                .flatMap(c -> c.types().stream())
                .map(NewFileType::id)
                .toList();
        assertTrue(
                ids.containsAll(List.of("java.class", "java.interface", "java.record", "java.enum", "java.annotation")),
                "Java kinds present: " + ids);
        for (String id : ids) {
            assertTrue(NewFileCatalog.byId(id).isJava(), id + " is not treated as a Java kind");
        }
    }

    @Test
    void onlyJavaKindsTakeTheQualifiedNameShortcut() {
        for (NewFileType type : NewFileCatalog.all()) {
            boolean java = "java".equals(NewFileCatalog.categoryOf(type));
            assertEquals(java, type.isJava(), type.id());
        }
    }

    @Test
    void aSuggestedNameIsEitherEmptyOrCarriesTheTypesExtension() {
        for (NewFileType type : NewFileCatalog.all()) {
            String suggested = type.suggestedFileName();
            if (type == NewFileCatalog.PLAIN) {
                assertTrue(suggested.isEmpty(), "the generic entry must not suggest a name");
                continue;
            }
            assertFalse(suggested.isBlank(), type.id() + " suggests nothing");
            if (!type.extension().isEmpty()) {
                assertTrue(suggested.endsWith("." + type.extension()), type.id() + ": " + suggested);
            }
        }
    }
}

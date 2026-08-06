package com.editora.template;

/**
 * One entry in the "New ▸" menu: a file kind the user can create in a folder.
 *
 * <p>Deliberately <b>not</b> a {@link Template}. A template is a user-extensible, variable-driven
 * document (the "New From Template…" picker); this is the flat catalog of the file types Editora
 * already understands, offered directly on the Project tree's context menu the way every IDE does.
 * Adding one is a single row in {@link NewFileCatalog} — no resource file, no registry entry.
 *
 * @param id stable identifier ({@code java.class}, {@code data.yaml}); also the i18n key suffix for
 *     entries whose label is translatable, and what the palette picker matches on
 * @param label the menu label when it is a <em>proper name</em> that must not be translated —
 *     "Python", "Dockerfile", "XML". Null when the label is an ordinary word ("Class", "Interface",
 *     "Text File"), in which case the caller resolves {@code newfile.type.<id>} from the i18n
 *     catalog. This mirrors the existing convention that technical identifiers and detected language
 *     names stay untranslated.
 * @param defaultBaseName what the name prompt is prefilled with, without the extension
 * @param extension the extension appended when the typed name has none; {@code ""} for a file whose
 *     name <em>is</em> the whole thing ({@code Dockerfile}, {@code .gitignore}) or for the generic
 *     "File…" entry, where whatever the user types decides the type
 * @param template the initial contents, with two tokens substituted by
 *     {@link NewFileContent#render}: <code>{name}</code> (the base name) and <code>{package}</code>
 *     (a Java package declaration plus a blank line, or nothing outside a source root). A
 *     <code>{cursor}</code> token marks where the caret lands and is removed from the text. Empty =
 *     an empty file.
 */
public record NewFileType(String id, String label, String defaultBaseName, String extension, String template) {

    /** A type whose menu label is a proper name (kept verbatim in every language). */
    static NewFileType named(String id, String label, String defaultBaseName, String extension) {
        return new NewFileType(id, label, defaultBaseName, extension, "");
    }

    /** A type whose menu label is an ordinary word, resolved from {@code newfile.type.<id>}. */
    static NewFileType translated(String id, String defaultBaseName, String extension) {
        return new NewFileType(id, null, defaultBaseName, extension, "");
    }

    /** This type with {@code template} as its initial contents. */
    NewFileType withTemplate(String template) {
        return new NewFileType(id, label, defaultBaseName, extension, template);
    }

    /** True when the label is a proper name to show verbatim; false when it needs the i18n catalog. */
    public boolean hasLiteralLabel() {
        return label != null;
    }

    /** The i18n key for a translated label ({@code newfile.type.<id>}); meaningless when literal. */
    public String labelKey() {
        return "newfile.type." + id;
    }

    /** The name prompt's initial value: the base name plus the extension, if any. */
    public String suggestedFileName() {
        return extension.isEmpty() ? defaultBaseName : defaultBaseName + "." + extension;
    }

    /** True for the Java source kinds, whose name prompt accepts a qualified {@code a.b.Name}. */
    public boolean isJava() {
        return "java".equals(extension);
    }
}

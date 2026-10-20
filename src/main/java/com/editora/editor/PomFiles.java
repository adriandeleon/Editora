package com.editora.editor;

/**
 * What makes an XML buffer a Maven pom for the editor's purposes (the summary preview, pom snippets, the
 * pom-aware outline): a file named {@code pom.xml} or {@code *.pom}, or any XML whose head carries a
 * {@code <project>} root with a {@code <modelVersion>} (so a {@code pom-template.xml} or a generated
 * {@code effective-pom.xml} is recognized too). Pure; split out of {@link EditorBuffer} for its size cap.
 */
public final class PomFiles {

    /** How much of an XML file's head is read to decide whether it is a pom (a {@code <project>} preamble). */
    static final int SNIFF_CHARS = 4096;

    /** The snippet language of a pom buffer: its own file ({@code maven-pom.json}) on top of XML's. */
    public static final String SNIPPET_LANGUAGE = "maven-pom";

    private PomFiles() {}

    static boolean isPom(String fileName, String head) {
        if (fileName != null && ("pom.xml".equalsIgnoreCase(fileName) || fileName.endsWith(".pom"))) {
            return true;
        }
        return head != null && head.contains("<modelVersion") && head.contains("<project");
    }

    /** The language whose snippets a buffer gets: {@code maven-pom} for a pom, else {@code language} (its own). */
    public static String snippetLanguage(EditorBuffer b, String language) {
        return b != null && b.isPom() ? SNIPPET_LANGUAGE : language;
    }
}

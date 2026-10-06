package com.editora;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every RichTextFX area built outside the editor buffer must say what undo history it keeps, by being
 * constructed through {@code ui.AreaUndo.none(...)} (not user-editable: record nothing) or
 * {@code ui.AreaUndo.bounded(...)} (a field the user types into).
 *
 * <p>Left alone, an area keeps RichTextFX's default: an <em>unlimited</em> history that records every
 * programmatic {@code appendText}/{@code deleteText}/{@code replaceText}, editable or not. The Run, Build and
 * Debug consoles each kept every line they had ever shown — 111 MB after 200,000 lines, and growing — though
 * their text is capped at 200,000 characters.
 */
class RichTextAreaUndoPolicyTest {

    private static final String AREA_TYPES = "CodeArea|StyleClassedTextArea|InlineCssTextArea|StyledTextArea"
            + "|GenericStyledArea|StyleClassedTextField|InlineCssTextField|StyledTextField";

    /** A construction, with whatever call it is the argument of ({@code AreaUndo.none(new CodeArea())}). */
    private static final Pattern CONSTRUCTION =
            Pattern.compile("((?:[A-Za-z_][\\w.]*\\(\\s*)?)new\\s+(?:" + AREA_TYPES + ")\\s*(?:<[^>(]*>)?\\s*\\(");

    private static final Pattern SUBCLASS = Pattern.compile("\\bclass\\s+\\w+\\s+extends\\s+(?:" + AREA_TYPES + ")\\b");

    private static final Pattern DECIDED = Pattern.compile("AreaUndo\\.(?:none|bounded)\\(\\s*");

    /**
     * The editor's own areas: {@code TagRenameMirror} builds them for {@code EditorBuffer}, which installs
     * the document's bounded history (or none, for a large file) on each one it is given.
     */
    private static final Set<String> EDITOR_AREAS =
            Set.of("com/editora/editor/EditorBuffer.java", "com/editora/editor/TagRenameMirror.java");

    @Test
    void everyAreaOutsideTheEditorBufferDecidesItsUndoHistory() throws IOException {
        Path root = Path.of("src/main/java");
        List<String> offenders = new ArrayList<>();
        int[] constructions = {0};
        try (Stream<Path> files = Files.walk(root)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                String name = root.relativize(file).toString().replace('\\', '/');
                if (EDITOR_AREAS.contains(name)) {
                    continue;
                }
                String code = withoutComments(Files.readString(file));
                Matcher construction = CONSTRUCTION.matcher(code);
                while (construction.find()) {
                    constructions[0]++;
                    if (!DECIDED.matcher(construction.group(1)).matches()) {
                        offenders.add(name + ": " + construction.group().strip());
                    }
                }
                if (SUBCLASS.matcher(code).find()) {
                    offenders.add(name + ": subclasses a RichTextFX area");
                }
            }
        }
        assertEquals(
                List.of(),
                offenders,
                "build the area with AreaUndo.none(...) or AreaUndo.bounded(...): the default history is unlimited");
        assertTrue(constructions[0] >= 10, "the scan still recognises the areas it is meant to hold to this");
    }

    /** Line and block comments removed, so prose about {@code new CodeArea()} is not taken for code. */
    private static String withoutComments(String source) {
        return source.replaceAll("(?s)/\\*.*?\\*/", " ").replaceAll("(?m)^\\s*//.*$", " ");
    }
}

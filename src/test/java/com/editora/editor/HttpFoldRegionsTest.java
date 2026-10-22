package com.editora.editor;

import java.util.List;

import com.editora.editor.fold.FoldRegions;
import com.editora.editor.fold.FoldRegions.Region;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** A .http file folds per request, from its ### separator to its last line (C16). */
class HttpFoldRegionsTest {

    @Test
    void eachRequestFoldsFromItsSeparator() {
        String text = String.join(
                "\n",
                "@host = http://x.test", // 0
                "", // 1
                "### First", // 2
                "# @name first", // 3
                "GET {{host}}/a", // 4
                "Accept: application/json", // 5
                "", // 6
                "### Second", // 7
                "POST {{host}}/b", // 8
                "Content-Type: application/json", // 9
                "", // 10
                "{\"a\": 1}", // 11
                "", // 12
                "### One line", // 13
                "GET {{host}}/c"); // 14
        assertEquals(
                List.of(new Region(2, 5), new Region(7, 11), new Region(13, 14)), FoldRegions.detect(text, "http"));
    }

    @Test
    void aFirstRequestWithoutASeparatorFoldsFromItsRequestLine() {
        assertEquals(List.of(new Region(0, 1)), FoldRegions.detect("GET http://x.test/\nAccept: */*\n", "http"));
        assertEquals(List.of(), FoldRegions.detect("GET http://x.test/\n", "http"), "one line: nothing to fold");
    }

    @Test
    void variableLinesUnderASeparatorFoldWithTheirRequest() {
        String text =
                "###\n@a = 1\n# c\nGET http://x.test/\n\n### Two\n@b = 2\n@c = 3\nPOST http://x.test/\n\n{\"x\": 1}\n";
        assertEquals(List.of(new Region(0, 3), new Region(5, 10)), FoldRegions.detect(text, "http"));
    }
}

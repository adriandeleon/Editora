package com.editora.run;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.jspecify.annotations.Nullable;

/**
 * PHPUnit's test at the caret, for Debug Test at Caret: the method of a test file whose span holds the caret's line
 * — from its attributes and doc comment to the next method — when it is a test: named {@code test…}, or marked
 * {@code #[Test]} or {@code @test}. Pure.
 */
public final class PhpUnitTests {

    private PhpUnitTests() {}

    /** A method's line: {@code public function testAdds(}, {@code public static function pairs(}… */
    private static final Pattern METHOD = Pattern.compile(
            "^\\s*(?:(?:public|protected|private|static|final|abstract)\\s+)*function\\s+&?\\s*(\\w+)\\s*\\(");

    /** PHPUnit's {@code #[Test]} attribute, by its short or full name. */
    private static final Pattern TEST_ATTRIBUTE =
            Pattern.compile("#\\[\\s*\\\\?(?:PHPUnit\\\\Framework\\\\Attributes\\\\)?Test\\s*[\\],(]");

    /** A doc comment's {@code @test}. */
    private static final Pattern TEST_TAG = Pattern.compile("@test\\b");

    /**
     * The test method of {@code text} whose span holds 0-based line {@code caret}, or null when the caret is in none
     * (on the class's line, in a helper or a data provider: the whole file runs).
     */
    public static @Nullable String at(String text, int caret) {
        String[] lines = text.split("\n", -1);
        String found = null;
        boolean test = false;
        int start = -1; // where the method's attributes and doc comment begin
        for (int i = 0; i < lines.length; i++) {
            Matcher m = METHOD.matcher(lines[i]);
            if (!m.find()) {
                continue;
            }
            int head = headOf(lines, i);
            if (caret < head) {
                break;
            }
            found = m.group(1);
            start = head;
            test = found.startsWith("test") || marked(lines, head, i);
        }
        return found != null && start >= 0 && test ? found : null;
    }

    /** PHPUnit's {@code --filter} for test {@code method} of the file run: its data sets too. */
    public static String filter(String method) {
        return "/::" + method + "(?: .*)?$/";
    }

    /** The first line of the attributes and doc comment above the method on line {@code at}. */
    private static int headOf(String[] lines, int at) {
        int head = at;
        boolean inDoc = false;
        for (int i = at - 1; i >= 0; i--) {
            String s = lines[i].strip();
            if (inDoc) {
                head = i;
                if (s.startsWith("/**") || s.startsWith("/*")) {
                    inDoc = false;
                }
                continue;
            }
            if (s.startsWith("#[")) {
                head = i;
            } else if (s.endsWith("*/")) {
                head = i;
                inDoc = !(s.startsWith("/**") || s.startsWith("/*"));
            } else {
                break;
            }
        }
        return head;
    }

    /** Whether the lines from {@code head} up to the method on line {@code at} mark it a test. */
    private static boolean marked(String[] lines, int head, int at) {
        for (int i = head; i <= at; i++) {
            if (TEST_ATTRIBUTE.matcher(lines[i]).find()
                    || TEST_TAG.matcher(lines[i]).find()) {
                return true;
            }
        }
        return false;
    }
}

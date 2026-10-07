package com.editora.git;

import java.util.ArrayList;
import java.util.List;

import com.editora.git.CommitMessages.SubjectLength;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The commit box's message aids: the length guide, {@code --cleanup=strip}, and the recent-messages list. */
class CommitMessagesTest {

    @Test
    void theSubjectIsMeasuredAgainstFiftyAndSeventyTwo() {
        assertEquals(new CommitMessages.Guide(0, SubjectLength.OK, 0), CommitMessages.guide("", '#'));
        assertEquals(new CommitMessages.Guide(0, SubjectLength.OK, 0), CommitMessages.guide(null, '#'));
        assertEquals(SubjectLength.OK, CommitMessages.guide("x".repeat(50), '#').subject());
        assertEquals(
                SubjectLength.LONG, CommitMessages.guide("x".repeat(51), '#').subject());
        assertEquals(
                SubjectLength.LONG, CommitMessages.guide("x".repeat(72), '#').subject());
        assertEquals(
                SubjectLength.TOO_LONG,
                CommitMessages.guide("x".repeat(73), '#').subject());
        assertEquals(73, CommitMessages.guide("x".repeat(73) + "   ", '#').subjectLength(), "trailing space is free");
    }

    @Test
    void lengthIsCountedInCharactersNotUtf16Units() {
        assertEquals(3, CommitMessages.guide("a😀b", '#').subjectLength(), "an emoji is one character");
    }

    @Test
    void bodyLinesPastTheWrapColumnAreCountedAndCommentsAreNot() {
        String text = "# a template comment far longer than anything " + "x".repeat(80) + "\n"
                + "\n"
                + "Subject\n"
                + "\n"
                + "y".repeat(72) + "\n"
                + "y".repeat(73) + "\n"
                + "# " + "z".repeat(90) + "\n"
                + "z".repeat(100);
        CommitMessages.Guide guide = CommitMessages.guide(text, '#');
        assertEquals(7, guide.subjectLength(), "the first line that is not a comment or blank");
        assertEquals(2, guide.longBodyLines());
        assertEquals(
                1, CommitMessages.guide(text, ';').longBodyLines() - 2, "another comment character: the # lines count");
    }

    @Test
    void stripDoesWhatGitsCleanupStripDoes() {
        assertEquals("", CommitMessages.strip("# Subject\n#\n# Why\n", '#'), "only comments: nothing to commit");
        assertEquals(
                "Subject\n\nBody one\nBody two",
                CommitMessages.strip("\n\n# hint\nSubject   \n\n\n\n# another\nBody one\nBody two\n\n# end\n", '#'));
        assertEquals("#42 fix", CommitMessages.strip("#42 fix", ';'), "the comment character is configurable");
        assertEquals("", CommitMessages.strip(null, '#'));
        assertEquals("a\n\nb", CommitMessages.strip("a\r\n\r\nb\r\n", '#'), "CRLF text");
    }

    @Test
    void theSubjectOfAMessageIsItsFirstLineThatSaysSomething() {
        assertEquals("Fix it", CommitMessages.subject("\n  Fix it  \n\nbody"));
        assertEquals("", CommitMessages.subject("   \n"));
        assertEquals("", CommitMessages.subject(null));
    }

    @Test
    void recentMessagesAreNewestFirstUniqueAndCapped() {
        List<String> history = List.of();
        history = CommitMessages.remember(history, "one", 3);
        history = CommitMessages.remember(history, "two\n\nbody", 3);
        history = CommitMessages.remember(history, "  one  ", 3);
        assertEquals(List.of("one", "two\n\nbody"), history, "used again: moved to the front, not duplicated");
        history = CommitMessages.remember(history, "three", 3);
        history = CommitMessages.remember(history, "four", 3);
        assertEquals(List.of("four", "three", "one"), history, "the oldest falls off");
        assertEquals(history, CommitMessages.remember(history, "   ", 3), "a blank message is not remembered");
        assertEquals(List.of("x"), CommitMessages.remember(null, "x", CommitMessages.HISTORY_MAX));

        List<String> many = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            many = new ArrayList<>(CommitMessages.remember(many, "m" + i, CommitMessages.HISTORY_MAX));
        }
        assertEquals(20, many.size());
        assertEquals("m29", many.get(0));
    }
}

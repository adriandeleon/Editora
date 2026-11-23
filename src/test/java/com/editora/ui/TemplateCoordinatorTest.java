package com.editora.ui;

import java.nio.file.Path;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import com.editora.template.NewFileContent;
import com.editora.template.TemplateOutput;
import com.editora.template.TemplateRegistry;
import org.junit.jupiter.api.Test;

import static com.editora.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** The template coordinator's words and small decisions, without a window. */
class TemplateCoordinatorTest {

    @Test
    void everyRefusedNameSaysWhatToChange() {
        Map<NewFileContent.Refusal, String> want = new EnumMap<>(NewFileContent.Refusal.class);
        want.put(NewFileContent.Refusal.INVALID_NAME, tr("status.newfile.invalidName"));
        want.put(NewFileContent.Refusal.ILLEGAL_CHARACTER, tr("status.newfile.refused.illegalCharacter", "a:b"));
        want.put(NewFileContent.Refusal.RESERVED_NAME, tr("status.newfile.refused.reservedName", "a:b"));
        want.put(NewFileContent.Refusal.HOME_SHORTHAND, tr("status.newfile.refused.homeShorthand"));
        want.put(NewFileContent.Refusal.NOT_A_JAVA_NAME, tr("status.newfile.refused.notJavaName", "a:b"));
        want.put(NewFileContent.Refusal.JAVA_KEYWORD, tr("status.newfile.refused.javaKeyword", "a:b"));
        want.put(NewFileContent.Refusal.NOT_A_JAVA_FILE, tr("status.newfile.refused.notJavaFile", "a:b"));
        want.put(NewFileContent.Refusal.NO_PACKAGE, tr("status.newfile.refused.noPackage"));
        for (NewFileContent.Refusal r : NewFileContent.Refusal.values()) {
            assertEquals(want.get(r), TemplateCoordinator.refusalMessage(r, "  a:b "), r.toString());
        }
        assertEquals(tr("status.newfile.invalidName"), TemplateCoordinator.refusalMessage(null, null));
    }

    @Test
    void everySkippedTemplateFileSaysWhy() {
        Path f = Path.of("t.json");
        Map<TemplateRegistry.ProblemKind, String> want = new EnumMap<>(TemplateRegistry.ProblemKind.class);
        want.put(TemplateRegistry.ProblemKind.MALFORMED_JSON, tr("template.problem.malformedJson", 3, "bad"));
        want.put(TemplateRegistry.ProblemKind.NOT_AN_OBJECT, tr("template.problem.notAnObject"));
        want.put(TemplateRegistry.ProblemKind.MISSING_NAME, tr("template.problem.missingName"));
        want.put(TemplateRegistry.ProblemKind.NO_CONTENT, tr("template.problem.noContent"));
        want.put(TemplateRegistry.ProblemKind.BAD_FILES, tr("template.problem.badFiles"));
        want.put(TemplateRegistry.ProblemKind.RESERVED_ID, tr("template.problem.reservedId"));
        want.put(TemplateRegistry.ProblemKind.UNREADABLE, tr("template.problem.unreadable", "bad"));
        want.put(TemplateRegistry.ProblemKind.NEWER_VERSION, tr("template.problem.newerVersion", "bad"));
        for (TemplateRegistry.ProblemKind k : TemplateRegistry.ProblemKind.values()) {
            assertEquals(
                    want.get(k),
                    TemplateCoordinator.problemText(new TemplateRegistry.Problem(f, k, "bad", 3)),
                    k.toString());
        }
        assertEquals(
                tr("template.problem.newerVersionNoCopy"),
                TemplateCoordinator.problemText(
                        new TemplateRegistry.Problem(f, TemplateRegistry.ProblemKind.NEWER_VERSION, "", 0)));
        assertEquals(
                tr(
                        "status.templates.problems",
                        2,
                        "t.json: " + tr("template.problem.notAnObject") + "; u.json: "
                                + tr("template.problem.missingName")),
                TemplateCoordinator.problemsMessage(List.of(
                        new TemplateRegistry.Problem(f, TemplateRegistry.ProblemKind.NOT_AN_OBJECT, "", 0),
                        new TemplateRegistry.Problem(
                                Path.of("u.json"), TemplateRegistry.ProblemKind.MISSING_NAME, "", 0))));
    }

    @Test
    void theCaretsLineCountsTheLineBreaksBeforeIt() {
        Path p = Path.of("a.txt");
        assertEquals(0, TemplateCoordinator.lineOf(new TemplateOutput.Target(p, "a.txt", "abc", 2, true, false)));
        assertEquals(2, TemplateCoordinator.lineOf(new TemplateOutput.Target(p, "a.txt", "a\nb\nc", 4, true, false)));
        assertEquals(
                2,
                TemplateCoordinator.lineOf(new TemplateOutput.Target(p, "a.txt", "a\nb\n", 99, true, false)),
                "a caret past the end stops at the end");
    }

    @Test
    void aProjectLocationMustBeAbsolute() {
        String home = System.getProperty("user.home");
        assertNull(TemplateCoordinator.projectParent(null));
        assertNull(TemplateCoordinator.projectParent("  "));
        assertNull(TemplateCoordinator.projectParent("relative/dir"), "relative to nothing");
        assertNull(TemplateCoordinator.projectParent("\u0000bad"), "not a path at all");
        assertEquals(Path.of(home), TemplateCoordinator.projectParent("~"));
        assertEquals(Path.of(home, "src"), TemplateCoordinator.projectParent(" ~/src "));
        Path abs = Path.of(home).toAbsolutePath().resolve("work");
        assertEquals(abs, TemplateCoordinator.projectParent(abs.toString()));
    }
}

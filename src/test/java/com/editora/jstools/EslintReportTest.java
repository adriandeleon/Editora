package com.editora.jstools;

import java.util.List;

import com.editora.text.LspDiagnostic;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** ESLint's {@code --format json} report → problems, fixes and the fixed text (JS review F). */
class EslintReportTest {

    /** What ESLint 9 prints for {@code const x = 1;\nvar y == 2} (shape copied from its docs). */
    private static final String TEXT = "const x = 1;\nvar y = x == 2;\n";

    private static final String REPORT = """
            [{"filePath":"/p/src/a.js","messages":[
              {"ruleId":"no-unused-vars","severity":1,"message":"'y' is assigned a value but never used.",
               "line":2,"column":5,"nodeType":"Identifier","messageId":"unusedVar","endLine":2,"endColumn":6,
               "suggestions":[{"messageId":"removeVar","data":{"varName":"y"},
                               "fix":{"range":[13,28],"text":""},"desc":"Remove unused variable 'y'."}]},
              {"ruleId":"no-var","severity":2,"message":"Unexpected var, use let or const instead.",
               "line":2,"column":1,"nodeType":"VariableDeclaration","messageId":"unexpectedVar",
               "endLine":2,"endColumn":16,"fix":{"range":[13,16],"text":"let"}},
              {"ruleId":"eqeqeq","severity":2,"message":"Expected '===' and instead saw '=='.",
               "line":2,"column":11,"endLine":2,"endColumn":13}
             ],"suppressedMessages":[],"errorCount":2,"fatalErrorCount":0,"warningCount":1,
             "fixableErrorCount":1,"fixableWarningCount":0,"source":"const x = 1;\\nvar y = x == 2;\\n",
             "usedDeprecatedRules":[]}]
            """;

    @Test
    void messagesBecomeZeroBasedProblemsWithTheRuleAsCode() {
        EslintReport.Result r = EslintReport.parse(REPORT, TEXT);
        assertEquals(3, r.problems().size());
        LspDiagnostic unused = r.problems().get(0).diagnostic();
        assertEquals(1, unused.startLine());
        assertEquals(4, unused.startCol());
        assertEquals(1, unused.endLine());
        assertEquals(5, unused.endCol());
        assertEquals(LspDiagnostic.Severity.WARNING, unused.severity());
        assertEquals("no-unused-vars", unused.code());
        assertEquals("eslint", unused.source());
        assertEquals(
                LspDiagnostic.Severity.ERROR, r.problems().get(1).diagnostic().severity());
        assertNull(r.output());
        assertEquals(3, r.diagnostics().size());
    }

    @Test
    void fixesAndSuggestionsApplyToTheCheckedText() {
        EslintReport.Result r = EslintReport.parse(REPORT, TEXT);
        EslintReport.Problem unused = r.problems().get(0);
        assertNull(unused.fix());
        assertEquals(1, unused.suggestions().size());
        assertEquals("Remove unused variable 'y'.", unused.suggestions().get(0).description());
        assertEquals("const x = 1;\n\n", unused.suggestions().get(0).fix().applyTo(TEXT));
        EslintReport.Fix noVar = r.problems().get(1).fix();
        assertEquals("const x = 1;\nlet y = x == 2;\n", noVar.applyTo(TEXT));
        assertNull(noVar.applyTo("short"), "another text: the range does not fit");
        assertTrue(r.problems().get(2).suggestions().isEmpty());
    }

    @Test
    void aParseErrorWithOnlyAPositionMarksTheTokenThere() {
        String text = "let a = ;\nfoo bar\n";
        String json = "[{\"filePath\":\"/p/a.js\",\"messages\":[{\"ruleId\":null,\"fatal\":true,\"severity\":2,"
                + "\"message\":\"Parsing error: Unexpected token ;\",\"line\":1,\"column\":9},"
                + "{\"ruleId\":null,\"fatal\":true,\"severity\":2,\"message\":\"x\",\"line\":2,\"column\":5}]}]";
        List<EslintReport.Problem> p = EslintReport.parse(json, text).problems();
        LspDiagnostic semi = p.get(0).diagnostic();
        assertEquals(0, semi.startLine());
        assertEquals(8, semi.startCol());
        assertEquals(9, semi.endCol(), "one character: ';' is no word");
        assertNull(semi.code());
        assertEquals(7, p.get(1).diagnostic().endCol(), "the word 'bar'");
    }

    @Test
    void theIgnoredFileWarningIsDroppedAndTheFixedOutputIsRead() {
        String json = "[{\"filePath\":\"/p/dist/a.js\",\"messages\":[{\"ruleId\":null,\"fatal\":false,\"severity\":1,"
                + "\"message\":\"File ignored because of a matching ignore pattern. Use \\\"--no-ignore\\\" to"
                + " override.\"}],\"output\":\"let a = 1;\\n\"}]";
        EslintReport.Result r = EslintReport.parse(json, "var a = 1;\n");
        assertTrue(r.problems().isEmpty());
        assertEquals("let a = 1;\n", r.output());
        assertTrue(EslintReport.parse("[]", "").problems().isEmpty());
    }

    @Test
    void somethingElseOnStdoutIsNoReport() {
        assertThrows(IllegalArgumentException.class, () -> EslintReport.parse("Oops! Something went wrong", ""));
        assertThrows(IllegalArgumentException.class, () -> EslintReport.parse("{\"results\":[]}", ""));
    }
}

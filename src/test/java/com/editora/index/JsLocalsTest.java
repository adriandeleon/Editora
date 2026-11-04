package com.editora.index;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class JsLocalsTest {

    /** The offset of the declaration the use marked {@code |} refers to; the marker is removed first. */
    private static int resolve(String marked, String name, String language) {
        int at = marked.indexOf('|');
        return JsLocals.declarationOf(marked.replace("|", ""), at, name, language);
    }

    private static int resolve(String marked, String name) {
        return resolve(marked, name, "javascript");
    }

    @Test
    void aLocalInTheSecondFunctionIsItsOwn() {
        String src = "function a() {\n  const result = 1;\n  return result;\n}\n"
                + "function b() {\n  const result = 2;\n  return res|ult;\n}\n";
        assertEquals(src.replace("|", "").lastIndexOf("result = 2"), resolve(src, "result"));
    }

    @Test
    void aParameterShadowsATopLevelVariable() {
        String src = "function f(input: string) {\n  return inp|ut.trim();\n}\nconst input = 3;\n";
        assertEquals(src.indexOf("input:"), resolve(src, "input", "typescript"));
        String arrow = "const g = async (req, res) => {\n  res.end(re|q.url);\n};\nconst req = 1;\n";
        assertEquals(arrow.indexOf("req,"), resolve(arrow, "req"));
        String single = "items.map(item => it|em.id);\nconst item = 0;\n";
        assertEquals(single.indexOf("item =>"), resolve(single, "item"));
    }

    @Test
    void destructuredNamesAndRenamesBind() {
        String src = "const { alpha, beta: b2 } = load();\nconsole.log(b|2, alpha);\n";
        assertEquals(src.indexOf("b2"), resolve(src, "b2"));
        String keyOnly = "const { beta: b2 } = load();\nconsole.log(be|ta);\n";
        assertEquals(-1, resolve(keyOnly, "beta"), "a key is not a binding");
        String param = "function f({ a, b = 2 }, [c]) {\n  return a + |c;\n}\n";
        assertEquals(param.indexOf("[c]") + 1, resolve(param, "c"));
    }

    @Test
    void importsForAndCatchBind() {
        String imp = "import fs, { readFile as rf, stat } from \"node:fs\";\nawait r|f(\"a\");\n";
        assertEquals(imp.indexOf("rf,"), resolve(imp, "rf"));
        assertEquals(-1, resolve("import { readFile as rf } from \"x\";\nreadFi|le();\n", "readFile"));
        String loop = "for (const x of xs) {\n  use(|x);\n}\n";
        assertEquals(loop.indexOf("x of"), resolve(loop, "x"));
        String caught = "try { go(); } catch (err) {\n  log(e|rr);\n}\n";
        assertEquals(caught.indexOf("err)"), resolve(caught, "err"));
    }

    @Test
    void aBlockScopedNameIsNotVisibleOutsideItsBlock() {
        String src = "function f() {\n  if (a) {\n    const tmp = 1;\n  }\n  return t|mp;\n}\n";
        assertEquals(-1, resolve(src, "tmp"));
    }

    @Test
    void aPropertyIsLeftToTheIndex() {
        String src = "class A {\n  start() {}\n  run(start) { return this.st|art(); }\n}\n";
        assertEquals(-1, resolve(src, "start"), "this.start is the method, not the parameter");
    }

    @Test
    void callsAndControlStatementsAreNotParameterLists() {
        String src = "const x = 1;\nfoo(x);\nif (x) {\n  log(|x);\n}\n";
        assertEquals(src.indexOf("x = 1"), resolve(src, "x"));
    }

    @Test
    void otherLanguagesAreNotRead() {
        assertEquals(-1, resolve("x = 1\nprint(|x)\n", "x", "python"));
    }
}

package com.editora.editor;

import java.util.List;

import org.eclipse.tm4e.core.grammar.IGrammar;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The server-free outline's symbols and the token styles, checked against the bundled grammars' real scopes:
 * a type <em>reference</em> is not a declaration, a method is named after itself rather than its receiver
 * type, and only a call's callee is styled as a function.
 */
class OutlineSymbolsTest {

    private static List<String> symbols(String fileName, String text) {
        IGrammar grammar = GrammarRegistry.shared().forFileName(fileName);
        assertNotNull(grammar, "grammar should load for " + fileName);
        return TextMateHighlighter.analyze(text, grammar).symbols().stream()
                .map(s -> s.line() + ":" + s.name() + ":" + s.kind())
                .toList();
    }

    @Test
    void goMethodsAreNamedAfterTheMethodNotTheReceiverType() {
        String go = "package main\n\ntype Server struct {\n}\n\nfunc (s *Server) Run() error {\n\treturn nil\n}\n\n"
                + "func (s *Server) Stop() error {\n\tif cfg, ok := x.(Config); ok {\n\t}\n\treturn nil\n}\n";
        List<String> symbols = symbols("x.go", go);
        assertTrue(symbols.contains("2:Server:type"), symbols.toString());
        assertTrue(symbols.contains("5:Run:function"), symbols.toString());
        assertTrue(symbols.contains("9:Stop:function"), symbols.toString());
        assertFalse(symbols.contains("10:Config:type"), "a type assertion is not a declaration: " + symbols);
    }

    @Test
    void typeScriptTypeReferencesAreNotDeclarations() {
        String ts = "export class Service {\n  constructor(repo: UserRepository) {\n    this.repo = repo;\n  }\n"
                + "  load(id: number): Promise<User> {\n    for (const item of items as Item[]) {\n      handle(item);\n"
                + "    }\n  }\n}\nconst config: Config = {\n  a: 1,\n};\ninterface User {\n  id: number;\n}\n";
        List<String> symbols = symbols("x.ts", ts);
        assertTrue(symbols.contains("0:Service:type"), symbols.toString());
        assertTrue(symbols.contains("4:load:function"), symbols.toString());
        assertTrue(symbols.contains("13:User:type"), symbols.toString());
        for (String bogus : List.of("UserRepository", "Item", "Config", "Promise")) {
            assertTrue(symbols.stream().noneMatch(s -> s.contains(":" + bogus + ":")), bogus + " in " + symbols);
        }
    }

    @Test
    void rustLifetimesAndMatchArmsAreNotTypes() {
        String rs = "pub struct Parser<'a> {\n    input: &'a str,\n}\nimpl<'a> Parser<'a> {\n"
                + "    pub fn next(&mut self) -> Option<Token> {\n        match self.peek() {\n"
                + "            Some(Token::Eof) => {\n            }\n            None => {\n            }\n        }\n    }\n}\n";
        List<String> symbols = symbols("x.rs", rs);
        assertTrue(symbols.contains("0:Parser:type"), symbols.toString());
        assertTrue(symbols.contains("3:Parser:type"), "the impl block is named after its type: " + symbols);
        assertTrue(symbols.contains("4:next:function"), symbols.toString());
        assertEquals(3, symbols.size(), "nothing for the lifetime, Some/None or Token::Eof: " + symbols);
    }

    @Test
    void declarationsKeepTheirSubKindedScopes() {
        assertFalse(TextMateHighlighter.isTypeReference(
                List.of("source.java", "meta.class.java", "entity.name.type.class.java"), "public "));
        assertFalse(TextMateHighlighter.isTypeReference(List.of("source.kotlin", "entity.name.type.kotlin"), "class "));
        assertTrue(TextMateHighlighter.isTypeReference(List.of("source.kotlin", "entity.name.type.kotlin"), "val x: "));
        assertTrue(TextMateHighlighter.isTypeReference(
                List.of("source.ts", "meta.type.annotation.ts", "entity.name.type.module.ts"), "x: "));
    }

    @Test
    void onlyTheCalleeOfACallIsStyledAsAFunction() {
        assertEquals(
                "function",
                TextMateHighlighter.styleForScopes(
                        List.of("source.python", "meta.function-call.python", "meta.function-call.generic.python")));
        assertNull(TextMateHighlighter.styleForScopes(
                List.of("source.python", "meta.function-call.python", "meta.function-call.arguments.python")));
        assertNull(TextMateHighlighter.styleForScopes(List.of(
                "source.python", "meta.function-call.python", "punctuation.definition.arguments.begin.python")));
        assertNull(TextMateHighlighter.styleForScopes(
                List.of("source.java", "meta.method.body.java", "meta.function-call.java")));
        assertEquals(
                "function",
                TextMateHighlighter.styleForScopes(List.of(
                        "source.java",
                        "meta.method.body.java",
                        "meta.function-call.java",
                        "entity.name.function.java")));
    }

    @Test
    void aTypeAnnotationIsNotADecorator() {
        assertEquals(
                "operator",
                TextMateHighlighter.styleForScopes(List.of(
                        "source.ts",
                        "meta.parameters.ts",
                        "meta.type.annotation.ts",
                        "keyword.operator.type.annotation.ts")));
        assertNull(TextMateHighlighter.styleForScopes(
                List.of("source.python", "meta.function.python", "punctuation.separator.annotation.result.python")));
        assertEquals(
                "annotation",
                TextMateHighlighter.styleForScopes(List.of("source.java", "meta.declaration.annotation.java")));
        assertEquals(
                "annotation", TextMateHighlighter.styleForScopes(List.of("source.python", "meta.decorator.python")));
    }
}

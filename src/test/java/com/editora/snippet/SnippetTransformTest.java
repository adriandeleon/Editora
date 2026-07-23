package com.editora.snippet;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class SnippetTransformTest {

    /** Parses the spec of a full {@code ${1/…}} construct, asserting it parsed. */
    private static SnippetTransform of(String afterSlash) {
        SnippetTransform.Parsed p = SnippetTransform.parseAt(afterSlash, 0);
        assertNotNull(p, () -> "expected a parseable transform: " + afterSlash);
        return p.transform();
    }

    // --- the two bundled PowerShell snippets this issue was filed for ---

    @Test
    void powershellForeachItemAppendsTheSuffix() {
        // ${1/(.*)/$1Item/} — the loop variable must not collide with the collection
        assertEquals("collectionItem", of("(.*)/$1Item/}").apply("collection"));
        assertEquals("usersItem", of("(.*)/$1Item/}").apply("users"));
    }

    @Test
    void powershellSplatSanitisesNonWordCharacters() {
        // ${1/[^\w]/_/} — "Get-Item" must become "Get_Item", or PowerShell parses $Get-ItemParams
        // as a subtraction
        assertEquals("Get_Item", of("[^\\w]/_/g}").apply("Get-Item"));
    }

    @Test
    void withoutTheGlobalFlagOnlyTheFirstMatchIsReplaced() {
        assertEquals("Get_Item-Name", of("[^\\w]/_/}").apply("Get-Item-Name"));
        assertEquals("Get_Item_Name", of("[^\\w]/_/g}").apply("Get-Item-Name"));
    }

    // --- group references ---

    @Test
    void groupsAreReferencedByNumber() {
        assertEquals("bar-foo", of("(\\w+)_(\\w+)/$2-$1/}").apply("foo_bar"));
        assertEquals("bar-foo", of("(\\w+)_(\\w+)/${2}-${1}/}").apply("foo_bar"));
    }

    @Test
    void groupZeroIsTheWholeMatch() {
        assertEquals("[foo]", of("foo/[$0]/}").apply("foo"));
    }

    @Test
    void aMissingGroupResolvesToEmptyRatherThanThrowing() {
        assertEquals("x", of("(\\w+)/x$9/}").apply("foo"));
    }

    @Test
    void unmatchedTextIsCopiedThrough() {
        assertEquals("keep [foo] keep", of("foo/[$0]/}").apply("keep foo keep"));
    }

    @Test
    void noMatchLeavesTheInputAlone() {
        assertEquals("hello", of("zzz/x/}").apply("hello"));
    }

    // --- case modifiers ---

    @Test
    void upcaseAndDowncase() {
        assertEquals("FOO", of("(.*)/${1:/upcase}/}").apply("foo"));
        assertEquals("foo", of("(.*)/${1:/downcase}/}").apply("FOO"));
    }

    @Test
    void capitalizeTouchesOnlyTheFirstCharacter() {
        assertEquals("FooBar", of("(.*)/${1:/capitalize}/}").apply("fooBar"));
        // deliberately NOT title-case: the remainder is left exactly as it was
        assertEquals("FOO", of("(.*)/${1:/capitalize}/}").apply("fOO"));
        assertEquals("Foo bar", of("(.*)/${1:/capitalize}/}").apply("foo bar"));
    }

    @Test
    void wordBasedModifiers() {
        assertEquals("fooBarBaz", of("(.*)/${1:/camelcase}/}").apply("foo_bar_baz"));
        assertEquals("FooBarBaz", of("(.*)/${1:/pascalcase}/}").apply("foo_bar_baz"));
        assertEquals("foo_bar_baz", of("(.*)/${1:/snakecase}/}").apply("fooBarBaz"));
        assertEquals("foo-bar-baz", of("(.*)/${1:/kebabcase}/}").apply("fooBarBaz"));
    }

    @Test
    void aModifierOnAnEmptyGroupYieldsEmpty() {
        // VS Code: every modifier returns '' for a falsy value.
        // The pattern must consume the whole input — an all-optional pattern matches the empty string at
        // offset 0 and the untouched remainder is then copied through, which is correct but tests nothing.
        assertEquals("X", of("(x)?y/${1:/upcase}/}").apply("xy"));
        assertEquals("", of("(x)?y/${1:/upcase}/}").apply("y"));
    }

    // --- conditionals ---

    @Test
    void plusFormIsUsedOnlyWhenTheGroupMatched() {
        assertEquals("yes", of("(foo)?bar/${1:+yes}/}").apply("foobar"));
        assertEquals("", of("(foo)?bar/${1:+yes}/}").apply("bar"));
    }

    @Test
    void minusFormIsUsedOnlyWhenTheGroupDidNot() {
        assertEquals("foo", of("(foo)?bar/${1:-no}/}").apply("foobar"));
        assertEquals("no", of("(foo)?bar/${1:-no}/}").apply("bar"));
    }

    @Test
    void bareFormIsShorthandForMinus() {
        assertEquals("foo", of("(foo)?bar/${1:no}/}").apply("foobar"));
        assertEquals("no", of("(foo)?bar/${1:no}/}").apply("bar"));
    }

    @Test
    void ternaryFormPicksEitherBranch() {
        assertEquals("Y", of("(foo)?bar/${1:?Y:N}/}").apply("foobar"));
        assertEquals("N", of("(foo)?bar/${1:?Y:N}/}").apply("bar"));
    }

    @Test
    void anAllOptionalPatternCopiesTheRemainderThrough() {
        // pins the semantics the tests above dodge: an empty match at offset 0 replaces nothing else
        assertEquals("bar", of("(foo)?/${1:+yes}/}").apply("bar"));
    }

    // --- escapes and literals ---

    @Test
    void escapedSlashIsLiteralInBothSections() {
        assertEquals("a/b", of("X/a\\/b/}").apply("X"));
    }

    @Test
    void literalDollarIsKeptWhenItIsNotAReference() {
        assertEquals("$ x", of("(x)/$ $1/}").apply("x"));
    }

    @Test
    void newlineAndTabEscapes() {
        assertEquals("a\nb", of("X/a\\nb/}").apply("X"));
        assertEquals("a\tb", of("X/a\\tb/}").apply("X"));
    }

    // --- flags ---

    @Test
    void caseInsensitiveFlag() {
        assertEquals("hit", of("foo/hit/i}").apply("FOO"));
        assertEquals("FOO", of("foo/hit/}").apply("FOO"));
    }

    // --- parsing boundaries ---

    @Test
    void parseReportsTheIndexPastTheClosingBrace() {
        String s = "(.*)/$1Item/} trailing";
        SnippetTransform.Parsed p = SnippetTransform.parseAt(s, 0);
        assertNotNull(p);
        assertEquals(" trailing", s.substring(p.end()));
    }

    @Test
    void anUnbalancedBraceInsideTheRegexDoesNotDerailParsing() {
        // brace-counting would run off the end here; splitting on unescaped '/' does not
        SnippetTransform.Parsed p = SnippetTransform.parseAt("[}]/X/}", 0);
        assertNotNull(p);
        assertEquals("XaX", p.transform().apply("}a}").replace("}", "X"));
    }

    @Test
    void malformedSpecsReturnNullSoTheCallerCanFallBack() {
        assertNull(SnippetTransform.parseAt("(.*)", 0), "no format section");
        assertNull(SnippetTransform.parseAt("(.*)/x", 0), "no flags section");
        assertNull(SnippetTransform.parseAt("(.*)/x/", 0), "unterminated");
        assertNull(SnippetTransform.parseAt("([/x/}", 0), "regex will not compile");
        assertNull(SnippetTransform.parseAt(null, 0));
    }

    @Test
    void nullInputAppliesAsEmpty() {
        assertEquals("", of("(.*)/$1/}").apply(null));
    }
}

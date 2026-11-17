package com.editora.dap;

import com.editora.dap.DotnetTests.Target;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** C#'s Debug Test at Caret: the test at the caret, its filter, the test host's process ({@link DotnetTests}). */
class DotnetTestsTest {

    private static final String XUNIT = """
            namespace Shop.Tests;

            public class PriceTests
            {
                private int Helper() => 3;

                [Fact]
                public void Total()
                {
                    int price = 7;
                    Assert.Equal(21, price * Helper());
                }

                [Theory]
                [InlineData(1, 2)]
                public async Task Sums(int a, int b)
                {
                    await Task.Yield();
                }
            }
            """;

    @Test
    void theTestAtTheCaretIsTheMethodItIsIn() {
        assertEquals(new Target("Shop.Tests", "PriceTests", "Total"), DotnetTests.testAt(XUNIT, 9), "in its body");
        assertEquals(new Target("Shop.Tests", "PriceTests", "Total"), DotnetTests.testAt(XUNIT, 6), "on [Fact]");
        assertEquals(new Target("Shop.Tests", "PriceTests", "Sums"), DotnetTests.testAt(XUNIT, 14), "on [Theory]");
        assertEquals(new Target("Shop.Tests", "PriceTests", "Sums"), DotnetTests.testAt(XUNIT, 18));
        assertEquals(new Target("Shop.Tests", "PriceTests", null), DotnetTests.testAt(XUNIT, 4), "a helper: the class");
        assertEquals(new Target("Shop.Tests", "PriceTests", null), DotnetTests.testAt(XUNIT, 3), "the class line");
        assertNull(DotnetTests.testAt(XUNIT, 0), "above every class");
    }

    @Test
    void nunitAndMsTestInABlockNamespace() {
        String nunit = """
                using NUnit.Framework;
                namespace Shop.Tests
                {
                    [TestFixture]
                    public class CartTests
                    {
                        [TestCase(2)]
                        public void Counts(int n)
                        {
                        }
                    }
                }
                """;
        assertEquals(new Target("Shop.Tests", "CartTests", "Counts"), DotnetTests.testAt(nunit, 8));
        String mstest = """
                [TestClass]
                public sealed class Plain
                {
                    [TestMethod]
                    public void Works() { }
                }
                """;
        assertEquals(new Target("", "Plain", "Works"), DotnetTests.testAt(mstest, 4), "no namespace");
        assertNull(DotnetTests.testAt("public class Program { static void Main() { } }", 0), "no tests");
    }

    @Test
    void aHelperTypeInsideATestClassIsNotTheTestClass() {
        String text = """
                namespace Shop.Tests;

                public class OrderTests
                {
                    private record Row(string Name, int Qty);

                    private class FakeRepo : IRepo
                    {
                        public int Count() { return 0; }
                    }

                    [Fact]
                    public void Totals()
                    {
                        var r = new Row("a", 1);
                    }
                }
                """;
        assertEquals(
                new Target("Shop.Tests", "OrderTests", "Totals"), DotnetTests.testAt(text, 15), "after the helpers");
        assertEquals(new Target("Shop.Tests", "OrderTests", null), DotnetTests.testAt(text, 4), "on the record");
        assertEquals(
                new Target("Shop.Tests", "OrderTests", null),
                DotnetTests.testAt(text, 8),
                "inside the fake: its test class");
    }

    @Test
    void aTestClassNestedInAnotherIsNamedOuterPlusInner() {
        String text = """
                namespace Shop.Tests;

                public class CartTests
                {
                    public class WhenEmpty
                    {
                        [Fact]
                        public void HasNoItems()
                        {
                        }
                    }

                    [Fact]
                    public void Adds() { }
                }
                """;
        Target nested = DotnetTests.testAt(text, 8);
        assertEquals(new Target("Shop.Tests", "CartTests+WhenEmpty", "HasNoItems"), nested);
        assertEquals("Shop.Tests.CartTests+WhenEmpty", nested.fullClassName());
        assertEquals("WhenEmpty.HasNoItems", nested.displayName());
        assertEquals(
                "FullyQualifiedName=Shop.Tests.CartTests+WhenEmpty.HasNoItems"
                        + "|FullyQualifiedName~Shop.Tests.CartTests+WhenEmpty.HasNoItems\\(",
                DotnetTests.filter(nested));
        assertEquals(new Target("Shop.Tests", "CartTests", "Adds"), DotnetTests.testAt(text, 13), "after the nested");
    }

    @Test
    void anAttributeOnTheMethodsLineMakesItATest() {
        String text = """
                public class OneLine
                {
                    [Fact] public void First() { Assert.True(true); }
                    [Theory, InlineData(1)] public void Second(int n)
                    {
                        Assert.Equal(1, n);
                    }
                }
                """;
        assertEquals(new Target("", "OneLine", "First"), DotnetTests.testAt(text, 2));
        assertEquals(new Target("", "OneLine", "Second"), DotnetTests.testAt(text, 5));
    }

    @Test
    void aCallInATestIsNotAnotherMethod() {
        String text = """
                public class Calls
                {
                    [Fact]
                    public void Works()
                    {
                        return Helper(1);
                        var s = "{";
                    }
                }
                """;
        assertEquals(new Target("", "Calls", "Works"), DotnetTests.testAt(text, 6));
    }

    @Test
    void theFilterNamesTheTestOrItsClass() {
        assertEquals(
                "FullyQualifiedName=Shop.Tests.PriceTests.Total|FullyQualifiedName~Shop.Tests.PriceTests.Total\\(",
                DotnetTests.filter(new Target("Shop.Tests", "PriceTests", "Total")));
        assertEquals(
                "FullyQualifiedName~Shop.Tests.PriceTests.",
                DotnetTests.filter(new Target("Shop.Tests", "PriceTests", null)));
        assertEquals("Plain.Works", new Target("", "Plain", "Works").displayName());
    }

    @Test
    void aTestProjectAndTheHostsProcess() {
        assertTrue(DotnetTests.isTestProject("<PackageReference Include=\"Microsoft.NET.Test.Sdk\" Version=\"17\" />"));
        assertTrue(DotnetTests.isTestProject("<Project Sdk=\"MSTest.Sdk/3.6.4\">"));
        assertTrue(DotnetTests.isTestProject("<PackageReference Include=\"xunit\" />"));
        assertFalse(
                DotnetTests.isTestProject("<Project Sdk=\"Microsoft.NET.Sdk\"><OutputType>Exe</OutputType></Project>"));
        assertEquals(2013826, DotnetTests.processId("Process Id: 2013826, Name: dotnet"));
        assertEquals(-1, DotnetTests.processId("Host debugging is enabled."));
    }
}

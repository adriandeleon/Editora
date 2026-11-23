package com.editora.run;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PhpRunLinesTest {

    @Test
    void aScriptRunsFromTheTop() {
        assertEquals(0, PhpRunLines.runLine("job.php", "<?php\n$x = 1;\necho $x;\n"));
        assertEquals(0, PhpRunLines.runLine("hi.php", "<?php\nnamespace App;\n\nmain();\n\nfunction main() {}\n"));
        assertEquals(0, PhpRunLines.runLine("cli.php", "<?php\nif ($argc > 1) { run(); }\n"));
    }

    @Test
    void entriesRunFromTheTopAndATestFileAtItsClass() {
        assertEquals(0, PhpRunLines.runLine("artisan", "#!/usr/bin/env php\n<?php\nrequire 'x';\n"));
        assertEquals(0, PhpRunLines.runLine("index.php", "<?php\nrequire 'app.php';\n"));
        assertEquals(
                3,
                PhpRunLines.runLine(
                        "CartTest.php",
                        "<?php\nuse PHPUnit\\Framework\\TestCase;\n\nfinal class CartTest extends TestCase\n{\n}\n"));
    }

    @Test
    void aFileOfDeclarationsHasNoRun() {
        assertEquals(-1, PhpRunLines.runLine("Cart.php", """
                <?php
                declare(strict_types=1);

                namespace Shop;

                use Shop\\Money;

                #[Entity]
                final class Cart
                {
                    public function add(): void { echo "added"; $this->items[] = 1; }
                }
                """));
        assertEquals(-1, PhpRunLines.runLine("helpers.php", """
                <?php
                if (! function_exists('money')) {
                    function money($x) { return $x; }
                }
                """));
        assertEquals(-1, PhpRunLines.runLine("app.php", "<?php\nreturn [\n    'name' => 'shop',\n];\n"));
        assertEquals(-1, PhpRunLines.runLine("routes.php", "<?php\nrequire __DIR__ . '/web.php';\n"));
    }

    @Test
    void stringsCommentsHeredocsAndTemplatesDoNotCount() {
        assertFalse(PhpRunLines.hasTopLevelCode("<?php\n// echo 1;\n# run();\n/* go(); */\nclass A {}\n"));
        assertFalse(PhpRunLines.hasTopLevelCode("<?php\nclass A { const T = <<<EOT\n}; echo 1;\nEOT;\n}\n"));
        assertFalse(PhpRunLines.hasTopLevelCode("<h1>Title</h1>\n<p>text</p>\n"));
        assertTrue(PhpRunLines.hasTopLevelCode("<h1><?= $title ?></h1>\n"), "a template that prints is run");
        assertTrue(PhpRunLines.hasTopLevelCode("<?php\n$app = new App();\n$app->run(function () { return 1; });\n"));
    }
}

package com.editora.text;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ModelineTest {

    private static String ext(String text) {
        Shebang.Result r = Modeline.detect(text, text);
        return r == null ? null : r.extension();
    }

    @Test
    void vimModelinesNameTheFiletype() {
        assertEquals("sh", ext("# vim: ft=sh\necho hi\n"));
        assertEquals("sh", ext("# vim: set filetype=bash :\necho hi\n"));
        assertEquals("sh", ext("# vi:syntax=zsh\n"));
        assertEquals("sh", ext("# ex: set ts=4 sw=4 ft=sh:\n"));
        assertEquals("py", ext("x = 1\n# vim: ft=python\n"));
    }

    @Test
    void reactAndEmacsJavaScriptModes() {
        assertEquals("tsx", ext("// vim: ft=typescriptreact\n"));
        assertEquals("jsx", ext("/* vim: set filetype=javascriptreact : */\n"));
        assertEquals("jsx", ext("// vim: set ft=jsx:\n"));
        assertEquals("js", ext("// -*- mode: js2 -*-\n"));
        assertEquals("jsx", ext("// -*- mode: rjsx -*-\n"));
        assertEquals("tsx", ext("// -*- mode: typescript-tsx -*-\n"));
    }

    /** vim reads the first and last five lines only. */
    @Test
    void vimModelinesAreReadAtEitherEnd() {
        String middle = "a\nb\nc\nd\ne\nf\n# vim: ft=sh\ng\nh\ni\nj\nk\nl\n";
        assertNull(ext(middle));
        assertEquals("sh", ext("a\nb\nc\nd\ne\nf\ng\nh\n# vim: ft=sh\n"));
    }

    @Test
    void emacsModeLines() {
        assertEquals("sh", ext("# -*- mode: sh -*-\necho hi\n"));
        assertEquals("sh", ext("# -*- sh -*-\n"));
        assertEquals("sh", ext("# -*- mode: shell-script; indent-tabs-mode: nil -*-\n"));
        assertEquals("sh", ext("# -*- bash-ts-mode -*-\n"));
        // The second line counts when the first is a shebang it cannot read.
        assertEquals("sh", ext("#!/usr/bin/perl -w\n# -*- mode: sh -*-\n"));
        // Not on a later line.
        assertNull(ext("one\ntwo\n# -*- mode: sh -*-\n"));
    }

    @Test
    void aShebangWinsAndUnknownNamesAreIgnored() {
        assertEquals("py", ext("#!/usr/bin/env python3\n# vim: ft=sh\n"));
        assertNull(ext("# vim: ft=cobol\n"));
        assertNull(ext("just some prose about vim and emacs\n"));
        assertNull(Modeline.detect(null, null));
    }

    @Test
    void scalaAndClojureModes() {
        assertEquals("scala", ext("// vim: ft=scala\nobject A\n"));
        assertEquals("clj", ext(";; -*- mode: clojure -*-\n(ns a)\n"));
        assertEquals("cljs", ext(";; -*- clojurescript -*-\n"));
        assertEquals("clj", ext("(ns a)\n;; vim: set filetype=clojure :\n"));
    }

    /** A sourced library that says so: ShellCheck's dialect directive makes it shell (Bash review A15). */
    @Test
    void aShellcheckShellDirectiveMakesTheFileShell() {
        assertEquals("sh", ext("# shellcheck shell=bash\nlog() { echo \"$*\"; }\n"));
        assertEquals("sh", ext("# Helpers.\n#shellcheck disable=SC2034 shell=sh\nX=1\n"));
        assertNull(ext("<?php\n// shell=bash is not a directive\n"), "a .inc that is PHP stays what it was");
        assertNull(ext("log() { echo hi; }\n"));
    }
}

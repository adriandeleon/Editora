package com.editora.ui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Project Map review 3, cards: the pure helpers behind the card fixes. */
class ProjectMapCardsReview3Test {

    @Test
    void aCardOpensPastALeadingLicenceHeaderOnly() {
        String go = "/*\nCopyright 2014 The Kubernetes Authors.\n\nLicensed under the Apache License.\n*/\n\n"
                + "package main\n\nimport \"os\"\n";
        assertEquals(6, ProjectMapPreview.openingLine(go), "Go: the package clause");
        String java = ProjectMapCardsReview3FxTest.source();
        assertEquals(6, ProjectMapPreview.openingLine(java), "Java: past the block and the blank line");
        String python = "#!/usr/bin/env python3\n# Copyright (c) 2026 Someone\n# SPDX-License-Identifier: MIT\n"
                + "# All rights reserved.\n\nimport os\n";
        assertEquals(5, ProjectMapPreview.openingLine(python), "a shebang and a run of # lines");
        String xml = "<?xml version=\"1.0\"?>\n<!--\n  Licensed to the ASF.\n  See the NOTICE file.\n-->\n<project/>\n";
        assertEquals(5, ProjectMapPreview.openingLine(xml));

        String doc = "/**\n * The whole point of this file.\n * It explains itself.\n */\nclass A {}\n";
        assertEquals(0, ProjectMapPreview.openingLine(doc), "a file's own doc comment is read, not skipped");
        assertEquals(0, ProjectMapPreview.openingLine("// Copyright 2026\npackage x;\n"), "one line is kept");
        assertEquals(0, ProjectMapPreview.openingLine("/*\n * Copyright\n * Licence, never closed\n"));
        assertEquals(0, ProjectMapPreview.openingLine("package a;\n"));
        assertEquals(0, ProjectMapPreview.openingLine("/*\n Copyright\n License\n*/\n\n\n"), "nothing after it");
        assertEquals(0, ProjectMapPreview.openingLine(""));
    }

    @Test
    void aCardsTitleKeepsTheNameUpToTheParameters() {
        assertEquals("kubelet.go", ProjectMapPreview.titleKey("kubelet.go", false));
        assertEquals("a(b).txt", ProjectMapPreview.titleKey("a(b).txt", false), "a file's whole name");
        assertEquals("kubelet.go › Run", ProjectMapPreview.titleKey("kubelet.go › Run(ctx, updates)", true));
        assertEquals("x(1).go › Run", ProjectMapPreview.titleKey("x(1).go › Run(ctx)", true), "after the ›");
        assertEquals("App.java › App", ProjectMapPreview.titleKey("App.java › App", true));
    }
}

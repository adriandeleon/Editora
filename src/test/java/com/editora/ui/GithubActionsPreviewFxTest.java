package com.editora.ui;

import java.util.ArrayList;
import java.util.List;

import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Label;
import javafx.scene.layout.VBox;

import com.editora.editor.GithubActionsPreview;
import com.editora.ghactions.Workflow;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** Renders the GitHub Actions preview node on the FX thread and checks the decoded content. */
@Tag("fx")
class GithubActionsPreviewFxTest {

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
    }

    @Test
    void rendersTriggersAndJobs() throws Exception {
        String text = """
                name: CI
                on:
                  push:
                    branches: [main]
                  schedule:
                    - cron: '0 2 * * 1-5'
                jobs:
                  build:
                    runs-on: ubuntu-latest
                    steps:
                      - uses: actions/checkout@v4
                      - run: npm test
                  deploy:
                    needs: build
                    runs-on: ubuntu-latest
                    steps:
                      - run: ./deploy.sh
                """;
        List<String> labels = FxTestSupport.callOnFx(() -> {
            VBox n = GithubActionsPreview.content(Workflow.parse(text), 700);
            n.applyCss();
            List<String> out = new ArrayList<>();
            walk(n, out);
            return out;
        });
        String all = String.join("\n", labels);
        assertTrue(all.contains("CI"), all);
        assertTrue(all.contains("push to main"), all);
        assertTrue(all.contains("Monday through Friday"), all); // schedule cron decoded
        assertTrue(all.contains("1. checkout"), all);
        assertTrue(labels.stream().anyMatch(s -> s.contains("needs build")), all);
    }

    private static void walk(Node node, List<String> out) {
        if (node instanceof Label l && l.getText() != null && !l.getText().isBlank()) {
            out.add(l.getText());
        }
        if (node instanceof Parent p) {
            for (Node child : p.getChildrenUnmodifiable()) {
                walk(child, out);
            }
        }
    }
}

package com.editora.ui;

import java.util.*;

import javafx.scene.Scene;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;

import com.editora.editor.EditorBuffer;
import com.editora.lsp.LspTestHooks;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Component costs, without JSON transport or server time; run outside coverage instrumentation. */
@Tag("probe")
class JavaEditingCostProbeTest {
    public static void main(String[] args) throws Exception {
        try {
            new JavaEditingCostProbeTest().largeDocumentCosts();
        } finally {
            javafx.application.Platform.exit();
        }
    }

    @Test
    void largeDocumentCosts() throws Exception {
        assumeTrue(Boolean.getBoolean("lsp.java.cost.probe"));
        FxTestSupport.bootToolkit();
        for (int size : List.of(16_384, 262_144, 1_048_576, 4_194_304)) {
            String text = "class CostProbe {\n" + "  // padding for ordinary Java source line\n".repeat(size / 43)
                    + "  void method() {}\n}\n";
            var samples = new LinkedHashMap<String, List<Double>>();
            FxTestSupport.runOnFx(() -> {
                EditorBuffer buffer = new EditorBuffer();
                buffer.setLanguageOverride("java");
                buffer.setAutocomplete(false, false, false, false);
                buffer.setContent(text);
                Stage stage = new Stage();
                stage.setScene(new Scene(new StackPane(buffer.getNode()), 1000, 720));
                stage.show();
                buffer.getArea().requestFocus();
                try {
                    String previous = buffer.text();
                    for (int i = 0; i < 35; i++) {
                        buffer.getArea().moveTo(buffer.getArea().getLength() - 3);
                        long start = System.nanoTime();
                        buffer.typeString("x");
                        record(samples, "key", start, i);
                        start = System.nanoTime();
                        String current = buffer.text();
                        record(samples, "snapshot", start, i);
                        start = System.nanoTime();
                        buffer.text();
                        record(samples, "cached-snapshot", start, i);
                        start = System.nanoTime();
                        LspTestHooks.computeSyncDiff(previous, current);
                        record(samples, "sync-diff-and-range", start, i);
                        previous = current;
                    }
                } finally {
                    buffer.dispose();
                    stage.close();
                }
            });
            samples.forEach((name, values) -> {
                Collections.sort(values);
                System.out.printf(
                        Locale.ROOT,
                        "COST chars=%d stage=%s n=%d median=%.3f p95=%.3f max=%.3f ms%n",
                        text.length(),
                        name,
                        values.size(),
                        values.get(values.size() / 2),
                        values.get((int) Math.ceil(values.size() * .95) - 1),
                        values.getLast());
            });
        }
    }

    private static void record(Map<String, List<Double>> samples, String stage, long start, int iteration) {
        if (iteration >= 5)
            samples.computeIfAbsent(stage, ignored -> new ArrayList<>()).add((System.nanoTime() - start) / 1e6);
    }
}
